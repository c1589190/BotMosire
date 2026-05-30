package com.cna.apcore;

import com.cna.Main;
import com.cna.Utils;
import com.cna.agent.AgentInput.DefaultAgentInputUnit;
import com.cna.agent.AgentInputHandlers.DefaultAgentInputHandlerUnit;
import com.cna.agent.AgentTask.DefaultAgentTaskUnit;
import com.cna.agent.AgentTool.*;
import com.cna.agent.AgentTool.io.*;
import com.cna.agent.AgentTasksHandlers.DefaultAgentTaskHandler;
import com.cna.agent.code.DelegateComputerTaskTool;
import com.cna.apcore.config.CoreConfig;
import com.cna.apcore.db.ExperiencesDB;
import com.cna.apcore.db.FeelingsDB;
import com.cna.apcore.model.CognitiveAction;
import com.cna.apcore.model.CognitivePrepareUnit;
import com.cna.apcore.pool.CognitivePreparePool;
import com.cna.config.ConfigsManager;
import com.cna.db.FeelingHypergraphManager;
import com.cna.llm.LLMAdapter;
import com.cna.llm.LLManager;
import com.cna.plugin.MosireAPI;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * V4 认知动作循环主编排器 — 完全取代旧 LivingLoop。
 *
 * 职责：
 * 1. 持有 CognitivePreparePool + ExperiencesDB + FeelingsDB
 * 2. Tick 调度：每 COGNITIVE_TICK_MS 触发一次选择周期
 * 3. 直接从 Main.AgentInputTasksQueue 拉取输入（不再依赖 LivingLoop 中间层）
 * 4. 自持工具箱（自带全部 DefaultAgentToolUnit，不再从 LivingLoop 导入）
 * 5. 实现 MosireAPI，供 PluginsManager 和 ConsoleCommandSystem 接入
 * 6. 选中单元 → 构建 prompt → LLM 调用 → 工具执行 → 经验/感觉存储
 */
@Slf4j
public class ActionLoop implements MosireAPI {

    private static volatile ActionLoop INSTANCE;

    private final CognitivePreparePool preparePool;
    private final ExperiencesDB experiencesDB;
    private final FeelingsDB feelingsDB;
    private final LLMAdapter brainLLM;
    private final LLMAdapter embLLM;
    private final Map<String, DefaultAgentToolUnit> toolbox;
    private final ObjectMapper mapper;

    private final ScheduledExecutorService tickScheduler;
    private final ExecutorService actionExecutor;
    private final AtomicBoolean isProcessing;

    // 控制台监听器列表（供 ApcoreConsole 等外部组件订阅 LLM 响应）
    private final List<Consumer<ActionNotification>> consoleListeners = new CopyOnWriteArrayList<>();

    // 统计计数器（调试用）
    private final AtomicInteger tickCount = new AtomicInteger(0);
    private final AtomicInteger inputProcessedCount = new AtomicInteger(0);
    private final AtomicInteger actionProcessedCount = new AtomicInteger(0);
    private final AtomicInteger toolExecutedCount = new AtomicInteger(0);
    private final AtomicInteger experienceStoredCount = new AtomicInteger(0);
    private final AtomicInteger feelingStimulatedCount = new AtomicInteger(0);

