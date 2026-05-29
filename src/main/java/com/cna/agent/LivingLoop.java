package com.cna.agent;

import com.cna.Main;
import com.cna.Utils;
import com.cna.agent.AgentInput.DefaultAgentInputUnit;
import com.cna.agent.AgentInputHandlers.DefaultAgentInputHandlerUnit;
import com.cna.agent.AgentInputHandlers.ExpectedChatMessageInputHandler;
import com.cna.agent.AgentInputHandlers.WebEventInputHandler;
import com.cna.agent.AgentTask.ChatTask;
import com.cna.agent.AgentTask.ConsoleChatTask;
import com.cna.agent.AgentTask.DefaultAgentTaskUnit;
import com.cna.agent.AgentTask.ScheduledTask;
import com.cna.agent.AgentTask.UpdateThoughtsTask;
import com.cna.agent.AgentTask.WebEventTask;
import com.cna.agent.AgentTasksHandlers.*;
import com.cna.agent.AgentTool.*;
import com.cna.agent.code.DelegateComputerTaskTool;
import com.cna.config.ConfigsManager;
import com.cna.config.ScenePromptsManager;
import com.cna.config.ToolPromptsManager;
import com.cna.db.FeelingDimensionManager;
import com.cna.db.FeelingDimensionManager.DimensionScore;
import com.cna.llm.CallResult;
import com.cna.llm.LLMAdapter;
import com.cna.llm.LLManager;
import com.cna.plugin.MosireAPI;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import com.cna.agent.AgentTool.io.*;
import org.slf4j.Logger;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class LivingLoop implements MosireAPI {
    //private static final ObjectMapper sharedMapper = new ObjectMapper();

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();

    // (移除 tickCounter_CognitiveCycle 和 scheduledTaskCounter：改用双 scheduler 直接按真实间隔触发)

    // Gatekeeper (小模型) 专用异步线程池，防止网络请求阻塞心跳总线
    private final ExecutorService gatekeeperExecutor = Executors.newSingleThreadExecutor();

    // Gatekeeper 状态锁。保证小模型一次只专注思考一批消息
    private final AtomicBoolean isGatekeeperThinking = new AtomicBoolean(false);

    // 累加器：跨线程安全的任务处理计数器，用于触发定期反思
    private final AtomicInteger processedTaskCount = new AtomicInteger(0);

    // 即将执行的任务队列，按 priority 升序排列（数值越小越先出队）
    // 同优先级时 inProgress 任务优先，确保被抢占的任务优先恢复
    private final PriorityBlockingQueue<DefaultAgentTaskUnit> pendingQueue = new PriorityBlockingQueue<>(
            1145, Comparator.comparingDouble(DefaultAgentTaskUnit::getPriority)
            .thenComparing(t -> t.isInProgress() ? 0 : 1)
    );

    // 当前正在执行的任务（消费者线程独占，至多一个）
    private DefaultAgentTaskUnit executingTask = null;

    /**
     * 任务队列快照，将执行中与待处理分离，供 LLM 感知。
     */
    public record TaskQueueSnapshot(
            DefaultAgentTaskUnit executingTask,
            List<DefaultAgentTaskUnit> pendingTasks
    ) {}

    //Input队列
    private final ConcurrentLinkedQueue<DefaultAgentTaskUnit> globalPendingRequests = new ConcurrentLinkedQueue<>();

    //private LinkedHashMap<String, ChatTask> ChatTaskPreparationPool = new LinkedHashMap<>();

    private final Map<Class<? extends DefaultAgentTaskUnit>, DefaultAgentTaskHandler> taskHandlerRegistry = new ConcurrentHashMap<>();

    private final Map<Class<? extends DefaultAgentInputUnit>, DefaultAgentInputHandlerUnit> inputHandlerRegistry = new ConcurrentHashMap<>();

    volatile DefaultAgentTaskUnit lastSolvingTask = null;
    //状态管理方法是，当一个任务结束时，这玩意也必须成为null

    /**
     * 获取待处理（未开始执行）的Task数量，供Input积压机制使用
     */
    public int getPendingTaskCount() {
        int count = 0;
        for (DefaultAgentTaskUnit t : pendingQueue) {
            if (!t.isInProgress()) {
                count++;
            }
        }
        return count;
    }

    /**
     * 获取任务队列快照，将执行中与待处理分离为两个列表。
     */
    public TaskQueueSnapshot getTaskQueueSnapshot() {
        List<DefaultAgentTaskUnit> pending = new ArrayList<>();
        for (DefaultAgentTaskUnit t : pendingQueue) {
            pending.add(t);
        }
        pending.sort(Comparator.comparingDouble(DefaultAgentTaskUnit::getPriority)
                .thenComparing(t -> t.isInProgress() ? 0 : 1));
        return new TaskQueueSnapshot(executingTask, pending);
    }

    /**
     * 根据展示编号查找任务并调整其优先级。
     * @param displayId LLM 可见的任务编号 [#N]
     * @param delta 调整量，自动钳位到 ±TASK_PRIORITY_ADJUSTMENT_RANGE
     * @return 操作结果描述，失败时返回 null
     */
    public String adjustTaskPriority(int displayId, double delta) {
        double range = ConfigsManager.TASK_PRIORITY_ADJUSTMENT_RANGE;
        double clampedDelta = Math.max(-range, Math.min(range, delta));

        // 先刷新快照以获取最新的 displayId 映射
        TaskQueueSnapshot snapshot = getTaskQueueSnapshot();

        DefaultAgentTaskUnit target = null;
        int idCounter = 0;

        if (snapshot.executingTask != null) {
            snapshot.executingTask.setDisplayId(++idCounter);
            if (snapshot.executingTask.getDisplayId() == displayId) {
                target = snapshot.executingTask;
            }
        }
        if (target == null) {
            for (DefaultAgentTaskUnit t : snapshot.pendingTasks) {
                t.setDisplayId(++idCounter);
                if (t.getDisplayId() == displayId) {
                    target = t;
                    break;
                }
            }
        }

        if (target == null) {
            return "未找到编号为 [#" + displayId + "] 的任务，请先调用 get_task_queue 确认当前队列状态。";
        }

        double oldPriority = target.getPriority();
        double newPriority = Math.max(0.1, oldPriority + clampedDelta);
        target.setPriority(newPriority);

        // 在目标任务的 turnsAddition 中留下调权记录
        String record = String.format(
                "\n[系统记录] 任务 %s 的优先级被手动调整，从 %.2f 变为 %.2f（%s了 %.2f），当前为第 %d 轮认知循环。\n",
                target.getTaskName(), oldPriority, newPriority,
                clampedDelta >= 0 ? "降低" : "提高", Math.abs(clampedDelta),
                this.consumerLoopCount);
        target.setTurnsAddition(target.getTurnsAddition() + record);

        // 如果目标在 pendingQueue 中，需要触发重新排序
        if (target != executingTask && pendingQueue.remove(target)) {
            pendingQueue.offer(target);
        }

        String dir = clampedDelta >= 0 ? "降低" : "提高";
        return String.format("已将任务 [#%d] %s 的优先级从 %.2f %s为 %.2f（%s了 %.2f）。",
                displayId, target.getTaskName(),
                oldPriority, clampedDelta >= 0 ? "降低" : "提高", newPriority,
                clampedDelta >= 0 ? "降低" : "提高", Math.abs(clampedDelta));
    }

    /**
     * 构建任务队列轻量概况，供 LLManager 注入 Prompt（方案 A）。
     */
    public String buildTaskQueueSummary() {
        TaskQueueSnapshot snapshot = getTaskQueueSnapshot();

        int maxCognitiveAge = ConfigsManager.MAX_TASK_COGNITIVE_AGE;
        int displayIdCounter = 0;

        StringBuilder sb = new StringBuilder();
        sb.append("【任务队列】");

        // 正在执行
        if (snapshot.executingTask != null) {
            snapshot.executingTask.setDisplayId(++displayIdCounter);
            sb.append(String.format("正在执行: [#%d]%s(第%d轮)",
                    snapshot.executingTask.getDisplayId(),
                    snapshot.executingTask.getTaskName(),
                    snapshot.executingTask.getCurrentTurn()));
        } else {
            sb.append("正在执行: 无");
        }

        // 待处理
        sb.append(" | 待处理: ");
        if (snapshot.pendingTasks.isEmpty()) {
            sb.append("无");
        } else {
            sb.append(String.format("%d 个 [", snapshot.pendingTasks.size()));
            boolean first = true;
            for (DefaultAgentTaskUnit t : snapshot.pendingTasks) {
                if (!first) sb.append(", ");
                t.setDisplayId(++displayIdCounter);
                int cognitiveAge = this.consumerLoopCount - t.getBornAtLoop();
                int remaining = Math.max(0, maxCognitiveAge - cognitiveAge);
                String marker = t.isInProgress() ? "(挂起)" : "";
                sb.append(String.format("[#%d]%s%s(Pri=%.1f, 剩余%d轮)",
                        t.getDisplayId(), t.getTaskName(), marker, t.getPriority(), remaining));
                String feel = t.getTaskFeelings();
                if (feel != null && !feel.isBlank()) {
                    sb.append(String.format(" 感觉:%s", feel));
                }
                first = false;
            }
            sb.append("]");
        }
        return sb.toString();
    }

    /**
     * 构建任务队列完整详情，供 GetTaskQueueTool 使用（方案 B）。
     */
    public String buildTaskQueueDetail() {
        TaskQueueSnapshot snapshot = getTaskQueueSnapshot();
        int maxCognitiveAge = ConfigsManager.MAX_TASK_COGNITIVE_AGE;
        int displayIdCounter = 0;

        int total = (snapshot.executingTask != null ? 1 : 0) + snapshot.pendingTasks.size();

        StringBuilder sb = new StringBuilder();
        sb.append("===== 任务队列完整详情 =====\n");
        sb.append(String.format("队列总数: %d\n", total));

        // 正在执行
        sb.append("\n【正在执行】\n");
        if (snapshot.executingTask != null) {
            DefaultAgentTaskUnit t = snapshot.executingTask;
            t.setDisplayId(++displayIdCounter);
            sb.append(String.format("[#%d] %s  Pri=%.1f  第%d轮\n",
                    t.getDisplayId(), t.getTaskName(), t.getPriority(), t.getCurrentTurn()));
            String feel = t.getTaskFeelings();
            if (feel != null && !feel.isBlank()) {
                sb.append("  感觉: ").append(feel).append("\n");
            }
            String text = t.getTaskText();
            if (text != null && !text.isBlank()) {
                if (text.length() > 200) text = text.substring(0, 200) + "...";
                sb.append("  ").append(text.replace("\n", "\\n")).append("\n");
            }
        } else {
            sb.append("（无）\n");
        }

        // 待处理
        sb.append("\n【待处理】\n");
        if (snapshot.pendingTasks.isEmpty()) {
            sb.append("（无）\n");
        } else {
            for (DefaultAgentTaskUnit t : snapshot.pendingTasks) {
                t.setDisplayId(++displayIdCounter);
                int cognitiveAge = this.consumerLoopCount - t.getBornAtLoop();
                int remaining = Math.max(0, maxCognitiveAge - cognitiveAge);
                String marker = t.isInProgress() ? " [挂起]" : "";
                sb.append(String.format("[#%d]%s %s  Pri=%.1f  认知年龄=%d轮(剩余%d轮)\n",
                        t.getDisplayId(), marker, t.getTaskName(), t.getPriority(),
                        cognitiveAge, remaining));
                String feel = t.getTaskFeelings();
                if (feel != null && !feel.isBlank()) {
                    sb.append("  感觉: ").append(feel).append("\n");
                }
                String text = t.getTaskText();
                if (text != null && !text.isBlank()) {
                    if (text.length() > 200) text = text.substring(0, 200) + "...";
                    sb.append("  ").append(text.replace("\n", "\\n")).append("\n");
                }
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    @Getter
    AtomicInteger cognitiveHeat = new AtomicInteger(0); // 认知热度，模拟大脑疲劳度，数值越高代表越疲劳

    // 认知消费者循环的全局轮次计数，用于基于认知周期的任务过期判断
    private int consumerLoopCount = 0;

    private LLMAdapter littleLLM;
    private LLMAdapter largeLLM;
    private LLMAdapter embLLM;

    private final Map<String, DefaultAgentToolUnit> largeLLMToolbox = new ConcurrentHashMap<>();

    public LivingLoop() {

        //装载默认工具箱
        DefaultAgentToolUnit updateScheduledTool = new UpdateScheduled();

        largeLLMToolbox.put(new SendChatMessage().getName(), new SendChatMessage());
        largeLLMToolbox.put(new GetChatHistory().getName(), new GetChatHistory());
        largeLLMToolbox.put(updateScheduledTool.getName(), updateScheduledTool);
        largeLLMToolbox.put(new GetScheduled().getName(), new GetScheduled());
        largeLLMToolbox.put(new WebSearch().getName(), new WebSearch());
        largeLLMToolbox.put(new ReadWebPage().getName(), new ReadWebPage());
        largeLLMToolbox.put(new WriteFile().getName(), new WriteFile());
        largeLLMToolbox.put(new ReadFile().getName(), new ReadFile());
        largeLLMToolbox.put(new CdWorkspace().getName(), new CdWorkspace());
        largeLLMToolbox.put(new SendFileToChat().getName(), new SendFileToChat());

        this.registerTool(new GetMoreCurrentMemorys());
        this.registerTool(new QueryDeepMemory());
        this.registerTool(new ReflectiveCompactionTool());
        this.registerTool(new SendConsoleMessage());
        this.registerTool(new CreatePendingChatTask(this));
        this.registerTool(new UpdateWebUI());
        this.registerTool(new ToolUsageReader());
        this.registerTool(new FinishTask());
        this.registerTool(new GetNowTime());
        this.registerTool(new SetSleepTimeTool());
        this.registerTool(new GetSleepTimeTool());
        this.registerTool(new GetTaskQueueTool(this));
        this.registerTool(new AdjustTaskPriorityTool(this));
        this.registerTool(new DelegateComputerTaskTool());
        this.registerTool(new ManageToolGroups(this.largeLLMToolbox));
        this.registerTool(new ManageMessageKeywords());
        this.registerTool(new McpBridge());

        log.info("[LivingLoop] 大模型默认工具箱装配完毕，已挂载工具数: {}", largeLLMToolbox.size());

        this.registerTaskHandler(new ChatTaskHandler());            // 常规聊天
        this.registerTaskHandler(new ScheduledTaskHandler());       // 定时计划
        this.registerTaskHandler(new UpdateThoughtsTaskHandler());  // 潜意识反思
        this.registerTaskHandler(new ConsoleChatTaskHandler());

        this.registerInputHandler(new ExpectedChatMessageInputHandler(this));

        this.registerInputHandler(new WebEventInputHandler(this));
        this.registerTaskHandler(new WebEventTaskHandler());
    }

    private void initLLM(){
        this.littleLLM    = new LLMAdapter(ConfigsManager.GATEKEEPER_CONFIG);
        this.largeLLM     = new LLMAdapter(ConfigsManager.BRAIN_CONFIG);
        this.embLLM       = new LLMAdapter(ConfigsManager.EMBEDDING_CONFIG);
    }

    public LLMAdapter getEmbLLM() { return embLLM; }


    // ==========================================
    // 插件系统 API：工具箱动态装配接口
    // ==========================================

    @Override
    public void registerTool(DefaultAgentToolUnit tool) {
        if (tool == null || tool.getName() == null) {
            return;
        }
        if (largeLLMToolbox.containsKey(tool.getName())) {
            log.warn("[PluginSystem] 工具 {} 已存在，原有逻辑将被覆写！", tool.getName());
        }
        largeLLMToolbox.put(tool.getName(), tool);
        log.info("[PluginSystem] 成功动态挂载外部工具: {}", tool.getName());
    }

    public void unregisterTool(String toolName) {
        if (toolName == null || !largeLLMToolbox.containsKey(toolName)) {
            return;
        }
        largeLLMToolbox.remove(toolName);
        log.info("[PluginSystem] 成功卸载外部工具: {}", toolName);
    }

    @Override
    public void registerTaskHandler(DefaultAgentTaskHandler handler) {
        if (handler == null || handler.getSupportedTaskClass() == null) return;
        taskHandlerRegistry.put(handler.getSupportedTaskClass(), handler);
        log.info("[PluginSystem] 挂载任务处理器: {} 负责处理 {}",
                handler.getClass().getSimpleName(), handler.getSupportedTaskClass().getSimpleName());
    }

    @Override
    public void registerInputHandler(DefaultAgentInputHandlerUnit handler) {
        if (handler == null || handler.getSupportedInputClass() == null) return;
        inputHandlerRegistry.put(handler.getSupportedInputClass(), handler);
        log.info("[PluginSystem] 挂载感知处理器: {} 负责处理 {}",
                handler.getClass().getSimpleName(), handler.getSupportedInputClass().getSimpleName());
    }

    @Override
    public void pushInput(DefaultAgentInputUnit input) {

    }

    @Override
    public void pushTask(DefaultAgentTaskUnit task) {
        task.setBornAtLoop(this.consumerLoopCount);

        // 计算任务专属感觉维度快照，随任务队列展示给 LLM
        FeelingDimensionManager fdm = FeelingDimensionManager.getInstance();
        if (fdm != null) {
            String taskText = task.getTaskText();
            if (taskText != null && !taskText.isBlank()) {
                List<DimensionScore> topFeelings = fdm.getTargetDimensions(taskText, true, ConfigsManager.FEELING_DIMENSION_COUNT);
                if (topFeelings.isEmpty()) {
                    task.setTaskFeelings("无共鸣");
                } else {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < topFeelings.size(); i++) {
                        DimensionScore s = topFeelings.get(i);
                        if (i > 0) sb.append(" ");
                        String polarity = s.hitWeight >= 0 ? "+" : "-";
                        sb.append(String.format("\"%s\"(%s,%.2f)", s.concept, polarity, s.InterestScore));
                    }
                    task.setTaskFeelings(sb.toString());
                }
            }
        }

        this.pendingQueue.offer(task);
        this.trimTaskQueue();
    }

    public void submitPendingRequest(DefaultAgentTaskUnit request) {
        globalPendingRequests.offer(request);
    }

    public DefaultAgentTaskUnit pollPendingRequest() {
        return globalPendingRequests.poll();
    }

    @Override
    public Logger getLogger() {
        return log;
    }

    private synchronized void trimTaskQueue() {
        int maxAmount = ConfigsManager.MAX_TASK_AMOUNT;
        int maxCognitiveAge = ConfigsManager.MAX_TASK_COGNITIVE_AGE;

        while (true) {
            // 只统计真正未开始的任务（不含被抢占挂起的 inProgress 任务）
            int trulyPending = 0;
            for (DefaultAgentTaskUnit t : pendingQueue) {
                if (!t.isInProgress()) {
                    trulyPending++;
                }
            }

            // 第一轮：删除认知年龄超限的任务（含被抢占挂起的）
            DefaultAgentTaskUnit expiredVictim = null;
            if (maxCognitiveAge > 0) {
                for (DefaultAgentTaskUnit t : pendingQueue) {
                    int cognitiveAge = this.consumerLoopCount - t.getBornAtLoop();
                    if (cognitiveAge >= maxCognitiveAge) {
                        expiredVictim = t;
                        break;
                    }
                }
            }
            if (expiredVictim != null) {
                pendingQueue.remove(expiredVictim);
                //LLManager.clearTaskCache(expiredVictim.getUUID());
                int cognitiveAge = this.consumerLoopCount - expiredVictim.getBornAtLoop();
                log.info("[TaskQueue] 过期任务已删除及其缓存已释放: {} (认知年龄: {}轮, 阈值: {}轮)",
                        expiredVictim.getClass().getSimpleName(), cognitiveAge, maxCognitiveAge);
                continue;
            }

            // 第二轮：真正未开始的任务数超上限，删除最低优先级的
            if (trulyPending <= maxAmount) {
                break;
            }

            DefaultAgentTaskUnit victim = null;
            for (DefaultAgentTaskUnit t : pendingQueue) {
                if (t.isInProgress()) {
                    continue; // 跳过被抢占挂起的任务，只淘汰从未开始过的
                }
                if (victim == null || t.getPriority() > victim.getPriority()) {
                    victim = t;
                }
            }

            if (victim != null) {
                pendingQueue.remove(victim);
                //LLManager.clearTaskCache(victim.getUUID());
                log.info("[TaskQueue] 队列积压，已抛弃最低优先级的等待任务及释放缓存: {} (Priority: {})",
                        victim.getClass().getSimpleName(),
                        victim.getPriority());
            } else {
                break;
            }
        }
    }

    public void start() {
        this.initLLM();

        // 如果配置了默认休眠时间段，启动时自动应用
        if (ConfigsManager.SLEEP_START != null && !ConfigsManager.SLEEP_START.isBlank()
                && ConfigsManager.SLEEP_END != null && !ConfigsManager.SLEEP_END.isBlank()) {
            SleepManager.getInstance().setSleepWindow(ConfigsManager.SLEEP_START, ConfigsManager.SLEEP_END);
        }

        // ==========================================
        // 线程 1a：认知觉醒（按真实间隔触发 Gatekeeper，不再每 1ms 空转）
        // 内含：cognitive heat 衰减 + 反思任务定量触发
        // ==========================================
        scheduler.scheduleAtFixedRate(() -> {
            try {
                // cognitive heat 自然衰减（每认知周期 -1，并 clamp 到 MAX）
                this.cognitiveHeat.set(Math.min(ConfigsManager.MAX_COGNITIVE_HEAT, this.cognitiveHeat.get()));
                this.cognitiveHeat.set(Math.max(this.cognitiveHeat.get() - 1, 0));

                // 触发认知周期：Gatekeeper 处理 input
                if (isGatekeeperThinking.compareAndSet(false, true)) {
                    gatekeeperExecutor.submit(() -> {
                        try {
                            handleCognitiveCycle();
                        } finally {
                            isGatekeeperThinking.set(false);
                        }
                    });
                }

                // 定量反思任务
                if (processedTaskCount.get() >= ConfigsManager.TASK_COUNT_FOR_REFLECTION
                        && ConfigsManager.TASK_COUNT_FOR_REFLECTION > 1) {
                    processedTaskCount.set(0);
                    log.info("[System] 达到任务处理阈值，正在向潜意识抛入强制反思任务...");
                    pendingQueue.offer(new UpdateThoughtsTask());
                    this.trimTaskQueue();
                }
            } catch (Exception e) {
                log.error("[LivingLoop][SCHE] 认知循环异常：", e);
            }
        }, ConfigsManager.COGNITIVE_CYCLE_TICKS, ConfigsManager.COGNITIVE_CYCLE_TICKS, TimeUnit.MILLISECONDS);

        // ==========================================
        // 线程 1b：定时任务生产（独立排程，不与认知循环耦合）
        // ==========================================
        if (ConfigsManager.SCHEDULE_CYCLING_TIME > 0) {
            scheduler.scheduleAtFixedRate(() -> {
                try {
                    log.info("[System] 达到定时任务阈值，正在向队列抛入定时任务...");
                    pendingQueue.offer(new ScheduledTask());
                    this.trimTaskQueue();
                } catch (Exception e) {
                    log.error("[LivingLoop][SCHE] 定时任务触发异常：", e);
                }
            }, ConfigsManager.SCHEDULE_CYCLING_TIME, ConfigsManager.SCHEDULE_CYCLING_TIME, TimeUnit.MILLISECONDS);
        }

        // ==========================================
        // 线程 2：大脑皮层深度思考与动作执行 (消费者)
        // ==========================================
        executorService.submit(() -> {
            log.info("[com.cna.agent.LivingLoop][EXEC] 大脑皮层任务消费线程已启动...");
            ObjectMapper mapper = new ObjectMapper();

            while (!Thread.currentThread().isInterrupted()) {
                try {
                    // 没有正在执行的任务时，从待处理队列拉取
                    if (executingTask == null) {
                        executingTask = pendingQueue.poll(1, TimeUnit.SECONDS);
                        if (executingTask == null) {
                            continue;
                        }
                    }

                    // 粘性执行
                    while (executingTask != null) {
                        // 检查是否有更高优先级的任务插队
                        DefaultAgentTaskUnit preemptor = null;
                        double currentPriority = executingTask.getPriority();
                        for (DefaultAgentTaskUnit t : pendingQueue) {
                            if (t.getPriority() < currentPriority) {
                                preemptor = t;
                                break;
                            }
                        }
                        if (preemptor != null) {
                            log.info("\n[执行总线] 更高优先级的任务插队，挂起当前任务: {} (Priority: {}) -> 优先执行: {} (Priority: {})",
                                    executingTask.getClass().getSimpleName(), executingTask.getPriority(),
                                    preemptor.getClass().getSimpleName(), preemptor.getPriority());
                            pendingQueue.remove(preemptor);
                            pendingQueue.offer(executingTask);
                            executingTask = preemptor;
                            continue;
                        }

                        log.info("\n[执行总线] 开始处理任务: {}", executingTask.getClass().getSimpleName());

                        ArrayNode toolsDefinitionArray = mapper.createArrayNode();
                        Set<String> addedToolNames = new HashSet<>();
                        Set<String> activatedGroups = executingTask.getActivatedToolGroups();

                        for (DefaultAgentToolUnit tool : new ArrayList<>(largeLLMToolbox.values())) {
                            if (!tool.isAutoLoad()) {
                                continue;
                            }
                            String className = tool.getClass().getSimpleName();
                            String toolGroup = ToolPromptsManager.getToolGroup(className);
                            boolean isDefault = ToolPromptsManager.isDefaultGroup(className);

                            if (isDefault || (toolGroup != null && activatedGroups.contains(toolGroup))) {
                                if (addedToolNames.add(tool.getName())) {
                                    toolsDefinitionArray.add(tool.getToolDefinition());
                                }
                            }
                        }

                        DefaultAgentTaskHandler handler = taskHandlerRegistry.get(executingTask.getClass());
                        if (handler != null) {
                            DefaultAgentTaskUnit result = handler.handleTask(executingTask, LivingLoop.this, toolsDefinitionArray);
                            if (result != null) {
                                executingTask = result;
                                processedTaskCount.incrementAndGet();
                                continue;
                            }
                            processedTaskCount.incrementAndGet();
                            executingTask = null;
                            break;
                        } else {
                            log.warn("[执行总线] 遇到未知的任务类型 [{}], 且没有挂载对应的 Handler，已跳过处理。",
                                    executingTask.getClass().getSimpleName());
                            executingTask = null;
                            break;
                        }
                    }

                    // 每处理完一个任务（粘性执行退出），认知循环轮次+1，推进所有等待任务的老化
                    consumerLoopCount++;

                } catch (InterruptedException e) {
                    log.info("[EXEC] 消费者线程收到中断信号，即将退出。");
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    log.error("[com.cna.agent.LivingLoop][EXEC] 任务处理循环异常：", e);
                }
            }
        });
    }

    // 返回 null 代表任务已终结，可以被销毁
    public DefaultAgentTaskUnit executeCognitiveCycle(
            DefaultAgentTaskUnit taskUnit,
            ScenePromptsManager scenePrompts,
            Map<String, Object> baseData,
            LLMAdapter DefaultLLM,
            ArrayNode toolsDefinitionArray,
            String taskDesc) {

        ObjectMapper mapper = new ObjectMapper();

        // 动态决定使用哪个模型
        LLMAdapter llm = DefaultLLM;
        int turn = taskUnit.getCurrentTurn();

        // 【核心修改】：提取当前任务的唯一标识符
        UUID currentTaskId = taskUnit.getUUID();

        log.info("[EXEC-Engine] [{}] 正在进行第 {} 轮深度思考与动作执行...", taskDesc, turn);

        Map<String, Object> turnData = new HashMap<>(baseData);

        // 提取当前任务来源，用于 current memory 标注和 finish_task 记忆回馈
        List<String> taskSources = taskUnit.getSources();

        CallResult result;

        StringBuilder currentMemory = new StringBuilder();
        currentMemory.append(Utils.getNowPrecise() + "时,\n");
        //储存本轮任务处理中所有需要被短期记忆记录的东西

        if (turn == 1) {
            // turnsAddition 不再注入模板——GLOBAL_CACHE 中的 assistant/tool 消息已是完整历史
            turnData.put("turnsAddition", "");

            if(lastSolvingTask != null){
                //这说明这个任务插队了
                currentMemory.append("上一轮执行的任务被挂起, " + taskUnit.getTaskName() + " 因判断后的执行权重更高被优先处理...\n");
            } else {
                currentMemory.append(taskUnit.getTaskName() + " 开始被处理...\n");
            }
            lastSolvingTask = taskUnit;
            taskUnit.markInProgress(); // 标记任务已开始执行，防止同权重饥饿

            // 【核心修改】：在 LLManager 中传入 currentTaskId
            if(scenePrompts.getThinkingPrompt() != null && !scenePrompts.getThinkingPrompt().isEmpty() && !scenePrompts.getThinkingPrompt().equals("")) {
                result = LLManager.executeScene(currentTaskId, scenePrompts.getThinkingPrompt(), turnData, llm, toolsDefinitionArray);
            } else {
                result = LLManager.executeScene(currentTaskId, scenePrompts.getSolvingPrompt(), turnData, llm, toolsDefinitionArray);
            }
        } else {
            // 第2轮及以后：turnsAddition 不注入模板，上下文完全由 GLOBAL_CACHE 承载
            turnData.put("turnsAddition", "");
            result = LLManager.executeScene(currentTaskId, scenePrompts.getSolvingPrompt(), turnData, llm, toolsDefinitionArray);
            currentMemory.append("之前的 " + taskUnit.getTaskName() + " 正在进行第" + turn + "轮处理...\n");
        }

        StringBuilder nowTurnAddition = new StringBuilder();
        nowTurnAddition.append(taskUnit.getTurnsAddition());// 自身对象内部可以保留记录以供短期记忆提取，但不传给大模型

        nowTurnAddition.append("在任务 " + taskUnit.getTaskName() + " 的第" + turn + "轮思考中，");
        if(result.getContent() != null && !result.getContent().isEmpty() && !result.getContent().equals("") && !result.getContent().equals(" ")) {
            nowTurnAddition.append("你产生了以下想法:{\n");
            nowTurnAddition.append(result.getContent());
            nowTurnAddition.append("\n};\n");
            currentMemory.append("你的想法是: \"" + result.getContent() + "\";\n");
        }

        // 【核心修改】：解封错误拦截，异常时主动销毁缓存
        // ---------- 检测 LLM 返回的错误/异常响应，防止无限循环 ----------
        if (result.getContent() != null && isLLMErrorResponse(result.getContent())) {
            log.error("[EXEC-Engine] LLM 返回了无法恢复的错误，强制结束任务: {}", result.getContent());
            lastSolvingTask = null;
            MemoryManager.getInstance().inputCurrentMemory(currentMemory.toString(), taskSources);

            LLManager.clearCache();

            return null;
        }

        // 只要没有工具调用，直接结束任务并归档
        if (!result.isToolCall() || result.getToolCalls() == null
                || !result.getToolCalls().isArray() || result.getToolCalls().isEmpty()) {
            log.info("[EXEC-Engine] 💤 模型未返回工具调用，直接结束任务。响应内容: {}", result.getContent());
            currentMemory.append("在本轮处理中没有调用任何工具，任务自动结束——也许是出错了...");
            MemoryManager.getInstance().inputCurrentMemory(currentMemory.toString(), taskSources);
            lastSolvingTask = null;

            // 【核心修改】：任务自然结束，清空该任务的专属上下文缓存
            //LLManager.clearTaskCache(currentTaskId);
            return null;
        }


        StringBuilder toolResults = new StringBuilder("\n\n【第 " + turn + " 轮工具观察结果】:\n");
        boolean hasFinishTask = false;

        // 注入当前任务来源 + 谐振分析到 FinishTask.ThreadLocal
        FinishTask.CURRENT_TASK_SOURCES.set(taskSources);
        if (turnData.containsKey("feeling_resonance_result")) {
            FinishTask.CURRENT_RESONANCE_RESULT.set(
                    (FeelingResonanceAnalyzer.ResonanceAnalysisResult) turnData.get("feeling_resonance_result"));
        }

        for (JsonNode toolCall : result.getToolCalls()) {
            String functionName = toolCall.path("function").path("name").asText();
            String argumentsStr = toolCall.path("function").path("arguments").asText();
            String toolCallId = toolCall.path("id").asText();

            log.info("[EXEC-Engine] 决定采取动作: [{}]", functionName);

            if ("finish_task".equals(functionName)) {
                hasFinishTask = true;
            }

            // P5 安全：电脑操作（文件/浏览器/桌面）仅限主人或内部渠道触发，挡掉外部非 master 用户
            if ("delegate_computer_task".equals(functionName) && !isComputerTaskAuthorized(taskUnit)) {
                String denied = "权限不足：电脑操作仅限主人(master)或内部渠道触发，已拒绝本次委派。";
                log.warn("[EXEC-Engine] delegate_computer_task 被拒绝（来源未授权）: task={}", taskUnit.getClass().getSimpleName());
                toolResults.append("调用了工具 [").append(functionName).append("] , 但因来源未授权被拒绝;\n");
                LLManager.feedToolResult(currentTaskId, toolCallId, functionName, denied);
                continue;
            }

            DefaultAgentToolUnit targetTool = largeLLMToolbox.get(functionName);
            if (targetTool != null) {
                try {
                    JsonNode argsNode;
                    try {
                        argsNode = mapper.readTree(argumentsStr);
                    } catch (Exception parseEx) {
                        log.warn("[EXEC-Engine] 标准 JSON 解析失败，将尝试修复未转义双引号: {}", argumentsStr);
                        String repaired = LLMAdapter.repairInnerJsonQuotes(argumentsStr);
                        log.info("[EXEC-Engine] 修复后的 arguments: {}", repaired);
                        argsNode = mapper.readTree(repaired);
                    }
                    String execResult = targetTool.execute(argsNode);
                    log.info("[EXEC-Engine] 动作反馈: {}", execResult);

                    // 工具组激活/注销（线程安全：直接从 argsNode 读取，不依赖工具实例状态）
                    if ("manage_tool_groups".equals(functionName)) {
                        String action = argsNode.path("action").asText("");
                        String group = argsNode.path("group").asText("");
                        Set<String> activated = taskUnit.getActivatedToolGroups();
                        if ("activate".equals(action) && !group.isEmpty()) {
                            activated.add(group);
                            log.info("[EXEC-Engine] 任务 {} 激活工具组: {} (当前已激活: {})", currentTaskId, group, activated);
                        } else if ("deactivate".equals(action) && !group.isEmpty()) {
                            activated.remove(group);
                            log.info("[EXEC-Engine] 任务 {} 注销工具组: {} (当前已激活: {})", currentTaskId, group, activated);
                        }
                    }

                    toolResults.append("调用了工具 [").append(functionName).append("], 返回了 [").append(execResult).append("];\n");

                    // 【核心修改】：向该任务的专属缓存中压入 Tool 执行结果
                    LLManager.feedToolResult(currentTaskId, toolCallId, functionName, execResult);

                    if(targetTool.isAutoMemory()){
                        currentMemory.append("调用了工具 [").append(functionName).append("], 返回了 [").append(execResult).append("];\n");
                    }

                } catch (Exception e) {
                    log.error("[EXEC-Engine] 工具解析或执行异常", e);
                    toolResults.append("调用了工具 [").append(functionName).append("] , 却发生了发生程序错误:[\n" + e.toString() + "\n];\n");
                    // 【核心修改】：异常时同样要压入缓存，告知大模型报错了
                    LLManager.feedToolResult(currentTaskId, toolCallId, functionName, toolResults.toString());
                }
            } else {
                String notFoundResult = "工具 \"" + functionName + "\" 不存在，请检查工具名称是否正确。";
                toolResults.append("调用了工具 [").append(functionName).append("] , 但是这个工具压根不存在;\n");
                // 【核心修改】：工具不存在时压入缓存反馈
                LLManager.feedToolResult(currentTaskId, toolCallId, functionName, notFoundResult);
            }
        }

        if (hasFinishTask) {
            log.info("[EXEC-Engine] 捕捉到 finish_task 工具调用，模型主动判定任务完成。");
            lastSolvingTask = null;

            MemoryManager.getInstance().inputCurrentMemory(currentMemory.toString(), taskSources);
            FinishTask.CURRENT_TASK_SOURCES.remove();
            FinishTask.CURRENT_RESONANCE_RESULT.remove();

            // 【核心修改】：主动销毁该任务完成后的上下文缓存
            //LLManager.clearTaskCache(currentTaskId);
            return null; // 直接终结任务
        }

        // 清理 ThreadLocal（finish_task 未调用时）
        FinishTask.CURRENT_TASK_SOURCES.remove();
        FinishTask.CURRENT_RESONANCE_RESULT.remove();

        // 推进轮数，并检查最大循环限制
        log.info("[EXEC-Engine] 获取到观察线索，转入下一轮思考...");
        nowTurnAddition.append(toolResults.toString());

        taskUnit.setTurnsAddition(nowTurnAddition.toString());
        taskUnit.setCurrentTurn(turn + 1); // 核心：向前推进一步！

        if (taskUnit.getCurrentTurn() > ConfigsManager.CONSUMER_CYCLING_TIME) {
            log.warn("[EXEC-Engine] 任务执行达到 {} 轮上限，防死循环，强制结束。", ConfigsManager.CONSUMER_CYCLING_TIME);
            currentMemory.append("……由于任务处理循环到上限了，这个任务被强制结束了……");
            lastSolvingTask = null;

            List<String> a = Collections.singletonList(currentMemory.toString());
            MemoryManager.getInstance().inputCurrentMemorys(a, taskSources);

            // 【核心修改】：死循环被干掉时，清空缓存
            //LLManager.clearTaskCache(currentTaskId);
            return null; // 超出轮数，销毁
        }
        if(!currentMemory.isEmpty()) {
            List<String> a = Collections.singletonList(currentMemory.toString());
            MemoryManager.getInstance().inputCurrentMemorys(a, taskSources);
        }

        return taskUnit; // 返回更新后的任务，准备重新入队
    }

    private void handleCognitiveCycle() {
        if (SleepManager.getInstance().isSleeping()) {
            return;
        }

        List<DefaultAgentInputUnit> currentBatch = new ArrayList<>();
        Main.AgentInputTasksQueue.drainTo(currentBatch);

        if (!currentBatch.isEmpty()) {
            log.info("[CognitiveCycle] 触发认知觉醒，捕获到 {} 条待处理的感知输入", currentBatch.size());

            Map<Class<? extends DefaultAgentInputUnit>, List<DefaultAgentInputUnit>> groupedInputs = new HashMap<>();
            for (DefaultAgentInputUnit input : currentBatch) {
                groupedInputs.computeIfAbsent(input.getClass(), k -> new ArrayList<>()).add(input);
            }

            for (Map.Entry<Class<? extends DefaultAgentInputUnit>, List<DefaultAgentInputUnit>> entry : groupedInputs.entrySet()) {
                DefaultAgentInputHandlerUnit handler = inputHandlerRegistry.get(entry.getKey());
                if (handler != null) {
                    handler.handleInputs(entry.getValue());
                } else {
                    log.warn("[CognitiveCycle] 收到未知的 Input 类型: {}，已丢弃", entry.getKey().getSimpleName());
                }
            }
        }

        for (DefaultAgentInputHandlerUnit handler : inputHandlerRegistry.values()) {
            handler.tick();
        }
    }

    /**
     * 判断 LLM 返回的内容是否为不可恢复的错误响应，
     * 防止将 API 解析错误误认为"模型不调用工具"而进入无限循环。
     */
    /**
     * delegate_computer_task 的来源授权。
     * 内部渠道（控制台 / 定时 / 反思 / 网页后台）一律放行；外部聊天仅当发话者在 master 名单内放行；
     * 未知来源默认拒绝。master 名单为空时，外部聊天一律拒绝（安全默认）。
     */
    private boolean isComputerTaskAuthorized(DefaultAgentTaskUnit task) {
        if (task instanceof ConsoleChatTask || task instanceof ScheduledTask
                || task instanceof UpdateThoughtsTask || task instanceof WebEventTask) {
            return true;
        }
        if (task instanceof ChatTask ct) {
            return ConfigsManager.isMaster(ct.getRole());
        }
        return false;
    }

    private static boolean isLLMErrorResponse(String content) {
        if (content == null) return false;
        return content.startsWith("响应缺少 choices")
                || content.startsWith("响应格式异常")
                || content.startsWith("API 错误")
                || content.startsWith("请求超时")
                || content.startsWith("网络异常")
                || content.startsWith("计算资源请求失败")
                || content.startsWith("响应结构异常")
                || content.startsWith("无法解析 JSON");
    }

    public void stop() {
        log.info("[BrainLoop] 收到停机指令，正在关闭心跳引擎...");
        scheduler.shutdown();
        executorService.shutdown();
        gatekeeperExecutor.shutdown();
        try {
            if (!scheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
            if (!executorService.awaitTermination(2, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
            if (!gatekeeperExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                gatekeeperExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            executorService.shutdownNow();
            gatekeeperExecutor.shutdownNow();
        }
    }
}