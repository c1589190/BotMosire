package com.cna.apcore;

import com.cna.Main;
import com.cna.Utils;
import com.cna.agent.AgentInput.DefaultAgentInputUnit;
import com.cna.agent.AgentInputHandlers.DefaultAgentInputHandlerUnit;
import com.cna.agent.AgentTask.DefaultAgentTaskUnit;
import com.cna.agent.AgentTool.*;
import com.cna.agent.AgentTool.io.*;
import com.cna.agent.AgentTool.FinishAction;
import com.cna.agent.AgentTasksHandlers.DefaultAgentTaskHandler;
import com.cna.agent.FeelingResonanceAnalyzer;
import com.cna.agent.code.DelegateComputerTaskTool;
import com.cna.apcore.config.CoreConfig;
import com.cna.apcore.db.CognitiveDB;
import com.cna.apcore.db.ExperiencesDB;
import com.cna.apcore.db.FeelingsDB;
import com.cna.apcore.action.ChatMessageActionDeveloper;
import com.cna.apcore.action.TickActionManager;
import com.cna.apcore.demand.DemandManager;
import com.cna.apcore.demand.DemandState;
import com.cna.apcore.attention.AttentionManager;
import com.cna.apcore.attention.FatigueManager;
import com.cna.apcore.feeling.FeelingsManager;
import com.cna.apcore.model.ActionPredict;
import com.cna.apcore.model.CognitiveAction;
import com.cna.apcore.model.CognitivePrepareUnit;
import com.cna.apcore.pool.CognitivePreparePool;
import com.cna.config.ConfigsManager;
import com.cna.db.FeelingDimensionManager;
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
    private final AtomicInteger toolExecutedCount = new AtomicInteger(0);
    private final AtomicInteger experienceStoredCount = new AtomicInteger(0);
    private final AtomicInteger feelingStimulatedCount = new AtomicInteger(0);

    // ★ Prompt 大小保护：防止 action_text/pool_summary/action_predicts_text
    //   组合后超过 API 上下文窗口导致 502
    /** action_text 最大字符数（超长聊天历史截断），约 3000 tokens */
    private static final int MAX_ACTION_TEXT_CHARS = 8000;
    /** pool_summary 最大单元数（防止准备池摘要膨胀） */
    private static final int MAX_POOL_SUMMARY_UNITS = 20;
    /** action_predicts_text 最大经验条数 */
    private static final int MAX_PREDICT_EXPERIENCES = 15;

    // ★ 感觉维度管理器（封装互斥检测、刺激处理、打分传播、谐振分析、违和积累）
    private final FeelingsManager feelingsManager;

    // ★ Chat 消息聚合器（按 source 分桶累积，攒够/等够再 flush 入池）
    private final ChatMessageActionDeveloper chatDeveloper;

    // ★ 注意力管理器（内源能量引擎，让自生成任务获得驱动力）
    private final AttentionManager attentionManager;

    // ★ 语义疲劳管理器（让"刚处理过类似内容"的任务自然被压制）
    private final FatigueManager fatigueManager;

    // ★ TickAction 管理器（统一调度桌面巡视、认知自检等周期性检查）
    private final TickActionManager tickActionManager;

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

        // ★ 感觉维度管理器初始化
        this.feelingsManager = new FeelingsManager(feelingsDB, experiencesDB, this::getEmbedding);

        // ★ Chat 消息聚合器初始化
        this.chatDeveloper = new ChatMessageActionDeveloper();

        // ★ 注意力管理器初始化
        this.attentionManager = new AttentionManager();

        // ★ 语义疲劳管理器初始化 + 注入到准备池
        this.fatigueManager = new FatigueManager();
        this.preparePool.setFatigueManager(fatigueManager);

        // ★ 注意力态度引擎依赖注入：让 preparePool 具备 eager UE 计算和态度管理能力
        this.preparePool.setEmbedder(this::getEmbedding);
        this.preparePool.setFdm(FeelingDimensionManager.getInstance());
        this.preparePool.setHypergraph(FeelingHypergraphManager.getInstance());
        this.preparePool.setFeelingsDB(feelingsDB);

        // ★ TickAction 管理器初始化（注册内置 tick action 并注入池引用）
        this.tickActionManager = TickActionManager.getInstance();
        this.tickActionManager.init(preparePool);

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
        log.info("[ActionLoop][MosireAPI] 📥 直接推送 Input → 准备池 (总计: " + count + "): source=" + source + ", textLen=" + text.length() + ", SE=" + String.format("%.3f", se));
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
        registerToolInternal(new ReadDocument());
        registerToolInternal(new CdWorkspace());
        registerToolInternal(new DownloadFile());

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
        registerToolInternal(new FinishAction());
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

        // 初始化 LLManager 全局上下文缓存（用 V4 action system prompt 替代默认的 CORE.md）
        // ★ 启用 V4 模式：跳过旧架构字段（tools_guide/current_thoughts/current_memories/curiosity_context/pending_tasks_summary）的注入
        LLManager.setV4Mode(true);
        String systemPrompt = LLManager.loadPromptTemplate("prompts/V4_ACTION_SYSTEM_PROMPT.md");
        LLManager.initGlobalCache(systemPrompt != null ? systemPrompt : "");

        tickScheduler.scheduleAtFixedRate(this::onTick,
                CoreConfig.COGNITIVE_TICK_MS,
                CoreConfig.COGNITIVE_TICK_MS,
                TimeUnit.MILLISECONDS);
        log.info("[ActionLoop] ✅ V4 认知循环已启动 (tick scheduler 运行中)");
    }

    /** 停止 */
    public void stop() {
        log.info("[ActionLoop] ⏹️ 正在停止 V4 认知循环...");
        log.info("[ActionLoop] 📊 停止前统计 — Ticks: {}, Inputs: {}, Tools: {}, Experiences: {}, Feelings: {}",
                tickCount.get(), inputProcessedCount.get(),
                toolExecutedCount.get(), experienceStoredCount.get(), feelingStimulatedCount.get());

        // ★ 清空 ChatMessageActionDeveloper 中未 flush 的桶
        List<CognitivePrepareUnit> remaining = chatDeveloper.flushAll();
        for (CognitivePrepareUnit unit : remaining) {
            preparePool.push(unit);
        }
        if (!remaining.isEmpty()) {
            log.info("[ActionLoop] 📦 stop 时 flush {} 个残留桶到准备池", remaining.size());
        }

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

            // ── 步骤 0.5: 系统级周期性检查（桌面巡视、认知自检等）──
            int tickUnitsPushed = tickActionManager.tick(tick, preparePool.size());
            if (tickUnitsPushed > 0) {
                inputProcessedCount.addAndGet(tickUnitsPushed);
            }

            // ── 步骤 1: tickAll + 衰减/清理 ──
            preparePool.tickAll();
            int pruned = preparePool.decayAndPrune();

            // ── 步骤 1.5: 注意力分配（内源能量注入）──
            attentionManager.tick(preparePool.getAllUnits());

            // ── 步骤 1.6: 疲劳衰减（清理过期历史）──
            fatigueManager.tick(tick);

            // ── 步骤 1.7: 注意力态度自然衰减 ──
            feelingsDB.decayAttentionAttitudes(CoreConfig.ATTENTION_ATTITUDE_DECAY);

            if (log.isDebugEnabled()) {
                log.debug("[ActionLoop] ⏰ Tick #{} — 池大小: {}, 本轮清理: {}, 正在处理: {}",
                        tick, preparePool.size(), pruned, isProcessing.get());
            } else if (tick % 10 == 0) {
                // 每 10 个 tick 打印一次概况
                log.info("[ActionLoop] 📊 Tick #{} 概况 — 池: {}, 已处理Input: {}, Tools: {}, Exp: {}, Feel: {}",
                        tick, preparePool.size(), inputProcessedCount.get(),
                        toolExecutedCount.get(), experienceStoredCount.get(), feelingStimulatedCount.get());

                // ★ 心智日志：池状态快照
                List<CognitivePrepareUnit> all = preparePool.getAllUnits();
                long exo = all.stream().filter(u -> !u.isEndogenous()).count();
                long endo = all.size() - exo;
                double avgF = all.stream().mapToDouble(CognitivePrepareUnit::getUnitFatigue)
                        .average().orElse(0.0);
                double avgSE = all.stream().mapToDouble(CognitivePrepareUnit::getStimulateEnergy)
                        .average().orElse(0.0);
                MentalStateLogger.getInstance().poolSnapshot(
                        tick, all.size(), (int) exo, (int) endo, avgF, avgSE);
            }

            // ── 步骤 2: 选择并处理 ──
            if (isProcessing.compareAndSet(false, true)) {
                CognitiveAction action = preparePool.selectAndConvert(
                        this::getEmbedding,
                        experiencesDB,
                        feelingsDB,
                        FeelingHypergraphManager.getInstance(),
                        tick
                );
                if (action != null) {
                    log.info("[ActionLoop] 🎯 Tick #" + tick + " — 选中 CognitiveAction: CF=" + String.format("%.3f", action.getCognitiveFamiliarity()) + ", Scale=" + action.getScale() + ", Accident=" + String.format("%.3f", action.getAccidentDegree()) + ", CW=" + String.format("%.3f", action.getContinueWeight()));
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
     * 从 Main.AgentInputTasksQueue 直接拉取输入，委托 ChatMessageActionDeveloper
     * 按 source 分桶聚合，满足条件后 flush 为 CognitivePrepareUnit 入池。
     */
    private void drainInputQueue() {
        List<DefaultAgentInputUnit> batch = new ArrayList<>();
        Main.AgentInputTasksQueue.drainTo(batch);

        if (batch.isEmpty()) return;

        log.info("[ActionLoop] 📥 从 AgentInputTasksQueue 捕获 {} 条感知输入", batch.size());

        // 委托 ChatMessageActionDeveloper 做分桶聚合
        List<CognitivePrepareUnit> readyUnits = chatDeveloper.accumulate(batch);

        // 已就绪的单元入池（优先合并同源已有单元）
        for (CognitivePrepareUnit unit : readyUnits) {
            String source = unit.getSourceIds().isEmpty() ? "unknown" : unit.getSourceIds().get(0);
            CognitivePrepareUnit existing = preparePool.findBySource(source);
            if (existing != null) {
                existing.appendText(unit.getText());
                existing.setSE(Math.max(existing.getStimulateEnergy(), unit.getStimulateEnergy()));
                existing.resetTick();
                existing.clearUE();
                int count = inputProcessedCount.incrementAndGet();
                log.info("[ActionLoop] 🔗 合并同源输入 (总计: " + count + "): source=" + source
                        + ", SE=" + String.format("%.3f", existing.getStimulateEnergy()));
            } else {
                preparePool.push(unit);
                // ★ 外部输入隐式驱动注意力态度
                preparePool.boostMatchedFeelings(unit, CoreConfig.ATTENTION_BOOST_EXTERNAL);
                int count = inputProcessedCount.incrementAndGet();
                log.info("[ActionLoop] 📨 新准备单元入池 (总计: " + count + "): source=" + source
                        + ", textLen=" + unit.getText().length()
                        + ", SE=" + String.format("%.3f", unit.getStimulateEnergy()));
            }
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
        log.info("[ActionLoop] 🔄 处理 CognitiveAction: summary=" + action.buildSummary()
                + ", poolSize=" + preparePool.size()
                + ", CF=" + String.format("%.3f", action.getCognitiveFamiliarity())
                + ", Scale=" + action.getScale()
                + ", Accident=" + String.format("%.3f", action.getAccidentDegree())
                + ", CW=" + String.format("%.3f", action.getContinueWeight())
                + ", UE=" + (action.getUEDimIds() != null ? action.getUEDimIds().size() : 0));

        try {
            // 1. 加载用户模板 + 感觉谐振分析，构建数据模型
            String userTemplate = LLManager.loadPromptTemplate("prompts/V4_ACTION_LOOP_PROMPT.ftl");

            // ★ 感觉谐振分析（委托 FeelingsManager）
            FeelingResonanceAnalyzer.ResonanceAnalysisResult resonance = feelingsManager.analyzeResonance(action.getActionText());
            String feelingResonanceBlock = resonance != null ? resonance.llmPromptBlock : null;

            // ★ 互斥感觉维度检测（委托 FeelingsManager）
            List<Map<String, Object>> mutualExclusions = List.of();
            double[] actionTextEmb = null;
            try {
                actionTextEmb = getEmbedding(action.getActionText());
                if (actionTextEmb != null) {
                    mutualExclusions = feelingsManager.detectMutualExclusions(actionTextEmb);
                    if (!mutualExclusions.isEmpty()) {
                        log.info("[ActionLoop] ⚔️ 互斥感觉检测: {} 个互斥候选 → {}",
                                mutualExclusions.size(),
                                mutualExclusions.stream().map(m -> m.get("concept")).limit(5).toList());

                        // ★ 心智日志：互斥感觉
                        String topConcepts = mutualExclusions.stream()
                                .limit(5).map(m -> (String) m.get("concept"))
                                .reduce((a, b) -> a + ", " + b).orElse("");
                        MentalStateLogger.getInstance().feelingMutualExclusion(
                                mutualExclusions.size(), topConcepts);
                    }
                }
            } catch (Exception e) {
                log.warn("[ActionLoop] 互斥感觉检测失败，跳过: {}", e.getMessage());
            }

            // ★ 动机分析（DemandManager — 六维认知感受计算 + 人话翻译）
            int resonanceDissonant = resonance != null ? resonance.getDissonantCount() : 0;
            int resonanceResonant = resonance != null ? resonance.getResonantCount() : 0;
            DemandState demandState = DemandManager.getInstance().compute(
                    action, mutualExclusions, resonanceDissonant, resonanceResonant, preparePool);
            String demandAnalysis = DemandManager.getInstance().renderPrompt(demandState);

            Map<String, Object> promptData = buildActionPromptData(action, feelingResonanceBlock,
                    mutualExclusions, demandAnalysis);
            ArrayNode toolsArray = buildToolsArray();

            // 2. 通过 LLManager 全局缓存执行 LLM 调用（模板渲染、缓存管理、截断、持久化全由 LLManager 负责）
            log.info("[ActionLoop] 🤖 调用大模型 (tools={})...", toolsArray.size());
            long llmStartMs = System.currentTimeMillis();
            com.cna.llm.CallResult result = LLManager.executeScene(
                    UUID.randomUUID(), userTemplate, promptData, brainLLM, toolsArray);
            long callMs = System.currentTimeMillis() - llmStartMs;

            if (result != null) {
                // ★ 本轮工具调用日志（per-round tool logging）
                int tcCount = (result.isToolCall() && result.getToolCalls() != null && result.getToolCalls().isArray())
                        ? result.getToolCalls().size() : 0;
                List<String> tcNames = new ArrayList<>();
                if (tcCount > 0) {
                    for (JsonNode tc : result.getToolCalls()) {
                        tcNames.add(tc.path("function").path("name").asText());
                    }
                    log.info("[ActionLoop] 🤖 LLM 返回 {} 个工具调用 ({}ms): {}",
                            tcCount, callMs, tcNames);
                } else {
                    log.info("[ActionLoop] 🤖 LLM 纯文本响应 ({}ms): contentLen={}",
                            callMs, result.getContent() != null ? result.getContent().length() : 0);
                }
            }
            long llmElapsedMs = System.currentTimeMillis() - llmStartMs;

            if (result == null) {
                log.warn("[ActionLoop] ⚠️ LLM 返回空响应 (耗时 {}ms)，跳过处理", llmElapsedMs);
                return;
            }

            // ★ 错误响应检测：LLM 调用失败时（API 错误/网络异常/超时等），
            //    content 会以特定错误前缀开头。此时上下文缓存可能已损坏，必须清理。
            if (result.isError()) {
                log.error("[ActionLoop] ❌ LLM 返回错误响应 (耗时 {}ms): {}", llmElapsedMs, result.getContent());
                LLManager.clearCache();
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

            // 3. 执行工具调用 — finish_action 放最后：先提取其数据，执行其他工具，最后结算
            //    ★ 元数据主路径：thoughts/feelings/scoring/boosts 从 finish_action 工具参数提取
            //    ★ fallback：仅当无原生 tool_calls 时，才解析 content JSON 作为备选
            String thoughts = "";
            List<String> toolResults = new ArrayList<>();
            int toolCallCount = 0;
            boolean hasFinishAction = false;
            JsonNode finishActionArgs = null;
            String finishActionCallId = null;
            List<Integer> resolvedDimIds = null;

            // 元数据暂存（从 finish_action 参数或 fallback JSON 提取）
            JsonNode stimulatedFeelingsNode = null;
            JsonNode experienceScoringNode = null;
            JsonNode newPrepareUnitNode = null;
            JsonNode boostNode = null;

            if (result.isToolCall() && result.getToolCalls() != null && result.getToolCalls().isArray()) {
                // —— 分类：finish_action vs 其他工具 ——
                List<JsonNode> otherToolCalls = new ArrayList<>();
                for (JsonNode tc : result.getToolCalls()) {
                    String fnName = tc.path("function").path("name").asText();
                    if ("finish_action".equals(fnName)) {
                        hasFinishAction = true;
                        finishActionCallId = tc.path("id").asText("");
                        String fnArgs = tc.path("function").path("arguments").asText();
                        try {
                            finishActionArgs = mapper.readTree(fnArgs);
                            // ★ 从 finish_action 参数中提前提取元数据（主路径）
                            thoughts = finishActionArgs.path("thoughts").asText("");
                            stimulatedFeelingsNode = finishActionArgs.path("stimulated_feelings");
                            experienceScoringNode = finishActionArgs.path("experience_scoring");
                            newPrepareUnitNode = finishActionArgs.path("new_prepare_unit");
                            boostNode = finishActionArgs.path("continue_weight_boosts");
                            int scoringCount = experienceScoringNode.isArray() ? experienceScoringNode.size() : 0;
                            log.info("[ActionLoop] 📋 检测到 finish_action — thoughts={}chars, scoring={}条, feelings={}, boosts={}, newUnit={}",
                                    thoughts.length(), scoringCount,
                                    stimulatedFeelingsNode.isArray() ? stimulatedFeelingsNode.size() : 0,
                                    boostNode.isArray() ? boostNode.size() : 0,
                                    !newPrepareUnitNode.isNull() && !newPrepareUnitNode.isMissingNode());
                        } catch (Exception e) {
                            log.warn("[ActionLoop] finish_action 参数解析失败: {}", e.getMessage());
                        }
                    } else {
                        otherToolCalls.add(tc);
                    }
                }
                toolCallCount = result.getToolCalls().size();

                // —— 执行计划日志 ——
                List<String> otherNames = otherToolCalls.stream()
                        .map(tc -> tc.path("function").path("name").asText())
                        .toList();
                log.info("[ActionLoop] 🔨 工具执行计划: finish_action={}, 其他={}个{}, 总计={}",
                        hasFinishAction, otherToolCalls.size(), otherNames, toolCallCount);

                // —— 第一步：执行所有非 finish_action 工具 ——
                for (JsonNode tc : otherToolCalls) {
                    String callId = tc.path("id").asText("");
                    String fnName = tc.path("function").path("name").asText();
                    String fnArgs = tc.path("function").path("arguments").asText();

                    log.info("[ActionLoop]   ▶ 执行: {} (argsLen={})", fnName, fnArgs.length());
                    if (log.isDebugEnabled()) {
                        log.debug("[ActionLoop]     args: {}", fnArgs.length() > 150 ? fnArgs.substring(0, 150) + "..." : fnArgs);
                    }

                    DefaultAgentToolUnit tool = toolbox.get(fnName);
                    String execResult;
                    if (tool != null) {
                        try {
                            long toolStartMs = System.currentTimeMillis();
                            JsonNode argsNode = mapper.readTree(fnArgs);
                            execResult = tool.execute(argsNode);
                            long toolElapsedMs = System.currentTimeMillis() - toolStartMs;

                            toolResults.add("[" + fnName + "]: " + execResult);
                            toolExecutedCount.incrementAndGet();
                            log.info("[ActionLoop]   ✅ 工具 [{}] 完成 ({}ms): {}",
                                    fnName, toolElapsedMs,
                                    execResult.length() > 80 ? execResult.substring(0, 80) + "..." : execResult);
                        } catch (com.fasterxml.jackson.core.JsonParseException e) {
                            // JSON 解析失败：LLM 生成的 arguments 不是合法 JSON，把原始参数带上让它能自我纠正
                            String rawArgsPreview = fnArgs.length() > 500
                                    ? fnArgs.substring(0, 500) + "...[截断]"
                                    : fnArgs;
                            execResult = "[JSON解析失败] 你传入的 arguments 不是合法 JSON，请检查格式。\n"
                                    + "错误位置: " + e.getOriginalMessage() + "\n"
                                    + "你传入的内容:\n" + rawArgsPreview;
                            toolResults.add("[" + fnName + "] JSON解析失败: " + e.getOriginalMessage());
                            log.warn("[ActionLoop]   ⚠️ 工具 [{}] JSON 解析失败: {} | rawArgs={}",
                                    fnName, e.getOriginalMessage(),
                                    fnArgs.length() > 200 ? fnArgs.substring(0, 200) + "..." : fnArgs);
                        } catch (Exception e) {
                            // 工具执行异常：参数解析成功但执行过程中出错
                            execResult = "[执行失败] 工具 " + fnName + " 执行时出错: " + e.getMessage();
                            toolResults.add("[" + fnName + "] 异常: " + execResult);
                            log.error("[ActionLoop]   ❌ 工具 [{}] 执行异常: {}", fnName, e.getMessage(), e);
                        }
                    } else {
                        execResult = "工具 \"" + fnName + "\" 未注册";
                        toolResults.add(execResult);
                        log.warn("[ActionLoop]   ⚠️ 工具不存在: {} (可用: {})",
                                fnName, toolbox.keySet().stream()
                                        .filter(n -> n.toLowerCase().contains(fnName.substring(0, Math.min(3, fnName.length())).toLowerCase()))
                                        .limit(5).toList());
                    }
                    // 工具结果压入上下文缓存
                    LLManager.feedToolResult(UUID.randomUUID(),callId, fnName, execResult);
                }

                // —— 第二步：最后执行 finish_action（如果存在）——
                if (hasFinishAction && finishActionArgs != null) {
                    log.info("[ActionLoop]   ▶ 执行: finish_action (结算本轮认知周期)");
                    DefaultAgentToolUnit finishTool = toolbox.get("finish_action");
                    if (finishTool != null) {
                        try {
                            long toolStartMs = System.currentTimeMillis();
                            String execResult = finishTool.execute(finishActionArgs);
                            long toolElapsedMs = System.currentTimeMillis() - toolStartMs;

                            toolResults.add("[finish_action]: " + execResult);
                            toolExecutedCount.incrementAndGet();
                            log.info("[ActionLoop]   ✅ finish_action 结算完成 ({}ms)", toolElapsedMs);

                            // 将 finish_action 结果也压入上下文
                            LLManager.feedToolResult(UUID.randomUUID(),finishActionCallId, "finish_action", execResult);

                            // ★ LLM 自主后续行动规划：从 next_actions 数组创建多个准备单元注入池中
                            JsonNode nextActions = finishActionArgs.path("next_actions");
                            if (nextActions.isArray()) {
                                double baseSE = action.getSourceUnit().getStimulateEnergy() * 0.7;
                                int createdCount = 0;
                                for (JsonNode na : nextActions) {
                                    String taskText = na.path("text").asText();
                                    if (taskText == null || taskText.isBlank()) continue;
                                    double priority = na.path("priority").asDouble(0.5);
                                    priority = Math.max(0.1, Math.min(1.0, priority)); // clamp [0.1, 1.0]
                                    double se = baseSE * priority;
                                    CognitivePrepareUnit nextUnit = CognitivePrepareUnit.create(
                                            taskText,
                                            action.getSourceUnit().getSourceIds(),
                                            se
                                    );
                                    // ★ 标记为内源任务：注意力系统会对其分配额外能量
                                    nextUnit.setEndogenous(true);
                                    preparePool.push(nextUnit);
                                    // ★ 内源任务隐式驱动注意力态度
                                    preparePool.boostMatchedFeelings(nextUnit,
                                            CoreConfig.ATTENTION_BOOST_ENDOGENOUS * priority);
                                    createdCount++;
                                    log.info("[ActionLoop] 🔄 后续任务 #{}: SE=" + String.format("%.3f", se)
                                            + ", priority=" + String.format("%.2f", priority)
                                            + ", text=" + (taskText.length() > 60
                                                    ? taskText.substring(0, 60) + "..." : taskText));
                                }
                                if (createdCount > 0) {
                                    log.info("[ActionLoop] 📋 LLM 通过 finish_action 规划了 " + createdCount + " 个后续任务");
                                }
                            }

                            // ★ 处理 action_feelings（委托 FeelingsManager）
                            JsonNode actionFeelings = finishActionArgs.path("action_feelings");
                            resolvedDimIds = feelingsManager.processActionFeelings(actionFeelings);
                        } catch (Exception e) {
                            String execResult = "ERROR: " + e.getMessage();
                            toolResults.add("[finish_action] 异常: " + execResult);
                            log.error("[ActionLoop]   ❌ finish_action 执行异常: {}", e.getMessage(), e);
                        }
                    }
                } else if (!hasFinishAction) {
                    // LLM 做了工具调用但没调 finish_action（如在 get_chat_history 后需要
                    // 下一轮继续处理），自动重建当前任务入池，避免任务丢失。
                    if (toolCallCount > 0) {
                        double continuedSE = action.getSourceUnit().getStimulateEnergy() * 0.9;
                        CognitivePrepareUnit continued = CognitivePrepareUnit.create(
                                action.getActionText(),
                                action.getSourceUnit().getSourceIds(),
                                continuedSE
                        );
                        continued.setEndogenous(true);
                        preparePool.push(continued);
                        // ★ 续命任务也隐式驱动注意力态度（权重减半）
                        preparePool.boostMatchedFeelings(continued,
                                CoreConfig.ATTENTION_BOOST_ENDOGENOUS * 0.5);
                        log.info("[ActionLoop] 🔄 本轮未结算但有 " + toolCallCount + " 个工具调用，自动续命任务入池: SE="
                                + String.format("%.3f", continuedSE)
                                + ", text=" + (action.getActionText().length() > 60
                                        ? action.getActionText().substring(0, 60) + "..." : action.getActionText()));
                    } else {
                        log.info("[ActionLoop] ⚠️ 本轮未调用 finish_action，认知周期无正式结算");
                    }
                }
            } else {
                // ★ fallback：无原生 tool_calls 时，尝试从 content JSON 解析元数据和工具调用
                JsonNode metaFallback = tryParseMeta(llmContent);
                if (metaFallback != null) {
                    // 从 fallback JSON 提取元数据
                    if (thoughts.isEmpty()) {
                        thoughts = metaFallback.path("thoughts").asText("");
                    }
                    if (stimulatedFeelingsNode == null || !stimulatedFeelingsNode.isArray()) {
                        stimulatedFeelingsNode = metaFallback.get("stimulated_feelings");
                    }
                    if (experienceScoringNode == null || !experienceScoringNode.isArray()) {
                        experienceScoringNode = metaFallback.get("experience_scoring");
                    }
                    if (newPrepareUnitNode == null || newPrepareUnitNode.isNull() || newPrepareUnitNode.isMissingNode()) {
                        newPrepareUnitNode = metaFallback.get("new_prepare_unit");
                    }
                    if (boostNode == null || !boostNode.isArray()) {
                        boostNode = metaFallback.get("continue_weight_boosts");
                    }

                    // 从 fallback JSON 执行工具调用
                    JsonNode metaToolCalls = metaFallback.get("tool_calls");
                    if (metaToolCalls != null && metaToolCalls.isArray()) {
                        int metaCallCount = metaToolCalls.size();
                        log.info("[ActionLoop] 🔨 从 JSON meta 执行 {} 个工具调用 (fallback)", metaCallCount);
                        for (JsonNode tc : metaToolCalls) {
                            String r = executeToolCall(tc);
                            toolResults.add(r);
                        }
                    }
                    log.info("[ActionLoop] ✅ fallback JSON 元数据解析完成: thoughts={}chars", thoughts.length());
                } else if (thoughts.isEmpty()) {
                    // 连 fallback JSON 也没有，content 整体当 thoughts
                    thoughts = llmContent;
                    log.info("[ActionLoop] LLM content 非 JSON 格式，整体视为 thoughts ({} chars)", thoughts.length());
                }
            }

            // ★ 工具结果聚合：将本轮所有工具执行结果拼接为一个准备单元注入池中，
            //    供后续认知周期参考，形成"行动→结果→反思"的闭环。
            if (!toolResults.isEmpty()) {
                StringBuilder aggregated = new StringBuilder();
                aggregated.append("【本轮工具执行汇总】\n");
                for (String tr : toolResults) {
                    aggregated.append(tr).append("\n");
                }
                CognitivePrepareUnit toolSummary = CognitivePrepareUnit.create(
                        aggregated.toString(),
                        action.getSourceUnit().getSourceIds(),
                        action.getSourceUnit().getStimulateEnergy() * 0.3
                );
                preparePool.push(toolSummary);
                // ★ 工具执行结果也隐式驱动注意力态度
                preparePool.boostMatchedFeelings(toolSummary,
                        CoreConfig.ATTENTION_BOOST_SELECTED * 0.3);
                log.info("[ActionLoop] 📦 工具执行汇总已注入准备池: " + toolResults.size() + " 条结果, SE=" + String.format("%.3f", action.getSourceUnit().getStimulateEnergy() * 0.3));
            }

            // 5. 处理刺激的感觉维度（委托 FeelingsManager）
            List<Integer> stimulatedDimIds = stimulatedFeelingsNode != null
                    ? feelingsManager.processStimulatedFeelings(stimulatedFeelingsNode)
                    : new ArrayList<>();

            // 6. 存储经验
            // ★ 优先使用 LLM 通过 action_feelings 回报的感觉维度 ID 作为富 key
            //    回退：合并 stimulatedDimIds + UE dimIds（旧逻辑）
            List<Integer> expDimIds;
            if (resolvedDimIds != null && !resolvedDimIds.isEmpty()) {
                expDimIds = resolvedDimIds;
                log.info("[ActionLoop] 💡 使用 action_feelings 富 key ({}) 存储经验", expDimIds.size());
            } else {
                expDimIds = new ArrayList<>();
                expDimIds.addAll(stimulatedDimIds);
                expDimIds.addAll(action.getUEDimIds());
                log.debug("[ActionLoop] 使用 stimulated+UE dims ({}) 存储经验 (fallback)", expDimIds.size());
            }
            String newPrepText = (newPrepareUnitNode != null && !newPrepareUnitNode.isNull() && !newPrepareUnitNode.isMissingNode())
                    ? newPrepareUnitNode.path("text").asText() : null;
            int expId = storeExperience(action, thoughts, expDimIds, toolResults, newPrepText);

            // 7. 应用经验打分（委托 FeelingsManager）
            if (experienceScoringNode != null) {
                feelingsManager.applyExperienceScoring(experienceScoringNode);
            }

            // 8. 处理新的准备单元
            if (newPrepareUnitNode != null && !newPrepareUnitNode.isNull() && !newPrepareUnitNode.isMissingNode()) {
                processNewPrepareUnit(newPrepareUnitNode, action);
            }

            // 9. 应用 ContinueWeight boosts
            if (boostNode != null) {
                applyBoosts(boostNode);
            }

            // ★ 违和感积累（委托 FeelingsManager）
            feelingsManager.accumulateDissonance(resonance, action.getActionText());

            // ★ 语义疲劳记录：将本轮 action 涉及的感觉维度写入近期历史
            fatigueManager.record(action.getSourceUnit().getUeUnits(), tickCount.get());

            // ★ 被选中执行的 action，其匹配的感觉获得最大 boost（已确认重要）
            preparePool.boostMatchedFeelings(action.getSourceUnit(), CoreConfig.ATTENTION_BOOST_SELECTED);

            // ── 通知控制台监听器 ──
            if (!consoleListeners.isEmpty()) {
                ActionNotification notification = new ActionNotification(
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

            log.info("[ActionLoop] ✅ CognitiveAction 处理完成 — 工具执行:{}/{}, 经验ID:{}, 刺激维度:{}, 池大小:{}",
                    toolResults.size(), toolCallCount, expId, stimulatedDimIds.size(), preparePool.size());

            // ★ 心智日志：动作完成
            MentalStateLogger.getInstance().actionComplete(
                    tickCount.get(), toolCallCount, toolResults.size(),
                    expId, stimulatedDimIds.size(), llmElapsedMs,
                    preparePool.size(), action.getCognitiveFamiliarity(),
                    action.getAccidentDegree(),
                    action.getUEDimIds() != null ? action.getUEDimIds().size() : 0);

        } catch (Exception e) {
            log.error("[ActionLoop] ❌ processAction 异常", e);
        }
    }

    // ==========================================
    // Step 1: Prompt 数据模型构建
    // ==========================================

    /**
     * 构建 FreeMarker 模板所需的数据模型。
     * 模板渲染由 {@link LLManager#render(String, Map)} 执行。
     *
     * ★ 内含 prompt 大小保护：对 action_text、pool_summary 等大字段做截断，
     *    防止组合 prompt 超过 API 上下文窗口导致 502 错误。
     */
    private Map<String, Object> buildActionPromptData(CognitiveAction action, String feelingResonanceBlock,
                                                       List<Map<String, Object>> mutualExclusions,
                                                       String demandAnalysis) {
        Map<String, Object> data = new HashMap<>();

        // ★ action_text 上限：防止超长聊天历史撑爆上下文窗口
        String actionText = action.getActionText();
        if (actionText != null && actionText.length() > MAX_ACTION_TEXT_CHARS) {
            actionText = actionText.substring(0, MAX_ACTION_TEXT_CHARS)
                    + "\n\n[... 后续内容已被截断，原始长度 " + actionText.length() + " 字符 ...]";
            log.warn("[ActionLoop] ⚠️ action_text 超长 ({} → {} chars)，已截断",
                    action.getActionText().length(), MAX_ACTION_TEXT_CHARS);
        }
        data.put("action_text", actionText);
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

        // ★ 选择上下文：告诉 LLM 为什么这个单元被选中，各项因子的贡献
        CognitivePrepareUnit src = action.getSourceUnit();
        data.put("selection_se", src.getStimulateEnergy());
        data.put("selection_attention", src.getAttentionEnergy());
        data.put("selection_ue", src.getUnderstandEnergy());
        data.put("selection_tick", src.getTick());
        data.put("selection_total_energy", src.getStimulateEnergy() + src.getAttentionEnergy());
        data.put("selection_is_endogenous", src.isEndogenous());

        // 生成可读的选择原因
        String selectionReason = buildSelectionReason(src, action);
        data.put("selection_reason", selectionReason);

        // ★ 动机分析（DemandManager 六维认知感受 → 人话翻译）
        if (demandAnalysis != null && !demandAnalysis.isBlank()) {
            data.put("demand_analysis", demandAnalysis);
        }

        // ★ 互斥感觉维度（与当前 action 语义相斥的已有感觉）
        if (mutualExclusions != null && !mutualExclusions.isEmpty()) {
            data.put("mutual_exclusions", mutualExclusions);
        }

        // ★ 感觉谐振分析结果（违和/一致感觉维度）
        if (feelingResonanceBlock != null && !feelingResonanceBlock.isBlank()) {
            data.put("feeling_resonance", feelingResonanceBlock);
        }

        // 先验经验（★ 截断保护：最多 MAX_PREDICT_EXPERIENCES 条）
        StringBuilder predictsText = new StringBuilder();
        List<ActionPredict> predicts = action.getActionPredicts();
        if (!predicts.isEmpty()) {
            int limit = Math.min(predicts.size(), MAX_PREDICT_EXPERIENCES);
            for (int i = 0; i < limit; i++) {
                var p = predicts.get(i);
                String expText = p.getExpText();
                if (expText != null && expText.length() > 300) {
                    expText = expText.substring(0, 300) + "...";
                }
                predictsText.append(String.format("  [经验%d] (ID=%d, 相似度=%.3f, 有用度=%.1f): %s\n",
                        i + 1, p.getExperienceId(), p.getSimilarity(),
                        p.getHelpfulDegree(), expText));
            }
            if (predicts.size() > limit) {
                predictsText.append(String.format("  ... 还有 %d 条经验未显示\n", predicts.size() - limit));
            }
        }
        data.put("action_predicts_text", predictsText.toString());

        // 准备池概况（★ 截断保护：最多显示 MAX_POOL_SUMMARY_UNITS 个单元）
        String poolSummary = preparePool.buildPoolSummary(MAX_POOL_SUMMARY_UNITS);
        data.put("pool_summary", poolSummary);

        return data;
    }

    /**
     * 为 LLM 生成可读的"为什么这个单元被选中"解释。
     */
    private String buildSelectionReason(CognitivePrepareUnit src, CognitiveAction action) {
        double se = src.getStimulateEnergy();
        double attn = src.getAttentionEnergy();
        double ue = src.getUnderstandEnergy();
        int tick = src.getTick();
        double cw = src.getContinueWeight();
        boolean endogenous = src.isEndogenous();

        StringBuilder reason = new StringBuilder();

        // 主导因子判断
        if (endogenous && attn > se) {
            reason.append("这是一个你之前规划的内源任务，经过注意力系统 ");
            reason.append(String.format("%.0f", attn / (se + attn) * 100));
            reason.append("% 的能量注入后被选中。");
        } else if (se > 1.0) {
            reason.append("外部刺激强烈（SE=" + String.format("%.2f", se) + "），来自外界的输入驱动了本轮选择。");
        } else if (tick > 10) {
            reason.append("这个单元已在池中等待 " + tick + " 轮未被处理，累积的紧迫度使其被选中。");
        } else if (ue > 3.0) {
            reason.append("该任务与你的感觉维度网络高度匹配（UE=" + String.format("%.1f", ue) + "），说明你对这个领域有丰富的认知基础。");
        } else if (cw > 1.0) {
            reason.append("你之前通过 boost 手动提升了这个单元的权重，使其优先被选中。");
        } else if (attn > 0.3) {
            reason.append("注意力系统在多个 tick 中持续关注此单元，累积了足够的能量。");
        } else {
            reason.append("综合多项选择因子后，此单元得分最高。");
        }

        // 补充认知评价
        double cf = action.getCognitiveFamiliarity();
        double accident = action.getAccidentDegree();
        if (cf > 3.0) {
            reason.append(" 你对这个任务非常熟悉（CF=" + String.format("%.1f", cf) + "），可以依赖先验经验快速处理。");
        } else if (cf < 0.5) {
            reason.append(" 这是一个相对新颖的任务（CF=" + String.format("%.2f", cf) + "），需要开放心态探索。");
        }
        if (accident > 0.3) {
            reason.append(" 意外度较高（" + String.format("%.2f", accident) + "），实际输入与预期有偏差，需要重新评估。");
        }

        return reason.toString();
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
                                 List<Integer> feelingDimIds, List<String> toolResults,
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

        // 感觉维度 ID：由调用方统一解析（action_feelings 富 key 或 stimulated+UE fallback）
        List<Integer> allDimIds = feelingDimIds != null ? feelingDimIds : new ArrayList<>();

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
        } catch (com.fasterxml.jackson.core.JsonParseException e) {
            String rawArgsPreview = argumentsStr.length() > 500
                    ? argumentsStr.substring(0, 500) + "...[截断]"
                    : argumentsStr;
            log.warn("[ActionLoop] 工具 [{}] JSON 解析失败 (fallback): {}", functionName, e.getOriginalMessage());
            return "[JSON解析失败] 你传入的 arguments 不是合法 JSON，请检查格式。\n"
                    + "错误位置: " + e.getOriginalMessage() + "\n"
                    + "你传入的内容:\n" + rawArgsPreview;
        } catch (Exception e) {
            log.error("[ActionLoop] 工具 [{}] 执行异常 (fallback)", functionName, e);
            return "[" + functionName + "] 执行失败: " + e.getMessage();
        }
    }

    // ==========================================
    // Step 9: 处理新的准备单元
    // ==========================================

    private void processNewPrepareUnit(JsonNode newUnit, CognitiveAction currentAction) {
        if (newUnit == null || newUnit.isNull() || newUnit.isMissingNode()) {
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
        // ★ 未完成事项也隐式驱动注意力态度
        preparePool.boostMatchedFeelings(newCPU, CoreConfig.ATTENTION_BOOST_ENDOGENOUS);
        log.info("[ActionLoop] 📝 LLM 创建新准备单元: " + newCPU + " (继承SE=" + String.format("%.3f", newCPU.getStimulateEnergy()) + ")");
    }

    // ==========================================
    // Step 10: 应用 ContinueWeight boosts
    // ==========================================

    private void applyBoosts(JsonNode boosts) {
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

            // ★ 尝试精确匹配 → 前缀匹配（LLM 可能从池摘要中复制了截断的 UUID）
            boolean ok = false;
            try {
                UUID uuid = UUID.fromString(uuidStr);
                ok = preparePool.boostContinueWeight(uuid, boost);
            } catch (IllegalArgumentException e) {
                // UUID 不完整（如 LLM 复制了截断的 8 位前缀），尝试前缀匹配
                ok = preparePool.boostContinueWeightByPrefix(uuidStr, boost);
            }

            if (ok) {
                log.info("[ActionLoop]   ✅ boost {} CW +{}",
                        uuidStr.length() > 16 ? uuidStr.substring(0, 8) + "..." : uuidStr, boost);
            } else {
                // 单元可能已被选中或过期，属正常时序，无需 warn
                log.debug("[ActionLoop]   boost 跳过（单元已不在池中）: {}", uuidStr);
            }
        }
    }

    // ==========================================
    // 辅助方法
    // ==========================================

    // ==========================================
    // 上下文缓存管理（委托给 LLManager 全局缓存）
    // ==========================================

    /** 清空上下文缓存（异常恢复时使用），完全委托给 LLManager */
    public void clearContextCache() {
        LLManager.clearCache();
        log.info("[ActionLoop] 上下文缓存已清空（通过 LLManager）");
    }

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