    private ActionLoop() {
        this.preparePool = new CognitivePreparePool();
        this.experiencesDB = ExperiencesDB.getInstance();
        this.feelingsDB = FeelingsDB.getInstance();
        this.brainLLM = new LLMAdapter(ConfigsManager.BRAIN_CONFIG);
        this.embLLM = new LLMAdapter(ConfigsManager.EMBEDDING_CONFIG);
        this.toolbox = new ConcurrentHashMap<>();
        this.mapper = new ObjectMapper();
        this.tickScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "V4-ActionLoop-Tick");
            t.setDaemon(true);
            return t;
        });
        this.actionExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "V4-ActionLoop-Exec");
            t.setDaemon(true);
            return t;
        });
        this.isProcessing = new AtomicBoolean(false);

        // ── 自持工具箱初始化（不依赖 LivingLoop） ──
        initToolbox();

        log.info("[ActionLoop] ✅ V4 认知动作循环已初始化（自持工具箱: {} 工具, Tick间隔: {}ms, 准备池上限: {}）",
                toolbox.size(), CoreConfig.COGNITIVE_TICK_MS, CoreConfig.MAX_POOL_SIZE);
    }

    public static ActionLoop getInstance() {
        if (INSTANCE == null) {
            synchronized (ActionLoop.class) {
                if (INSTANCE == null) {
                    INSTANCE = new ActionLoop();
                }
            }
        }
        return INSTANCE;
    }

    // ==========================================
    // 控制台通知（供 ApcoreConsole 等外部组件订阅 LLM 响应）
    // ==========================================

    /** 认知动作处理完成后的通知 */
    public record ActionNotification(
            int actionNum,
            String actionSummary,
            String llmThoughts,
            int toolCallCount,
            List<String> toolResults,
            int experienceId,
            int stimulatedFeelingCount,
            long llmElapsedMs,
            int poolSizeAfter
    ) {}

    /** 注册控制台监听器 */
    public void addConsoleListener(Consumer<ActionNotification> listener) {
        consoleListeners.add(listener);
        log.info("[ActionLoop] 📡 注册控制台监听器 (总计: {})", consoleListeners.size());
    }

    /** 移除控制台监听器 */
    public void removeConsoleListener(Consumer<ActionNotification> listener) {
        consoleListeners.remove(listener);
        log.info("[ActionLoop] 📡 移除控制台监听器 (剩余: {})", consoleListeners.size());
    }

    // ==========================================
    // MosireAPI 实现（供 PluginsManager / ConsoleCommandSystem 接入）
    // ==========================================

    @Override
    public Logger getLogger() {
        return log;
    }

    /** 直接推送 CognitivePrepareUnit 到准备池（LivingLoop 兼容桥接） */
    public void pushPrepareUnit(CognitivePrepareUnit unit) {
        if (unit != null) {
            preparePool.push(unit);
            inputProcessedCount.incrementAndGet();
        }
    }

    @Override
    public void registerTool(DefaultAgentToolUnit tool) {
        if (tool == null || tool.getName() == null) {
            log.warn("[ActionLoop][MosireAPI] 拒绝注册空工具");
            return;
        }
        if (toolbox.containsKey(tool.getName())) {
            log.warn("[ActionLoop][MosireAPI] 工具 '{}' 已存在，将被插件版本覆写", tool.getName());
        }
        toolbox.put(tool.getName(), tool);
        log.info("[ActionLoop][MosireAPI] ✅ 注册工具: {} (总计: {})", tool.getName(), toolbox.size());
    }

    @Override
    public void registerTaskHandler(DefaultAgentTaskHandler handler) {
        // V4 不使用旧的 TaskHandler 架构，但插件可能会调用
        log.info("[ActionLoop][MosireAPI] ⚠️ 插件尝试注册 TaskHandler: {} — V4 架构不使用此机制，已忽略",
                handler != null ? handler.getClass().getSimpleName() : "null");
    }

    @Override
    public void registerInputHandler(DefaultAgentInputHandlerUnit handler) {
        // V4 不使用旧的 InputHandler 架构
        log.info("[ActionLoop][MosireAPI] ⚠️ 插件尝试注册 InputHandler: {} — V4 架构不使用此机制，已忽略",
                handler != null ? handler.getClass().getSimpleName() : "null");
    }

    @Override
    public void pushInput(DefaultAgentInputUnit input) {
        if (input == null) {
            log.debug("[ActionLoop][MosireAPI] pushInput(null) 被调用，已忽略");
            return;
        }

        String source = extractSource(input);
        String text = extractText(input);

        if (text == null || text.isBlank()) {
            log.debug("[ActionLoop][MosireAPI] pushInput 文本为空，来源: {}", source);
            return;
        }

        // 计算 SE（刺激能量）
        double se = computeStimulateEnergy(input, source);
        CognitivePrepareUnit cpu = CognitivePrepareUnit.create(text, List.of(source), se);

        preparePool.push(cpu);
        int count = inputProcessedCount.incrementAndGet();
        log.info("[ActionLoop][MosireAPI] 📥 直接推送 Input → 准备池 (总计: {}): source={}, textLen={}, SE={:.3f}",
                count, source, text.length(), se);
    }

    @Override
    public void pushTask(DefaultAgentTaskUnit task) {
        if (task == null) {
            log.debug("[ActionLoop][MosireAPI] pushTask(null) 被调用，已忽略");
            return;
        }

        String text = task.getTaskText();
        List<String> sources = task.getSources();
        if (sources == null || sources.isEmpty()) {
            sources = List.of("system:internal");
        }

        if (text == null || text.isBlank()) {
            log.debug("[ActionLoop][MosireAPI] pushTask 文本为空: {}", task.getClass().getSimpleName());
            return;
        }

        // 将旧式任务转换为认知准备单元
        double se = 0.6; // 内部任务给予中等 SE
        CognitivePrepareUnit cpu = CognitivePrepareUnit.create(text, sources, se);
        preparePool.push(cpu);
        int count = inputProcessedCount.incrementAndGet();
        log.info("[ActionLoop][MosireAPI] 📥 旧式 Task → 准备池 (总计: {}): type={}, textLen={}, sources={}",
                count, task.getClass().getSimpleName(), text.length(), sources);
    }

    // ==========================================
    // 工具自持初始化
    // ==========================================

    private void initToolbox() {
        log.info("[ActionLoop] 🔧 开始自持工具箱初始化...");

        // 获取旧 LivingLoop 引用（仅用于兼容仍需要 LivingLoop 参数的旧工具）
        com.cna.agent.LivingLoop legacy = Main.legacyLoop;

        // 核心通讯工具
        registerToolInternal(new SendChatMessage());
        registerToolInternal(new GetChatHistory());
        registerToolInternal(new SendFileToChat());

        // 调度工具
        registerToolInternal(new UpdateScheduled());
        registerToolInternal(new GetScheduled());

        // 网页工具
        registerToolInternal(new WebSearch());
        registerToolInternal(new ReadWebPage());

        // 文件系统工具
        registerToolInternal(new WriteFile());
        registerToolInternal(new ReadFile());
        registerToolInternal(new CdWorkspace());

        // 认知/记忆工具
        registerToolInternal(new GetMoreCurrentMemorys());
        registerToolInternal(new QueryDeepMemory());
        registerToolInternal(new ReflectiveCompactionTool());

        // 控制台工具
        registerToolInternal(new SendConsoleMessage());

        // 管理工具（部分需要 LivingLoop 引用，使用 legacy 兼容）
        registerToolInternal(new CreatePendingChatTask(legacy));
        registerToolInternal(new UpdateWebUI());
        registerToolInternal(new ToolUsageReader());
        registerToolInternal(new FinishTask());
        registerToolInternal(new GetNowTime());
        registerToolInternal(new SetSleepTimeTool());
        registerToolInternal(new GetSleepTimeTool());
        registerToolInternal(new GetTaskQueueTool(legacy));
        registerToolInternal(new AdjustTaskPriorityTool(legacy));
        registerToolInternal(new DelegateComputerTaskTool());
        registerToolInternal(new ManageToolGroups(this.toolbox));
        registerToolInternal(new ManageMessageKeywords());
        registerToolInternal(new CreateSelfTask(legacy));
        registerToolInternal(new CancelTask(legacy));
        registerToolInternal(new McpBridge());

        log.info("[ActionLoop] 🔧 自持工具箱初始化完成: {} 个工具已挂载 ({} 个使用 legacy LivingLoop)",
                toolbox.size(), 6);

        // 打印所有工具名（调试用）
        log.info("[ActionLoop] 📋 工具箱工具列表:");
        List<String> names = new ArrayList<>(toolbox.keySet());
        names.sort(String::compareTo);
        for (String name : names) {
            log.info("[ActionLoop]   - {}", name);
        }
    }

    private void registerToolInternal(DefaultAgentToolUnit tool) {
        if (tool != null && tool.getName() != null) {
            toolbox.put(tool.getName(), tool);
            log.debug("[ActionLoop]   挂载工具: {}", tool.getName());
        }
    }

    // ==========================================
    // 生命周期
    // ==========================================

    /** 启动 tick 调度器 */
    public void start() {
        log.info("[ActionLoop] 🚀 启动 V4 认知循环 — tick间隔={}ms, 基础底线={}, CW衰减={}",
                CoreConfig.COGNITIVE_TICK_MS,
                CoreConfig.BASELINE_THRESHOLD,
                CoreConfig.CONTINUE_WEIGHT_DECAY);
        tickScheduler.scheduleAtFixedRate(this::onTick,
                CoreConfig.COGNITIVE_TICK_MS,
                CoreConfig.COGNITIVE_TICK_MS,
                TimeUnit.MILLISECONDS);
        log.info("[ActionLoop] ✅ V4 认知循环已启动 (tick scheduler 运行中)");
    }

    /** 停止 */
    public void stop() {
        log.info("[ActionLoop] ⏹️ 正在停止 V4 认知循环...");
        log.info("[ActionLoop] 📊 停止前统计 — Ticks: {}, Inputs: {}, Actions: {}, Tools: {}, Experiences: {}, Feelings: {}",
                tickCount.get(), inputProcessedCount.get(), actionProcessedCount.get(),
                toolExecutedCount.get(), experienceStoredCount.get(), feelingStimulatedCount.get());

        tickScheduler.shutdown();
        actionExecutor.shutdown();
        try {
            if (!tickScheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                log.warn("[ActionLoop] Tick scheduler 未能在 2s 内终止，强制关闭");
                tickScheduler.shutdownNow();
            }
            if (!actionExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("[ActionLoop] Action executor 未能在 5s 内终止，强制关闭");
                actionExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            log.warn("[ActionLoop] 停止过程中被中断");
            tickScheduler.shutdownNow();
            actionExecutor.shutdownNow();
        }
        com.cna.apcore.db.CognitiveDB.shutdown();
        log.info("[ActionLoop] ✅ V4 认知循环已完全停止");
    }

    // ==========================================
    // Tick 循环
    // ==========================================

    private void onTick() {
        int tick = tickCount.incrementAndGet();
        try {
            // ── 步骤 0: 直接处理 Main.AgentInputTasksQueue（不再依赖 LivingLoop） ──
            drainInputQueue();

            // ── 步骤 1: tickAll + 衰减/清理 ──
            preparePool.tickAll();
            int pruned = preparePool.decayAndPrune();

            if (log.isDebugEnabled()) {
                log.debug("[ActionLoop] ⏰ Tick #{} — 池大小: {}, 本轮清理: {}, 正在处理: {}",
                        tick, preparePool.size(), pruned, isProcessing.get());
            } else if (tick % 10 == 0) {
                // 每 10 个 tick 打印一次概况
                log.info("[ActionLoop] 📊 Tick #{} 概况 — 池: {}, 已处理Input: {}, Actions: {}, Tools: {}, Exp: {}, Feel: {}",
                        tick, preparePool.size(), inputProcessedCount.get(), actionProcessedCount.get(),
                        toolExecutedCount.get(), experienceStoredCount.get(), feelingStimulatedCount.get());
            }

            // ── 步骤 2: 选择并处理 ──
            if (isProcessing.compareAndSet(false, true)) {
                CognitiveAction action = preparePool.selectAndConvert(
                        this::getEmbedding,
                        experiencesDB,
                        feelingsDB,
                        FeelingHypergraphManager.getInstance()
                );
                if (action != null) {
                    log.info("[ActionLoop] 🎯 Tick #{} — 选中 CognitiveAction: CF={:.3f}, Scale={}, Accident={:.3f}, CW={:.3f}",
                            tick, action.getCognitiveFamiliarity(), action.getScale(),
                            action.getAccidentDegree(), action.getContinueWeight());
                    actionExecutor.submit(() -> {
                        try {
                            processAction(action);
                        } finally {
                            isProcessing.set(false);
                        }
                    });
                } else {
                    isProcessing.set(false);
                    if (log.isDebugEnabled()) {
                        log.debug("[ActionLoop] ⏸️ Tick #{} — 无单元超过基础底线 (threshold={}), 跳过",
                                tick, CoreConfig.BASELINE_THRESHOLD);
                    }
                }
            } else {
                log.debug("[ActionLoop] ⏳ Tick #{} — 上一轮处理尚未完成，跳过本轮选择", tick);
            }
        } catch (Exception e) {
            log.error("[ActionLoop] ❌ onTick #{} 异常", tick, e);
            isProcessing.set(false);
        }
    }

    /**
     * 从 Main.AgentInputTasksQueue 直接拉取输入并转换为 CognitivePrepareUnit。
     * 这是 V4 取代 LivingLoop handleCognitiveCycle 的核心方法。
     */
    private void drainInputQueue() {
        List<DefaultAgentInputUnit> batch = new ArrayList<>();
        Main.AgentInputTasksQueue.drainTo(batch);

        if (batch.isEmpty()) return;

        int batchSize = batch.size();
        log.info("[ActionLoop] 📥 从 AgentInputTasksQueue 捕获 {} 条感知输入", batchSize);

        // 按来源分组
        Map<String, List<DefaultAgentInputUnit>> grouped = new LinkedHashMap<>();
        for (DefaultAgentInputUnit input : batch) {
            String source = extractSource(input);
            grouped.computeIfAbsent(source, k -> new ArrayList<>()).add(input);
        }

        if (log.isDebugEnabled()) {
            log.debug("[ActionLoop] 输入分组: {} 个来源", grouped.size());
            for (Map.Entry<String, List<DefaultAgentInputUnit>> e : grouped.entrySet()) {
                log.debug("[ActionLoop]   来源 '{}': {} 条消息", e.getKey(), e.getValue().size());
            }
        }

        // 按来源聚合 → CognitivePrepareUnit
        for (Map.Entry<String, List<DefaultAgentInputUnit>> entry : grouped.entrySet()) {
            String source = entry.getKey();
            List<DefaultAgentInputUnit> inputs = entry.getValue();

            // 聚合文本
            StringBuilder combinedText = new StringBuilder();
            int atCount = 0;
            for (DefaultAgentInputUnit input : inputs) {
                String text = extractText(input);
                if (text != null && !text.isBlank()) {
                    if (combinedText.length() > 0) combinedText.append("\n---\n");
                    combinedText.append(text);
                }
                // 检查 @提及
                if (text != null && (text.contains("@") || text.contains("atTargets"))) {
                    atCount++;
                }
            }

            String finalText = combinedText.toString();
            if (finalText.isBlank()) {
                log.debug("[ActionLoop] 来源 '{}' 聚合后文本为空，跳过", source);
                continue;
            }

            // 计算 SE = 基础值 × (1 + log(消息数)) × @提及加成
            double baseSE = 0.5;
            double volumeFactor = 1.0 + Math.log1p(inputs.size()) * 0.5;
            double se = baseSE * volumeFactor;
            if (atCount > 0) {
                se *= 1.0 + Math.min(0.5, atCount * 0.1);
            }

            CognitivePrepareUnit cpu = CognitivePrepareUnit.create(finalText, List.of(source), se);
            preparePool.push(cpu);
            int count = inputProcessedCount.incrementAndGet();

            log.info("[ActionLoop] 📨 新准备单元入池 (总计: {}): source={}, messages={}, textLen={}, SE={}, atCount={}",
                    count, source, inputs.size(), finalText.length(), String.format("%.3f", se), atCount);
        }
    }

    // ==========================================
    // LLM 交互核心
    // ==========================================

    /**
     * 处理一个 CognitiveAction 的完整 LLM 交互周期。
     *
     * 流程：
     * 1. 构建 prompt + 工具定义 → 调用 LLM（原生 function calling）
     * 2. 从 content 中尝试解析 JSON 元数据（thoughts, feelings, scoring, boosts）
     *    解析失败则把 content 整体当 thoughts
     * 3. 从 result.getToolCalls() 执行原生工具调用
     * 4. 存储经验、更新感觉、应用打分、处理新单元和 boosts
     */
    private void processAction(CognitiveAction action) {
        int actionNum = actionProcessedCount.incrementAndGet();
        log.info("[ActionLoop] 🔄 开始处理 CognitiveAction #{}: {}", actionNum, action.buildSummary());

        try {
            // 1. 构建 prompt + 工具（由 LLManager 负责渲染模板和调用 LLM）
            String systemPrompt = LLManager.loadPromptTemplate("prompts/V4_ACTION_SYSTEM_PROMPT.md");
            String userTemplate = LLManager.loadPromptTemplate("prompts/V4_ACTION_LOOP_PROMPT.ftl");
            Map<String, Object> promptData = buildActionPromptData(action);
            ArrayNode toolsArray = buildToolsArray();

            // 2. 调用 LLM（通过 LLManager 无状态模式）
            log.info("[ActionLoop] 🤖 调用大模型 (tools={})...", toolsArray.size());
            long llmStartMs = System.currentTimeMillis();
            com.cna.llm.CallResult result = LLManager.executeStateless(
                    brainLLM, systemPrompt, userTemplate, promptData, toolsArray);
            long llmElapsedMs = System.currentTimeMillis() - llmStartMs;

            if (result == null) {
                log.warn("[ActionLoop] ⚠️ LLM 返回空响应 (耗时 {}ms)，跳过处理", llmElapsedMs);
                return;
            }

            String llmContent = result.getContent() != null ? result.getContent() : "";
            log.info("[ActionLoop] 📡 LLM 响应 (耗时 {}ms): isToolCall={}, contentLen={}, toolCalls={}",
                    llmElapsedMs, result.isToolCall(), llmContent.length(),
                    result.getToolCalls() != null ? result.getToolCalls().size() : 0);

            if (log.isDebugEnabled()) {
                log.debug("[ActionLoop] LLM content 预览: {}",
                        llmContent.length() > 200 ? llmContent.substring(0, 200) + "..." : llmContent);
            }

            // 3. 尝试从 content 解析 JSON 元数据
            JsonNode meta = tryParseMeta(llmContent);
            String thoughts;
            List<String> toolResults = new ArrayList<>();

            if (meta != null) {
                thoughts = meta.path("thoughts").asText("");
                log.info("[ActionLoop] ✅ 成功解析 LLM JSON 元数据: thoughtsLen={}, hasToolCalls={}, hasScoring={}, hasBoosts={}, hasNewUnit={}",
                        thoughts.length(),
                        meta.has("tool_calls") && meta.get("tool_calls").isArray(),
                        meta.has("experience_scoring") && meta.get("experience_scoring").isArray(),
                        meta.has("continue_weight_boosts") && meta.get("continue_weight_boosts").isArray(),
                        meta.has("new_prepare_unit") && !meta.get("new_prepare_unit").isNull());
                if (log.isDebugEnabled()) {
                    log.debug("[ActionLoop] thoughts: {}",
                            thoughts.length() > 120 ? thoughts.substring(0, 120) + "..." : thoughts);
                }
            } else {
                // content 不是 JSON，整体当 thoughts
                thoughts = llmContent;
                log.info("[ActionLoop] LLM content 非 JSON 格式，整体视为 thoughts ({} chars)", thoughts.length());
            }

            // 4. 执行原生 tool_calls（OpenAI function calling 格式）
            int toolCallCount = 0;
            if (result.isToolCall() && result.getToolCalls() != null && result.getToolCalls().isArray()) {
                toolCallCount = result.getToolCalls().size();
                log.info("[ActionLoop] 🔨 开始执行 {} 个工具调用", toolCallCount);

                for (JsonNode tc : result.getToolCalls()) {
                    String fnName = tc.path("function").path("name").asText();
                    String fnArgs = tc.path("function").path("arguments").asText();
                    if (fnName.isEmpty()) {
                        log.warn("[ActionLoop] 工具名为空，跳过");
                        continue;
                    }

                    log.info("[ActionLoop]   ▶ 执行: {} (argsLen={})", fnName, fnArgs.length());
                    if (log.isDebugEnabled()) {
                        log.debug("[ActionLoop]     args: {}", fnArgs.length() > 150 ? fnArgs.substring(0, 150) + "..." : fnArgs);
                    }

                    DefaultAgentToolUnit tool = toolbox.get(fnName);
                    if (tool != null) {
                        try {
                            long toolStartMs = System.currentTimeMillis();
                            JsonNode argsNode = mapper.readTree(fnArgs);
                            String execResult = tool.execute(argsNode);
                            long toolElapsedMs = System.currentTimeMillis() - toolStartMs;

                            toolResults.add("[" + fnName + "]: " + execResult);
                            toolExecutedCount.incrementAndGet();
                            log.info("[ActionLoop]   ✅ 工具 [{}] 完成 ({}ms): {}",
                                    fnName, toolElapsedMs,
                                    execResult.length() > 80 ? execResult.substring(0, 80) + "..." : execResult);
                        } catch (Exception e) {
                            String err = "[" + fnName + "] 异常: " + e.getMessage();
                            toolResults.add(err);
                            log.error("[ActionLoop]   ❌ 工具 [{}] 执行异常: {}", fnName, e.getMessage(), e);
                        }
                    } else {
                        String msg = "工具 \"" + fnName + "\" 未注册";
                        toolResults.add(msg);
                        log.warn("[ActionLoop]   ⚠️ 工具不存在: {} (可用: {})",
                                fnName, toolbox.keySet().stream()
                                        .filter(n -> n.toLowerCase().contains(fnName.substring(0, Math.min(3, fnName.length())).toLowerCase()))
                                        .limit(5).toList());
                    }
                }
            } else if (meta != null) {
                // fallback：如果原生 tool_calls 为空，尝试从 JSON meta 的 tool_calls 字段执行
                JsonNode metaToolCalls = meta.get("tool_calls");
                if (metaToolCalls != null && metaToolCalls.isArray()) {
                    int metaCallCount = metaToolCalls.size();
                    log.info("[ActionLoop] 🔨 从 JSON meta 执行 {} 个工具调用 (fallback)", metaCallCount);
                    for (JsonNode tc : metaToolCalls) {
                        String r = executeToolCall(tc);
                        toolResults.add(r);
                    }
                }
            }

            // 5. 处理刺激的感觉维度
            List<Integer> stimulatedDimIds = meta != null
                    ? processStimulatedFeelings(meta)
                    : new ArrayList<>();

            // 6. 存储经验
            int expId = storeExperience(action, thoughts, stimulatedDimIds, toolResults,
                    meta != null && !meta.path("new_prepare_unit").isNull()
                            ? meta.path("new_prepare_unit").path("text").asText() : null);

            // 7. 应用经验打分
            if (meta != null) {
                applyExperienceScoring(meta);
            }

            // 8. 处理新的准备单元
            if (meta != null) {
                processNewPrepareUnit(meta, action);
            }

            // 9. 应用 ContinueWeight boosts
            if (meta != null) {
                applyBoosts(meta);
            }

            // ── 通知控制台监听器 ──
            if (!consoleListeners.isEmpty()) {
                ActionNotification notification = new ActionNotification(
                        actionNum,
                        action.buildSummary(),
                        thoughts != null ? thoughts : "",
                        toolCallCount,
                        toolResults,
                        expId,
                        stimulatedDimIds.size(),
                        llmElapsedMs,
                        preparePool.size()
                );
                for (Consumer<ActionNotification> listener : consoleListeners) {
                    try {
                        listener.accept(notification);
                    } catch (Exception e) {
                        log.warn("[ActionLoop] 控制台监听器异常: {}", e.getMessage());
                    }
                }
            }

            log.info("[ActionLoop] ✅ CognitiveAction #{} 处理完成 — 工具执行:{}/{}, 经验ID:{}, 刺激维度:{}, 池大小:{}",
                    actionNum, toolResults.size(), toolCallCount, expId, stimulatedDimIds.size(), preparePool.size());

        } catch (Exception e) {
            log.error("[ActionLoop] ❌ processAction #{} 异常", actionNum, e);
        }
    }

    // ==========================================
    // Step 1: Prompt 数据模型构建
    // ==========================================

    /**
     * 构建 FreeMarker 模板所需的数据模型。
     * 模板渲染由 {@link LLManager#render(String, Map)} 执行。
     */
    private Map<String, Object> buildActionPromptData(CognitiveAction action) {
        Map<String, Object> data = new HashMap<>();
        data.put("action_text", action.getActionText());
        // 显式传递来源标识，确保 LLM 知道消息来自哪个平台/会话
        data.put("source_ids", action.getSourceUnit().getSourceIds());
        data.put("cognitive_familiarity", action.getCognitiveFamiliarity());
        data.put("scale", action.getScale());
        data.put("accident_degree", action.getAccidentDegree());
        data.put("action_pressure", action.getActionPressure());
        data.put("continue_weight", action.getContinueWeight());
        data.put("ue_concepts", action.getUEConcepts());
        data.put("ue_dim_ids", action.getUEDimIds());
        data.put("now_time", Utils.getNowPrecise());

        // 先验经验
        StringBuilder predictsText = new StringBuilder();
        if (!action.getActionPredicts().isEmpty()) {
            for (int i = 0; i < action.getActionPredicts().size(); i++) {
                var p = action.getActionPredicts().get(i);
                predictsText.append(String.format("  [经验%d] (ID=%d, 相似度=%.3f, 有用度=%.1f): %s\n",
                        i + 1, p.getExperienceId(), p.getSimilarity(),
                        p.getHelpfulDegree(), p.getExpText()));
            }
        }
        data.put("action_predicts_text", predictsText.toString());

        // 准备池概况
        data.put("pool_summary", preparePool.buildPoolSummary());

        return data;
    }

    private ArrayNode buildToolsArray() {
        ArrayNode tools = mapper.createArrayNode();
        int autoLoadCount = 0;
        int skippedCount = 0;
        for (DefaultAgentToolUnit tool : toolbox.values()) {
            if (tool.isAutoLoad()) {
                tools.add(tool.getToolDefinition());
                autoLoadCount++;
            } else {
                skippedCount++;
            }
        }
        log.debug("[ActionLoop] 构建工具数组: {} autoLoad + {} skipped = {} total",
                autoLoadCount, skippedCount, autoLoadCount + skippedCount);
        return tools;
    }

    // ==========================================
    // LLM 响应解析
    // ==========================================

    /**
     * 尝试从 LLM 的 content 中解析 JSON 元数据。
     * 成功返回 JsonNode，失败返回 null。
     */
    private JsonNode tryParseMeta(String content) {
        if (content == null || content.isBlank()) return null;
        String trimmed = content.trim();
        try {
            // 直接解析
            return mapper.readTree(trimmed);
        } catch (Exception e1) {
            // 尝试提取 {...} 或 [...]
            try {
                int start = trimmed.indexOf('{');
                int end = trimmed.lastIndexOf('}');
                if (start >= 0 && end > start) {
                    String extracted = trimmed.substring(start, end + 1);
                    log.debug("[ActionLoop] 从 LLM content 提取 JSON (offset={}..{}): {} chars",
                            start, end, extracted.length());
                    return mapper.readTree(extracted);
                }
            } catch (Exception e2) {
                log.debug("[ActionLoop] JSON 提取也失败: {}", e2.getMessage());
            }
        }
        return null;
    }

    // ==========================================
    // Step 7: 存储经验
    // ==========================================

    private int storeExperience(CognitiveAction action, String thoughts,
                                 List<Integer> stimulatedDimIds, List<String> toolResults,
                                 String newPrepareText) {
        List<String> expTexts = new ArrayList<>();

        // LLM 的想法
        if (thoughts != null && !thoughts.isBlank()) {
            expTexts.add("想法: " + thoughts);
        }

        // 原始 ActionText
        expTexts.add("触发: " + action.getActionText());

        // 工具执行结果
        for (String tr : toolResults) {
            if (tr != null && !tr.isBlank()) {
                expTexts.add("结果: " + tr);
            }
        }

        // 新预备认识
        if (newPrepareText != null && !newPrepareText.isBlank()) {
            expTexts.add("未完成: " + newPrepareText);
        }

        if (expTexts.isEmpty()) {
            log.debug("[ActionLoop] 无经验文本可存储，跳过");
            return -1;
        }

        // 合并所有感觉维度 ID
        List<Integer> allDimIds = new ArrayList<>();
        allDimIds.addAll(stimulatedDimIds);
        allDimIds.addAll(action.getUEDimIds());

        // 计算拼接文本的 embedding
        String combinedText = String.join(" ", expTexts);
        double[] emb = getEmbedding(combinedText);

        int expId = experiencesDB.insertExperience(allDimIds, expTexts, emb);
        if (expId > 0) {
            experienceStoredCount.incrementAndGet();
        }
        log.info("[ActionLoop] 💾 经验已存储: id={}, texts={}, feelings={} (总计: {})",
                expId, expTexts.size(), allDimIds.size(), experienceStoredCount.get());
        return expId;
    }

    // ==========================================
    // Step 5: 工具执行
    // ==========================================

    private String executeToolCall(JsonNode toolCall) {
        String functionName = toolCall.path("function").path("name").asText();
        String argumentsStr = toolCall.path("function").path("arguments").asText();

        if (functionName.isEmpty()) {
            return "工具名为空，跳过";
        }

        DefaultAgentToolUnit tool = toolbox.get(functionName);
        if (tool == null) {
            log.warn("[ActionLoop] 工具不存在 (fallback): {}", functionName);
            return "工具 \"" + functionName + "\" 不存在";
        }

        try {
            JsonNode argsNode = mapper.readTree(argumentsStr);
            String result = tool.execute(argsNode);
            toolExecutedCount.incrementAndGet();
            log.info("[ActionLoop] 工具 [{}] 执行完毕 (fallback): {}", functionName,
                    result != null && result.length() > 80 ? result.substring(0, 80) + "..." : result);
            return "[" + functionName + "]: " + result;
        } catch (Exception e) {
            log.error("[ActionLoop] 工具 [{}] 执行异常 (fallback)", functionName, e);
            return "[" + functionName + "] 异常: " + e.getMessage();
        }
    }

    // ==========================================
    // Step 6: 处理刺激的感觉维度
    // ==========================================

    /**
     * 处理 LLM 返回的 stimulated_feelings。
     * 对每个感觉维度：先 dedup（embedding），再插入或增加激活次数。
     *
     * @return 所有关联的感觉维度 ID 列表
     */
    private List<Integer> processStimulatedFeelings(JsonNode responseJson) {
        List<Integer> dimIds = new ArrayList<>();
        JsonNode feelings = responseJson.get("stimulated_feelings");
        if (feelings == null || !feelings.isArray()) {
            log.debug("[ActionLoop] 无 stimulated_feelings");
            return dimIds;
        }

        int count = feelings.size();
        log.info("[ActionLoop] 🎯 处理 {} 个刺激感觉维度", count);

        for (JsonNode f : feelings) {
            String concept = f.path("concept").asText();
            String embText = f.path("embedding_text").asText(concept);

            if (concept.isBlank()) continue;

            double[] emb = getEmbedding(embText);
            int id = feelingsDB.insertFeeling(concept, emb);
            if (id > 0) {
                feelingsDB.incrementActivation(id);
                dimIds.add(id);
                feelingStimulatedCount.incrementAndGet();
                log.debug("[ActionLoop]   刺激感觉: '{}' id={}", concept, id);
            }
        }

        if (!dimIds.isEmpty()) {
            log.info("[ActionLoop] ✅ 本轮刺激 {} 个感觉维度 (总计: {})",
                    dimIds.size(), feelingStimulatedCount.get());
        }
        return dimIds;
    }

    // ==========================================
    // Step 8: 应用经验打分
    // ==========================================

    private void applyExperienceScoring(JsonNode responseJson) {
        JsonNode scorings = responseJson.get("experience_scoring");
        if (scorings == null || !scorings.isArray()) {
            log.debug("[ActionLoop] 无 experience_scoring");
            return;
        }

        int count = scorings.size();
        log.info("[ActionLoop] 📝 应用 {} 条经验打分", count);

        for (JsonNode s : scorings) {
            int expId = s.path("experience_id").asInt(-1);
            double score = s.path("score").asDouble(0.0);

            if (expId < 0) continue;

            // 限制 LLM 只用 1/0/-1
            double clamped = score > 0 ? 1.0 : (score < 0 ? -1.0 : 0.0);

            // 更新经验 HelpfulDegree
            experiencesDB.updateHelpfulDegree(expId, clamped);

            // 传播准确度到关联的感觉维度
            ExperiencesDB.ExperienceEntry exp = experiencesDB.getById(expId);
            if (exp != null && !exp.feelingDimIds.isEmpty()) {
                double delta = clamped / exp.feelingDimIds.size();
                for (int dimId : exp.feelingDimIds) {
                    feelingsDB.propagateAccuracy(dimId, delta);
                }
                log.info("[ActionLoop]   经验 id={} 打分={}, 准确度传播到 {} 个感觉维度 (delta={:.4f})",
                        expId, clamped, exp.feelingDimIds.size(), delta);
            }
        }
    }

    // ==========================================
    // Step 9: 处理新的准备单元
    // ==========================================

    private void processNewPrepareUnit(JsonNode responseJson, CognitiveAction currentAction) {
        JsonNode newUnit = responseJson.get("new_prepare_unit");
        if (newUnit == null || newUnit.isNull()) {
            log.debug("[ActionLoop] 无 new_prepare_unit");
            return;
        }

        String text = newUnit.path("text").asText();
        if (text.isBlank()) return;

        List<String> sourceIds = new ArrayList<>();
        JsonNode sources = newUnit.path("sourceIds");
        if (sources.isArray()) {
            for (JsonNode s : sources) {
                sourceIds.add(s.asText());
            }
        }
        // 如果没有指定来源，继承当前 action 的来源
        if (sourceIds.isEmpty() && currentAction.getSourceUnit().getSourceIds() != null) {
            sourceIds = currentAction.getSourceUnit().getSourceIds();
        }

        CognitivePrepareUnit newCPU = CognitivePrepareUnit.create(text, sourceIds);
        // 初始 SE 可以继承一部分，表示"上一轮未完成的事有点重要"
        newCPU.setSE(currentAction.getSourceUnit().getStimulateEnergy() * 0.7);

        preparePool.push(newCPU);
        log.info("[ActionLoop] 📝 LLM 创建新准备单元: {} (继承SE={:.3f})",
                newCPU, newCPU.getStimulateEnergy());
    }

    // ==========================================
    // Step 10: 应用 ContinueWeight boosts
    // ==========================================

    private void applyBoosts(JsonNode responseJson) {
        JsonNode boosts = responseJson.get("continue_weight_boosts");
        if (boosts == null || !boosts.isArray()) {
            log.debug("[ActionLoop] 无 continue_weight_boosts");
            return;
        }

        int count = boosts.size();
        log.info("[ActionLoop] 🔼 应用 {} 个 ContinueWeight boost", count);

        for (JsonNode b : boosts) {
            String uuidStr = b.path("unit_uuid").asText();
            double boost = b.path("boost").asDouble(0.5);

            if (uuidStr.isBlank()) continue;

            try {
                UUID uuid = UUID.fromString(uuidStr);
                boolean ok = preparePool.boostContinueWeight(uuid, boost);
                if (ok) {
                    log.info("[ActionLoop]   ✅ boost {} CW +{}", uuidStr.substring(0, 8), boost);
                }
            } catch (IllegalArgumentException e) {
                log.warn("[ActionLoop]   无效的 UUID: {}", uuidStr);
            }
        }
    }

    // ==========================================
    // 辅助方法
    // ==========================================

    private double[] getEmbedding(String text) {
        return LLManager.getTextVector(text, embLLM);
    }

    /** 获取准备池（供外部查看） */
    public CognitivePreparePool getPool() {
        return preparePool;
    }

    /** 获取工具箱大小 */
    public int getToolboxSize() {
        return toolbox.size();
    }

    /** 获取工具箱所有工具名（调试用） */
    public Set<String> getToolboxNames() {
        return new TreeSet<>(toolbox.keySet());
    }

    /** 获取运行统计（调试用） */
    public Map<String, Integer> getStats() {
        Map<String, Integer> stats = new LinkedHashMap<>();
        stats.put("ticks", tickCount.get());
        stats.put("inputsProcessed", inputProcessedCount.get());
        stats.put("actionsProcessed", actionProcessedCount.get());
        stats.put("toolsExecuted", toolExecutedCount.get());
        stats.put("experiencesStored", experienceStoredCount.get());
        stats.put("feelingsStimulated", feelingStimulatedCount.get());
        stats.put("poolSize", preparePool.size());
        stats.put("toolboxSize", toolbox.size());
        return stats;
    }

    // ==========================================
    // 输入解析工具方法
    // ==========================================

    /** 从 input 中提取来源标识符 */
    private String extractSource(DefaultAgentInputUnit input) {
        if (input instanceof com.cna.agent.AgentInput.ChatMessageInput chatInput) {
            String source = chatInput.getSource();
            if (source != null && !source.isBlank()) return source;
            String sourceName = chatInput.getSource_name();
            if (sourceName != null && !sourceName.isBlank()) return "chat:" + sourceName;
            return "chat:unknown";
        }
        if (input instanceof com.cna.agent.AgentInput.WebEventInput) {
            return "web_event";
        }
        return "system:" + input.getClass().getSimpleName();
    }

    /** 从 input 中提取文本内容（优先取带来源/发送者上下文的完整文本） */
    private String extractText(DefaultAgentInputUnit input) {
        if (input instanceof com.cna.agent.AgentInput.ChatMessageInput chatInput) {
            // getInputText() 包含来源、发送者、场景等完整上下文，LLM 需要这些信息来正确回复
            String text = chatInput.getInputText();
            if (text != null && !text.isBlank()) return text;
            return chatInput.getContent();
        }
        if (input instanceof com.cna.agent.AgentInput.WebEventInput webInput) {
            return webInput.toString();
        }
        return input.toString();
    }

    /** 计算刺激能量 SE */
    private double computeStimulateEnergy(DefaultAgentInputUnit input, String source) {
        double se = 0.5; // 基础值

        // ChatMessage 有特殊加成
        if (input instanceof com.cna.agent.AgentInput.ChatMessageInput chatInput) {
            String text = chatInput.getContent();
            if (text != null) {
                // @提及加成
                if (text.contains("@")) {
                    se *= 1.2;
                }
                // 长度因子
                if (text.length() > 200) {
                    se *= 1.1;
                }
                // @提及次数加成
                int atCount = 0;
                for (int i = 0; i < text.length() - 1; i++) {
                    if (text.charAt(i) == '@') atCount++;
                }
                if (atCount > 2) {
                    se *= 1.0 + Math.min(0.5, (atCount - 2) * 0.1);
                }
            }
        }
        return se;
    }
}
