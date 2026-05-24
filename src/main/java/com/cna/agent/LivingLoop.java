package com.cna.agent;

import com.cna.Main;
import com.cna.Utils;
import com.cna.agent.AgentInput.DefaultAgentInputUnit;
import com.cna.agent.AgentInputHandlers.DefaultAgentInputHandlerUnit;
import com.cna.agent.AgentInputHandlers.ExpectedChatMessageInputHandler;
import com.cna.agent.AgentInputHandlers.WebEventInputHandler;
import com.cna.agent.AgentTask.ChatTask;
import com.cna.agent.AgentTask.DefaultAgentTaskUnit;
import com.cna.agent.AgentTask.ScheduledTask;
import com.cna.agent.AgentTask.UpdateThoughtsTask;
import com.cna.agent.AgentTasksHandlers.*;
import com.cna.agent.AgentTool.*;
import com.cna.config.ConfigsManager;
import com.cna.config.ScenePromptsManager;
import com.cna.db.FeelingDimensionManager;
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
import com.cna.agent.AgentTool.*;
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

    // 累加器：记录度过了多少个 Tick
    private int tickCounter_CognitiveCycle = 0;

    // 累加器：定时任务计数器
    private int scheduledTaskCounter = 0;

    // Gatekeeper (小模型) 专用异步线程池，防止网络请求阻塞心跳总线
    private final ExecutorService gatekeeperExecutor = Executors.newSingleThreadExecutor();

    // Gatekeeper 状态锁。保证小模型一次只专注思考一批消息
    private final AtomicBoolean isGatekeeperThinking = new AtomicBoolean(false);

    // 累加器：跨线程安全的任务处理计数器，用于触发定期反思
    private final AtomicInteger processedTaskCount = new AtomicInteger(0);

    // 将双端队列替换为优先级阻塞队列，根据 priority 升序排列（数值越小，越先出队）
    // 同优先级时，正在执行中（inProgress）的任务优先，防止不断被同权重新任务抢占导致饥饿
    private PriorityBlockingQueue<DefaultAgentTaskUnit> TaskQueue = new PriorityBlockingQueue<>(
            1145, Comparator.comparingDouble(DefaultAgentTaskUnit::getPriority)
            .thenComparing(t -> t.isInProgress() ? 0 : 1)
    );

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
        for (DefaultAgentTaskUnit t : TaskQueue) {
            if (!t.isInProgress()) {
                count++;
            }
        }
        return count;
    }


    @Getter
    AtomicInteger cognitiveHeat = new AtomicInteger(0); // 认知热度，模拟大脑疲劳度，数值越高代表越疲劳

    private LLMAdapter littleLLM;
    private LLMAdapter largeLLM;
    //private LLMAdapter advancedLLM;
    //private LLMAdapter plannerLLM;
    //private LLMAdapter SchedulerLLM;
    private LLMAdapter embLLM;

    private final Map<String, DefaultAgentToolUnit> largeLLMToolbox = new ConcurrentHashMap<>();

    public LivingLoop() {

        //装载默认工具箱
        DefaultAgentToolUnit updateInterestsTool = new UpdateInterests();
        DefaultAgentToolUnit updateScheduledTool = new UpdateScheduled();
        DefaultAgentToolUnit switchModelTool = new SwitchToAdvancedModel();

        largeLLMToolbox.put(new SendChatMessage().getName(), new SendChatMessage());
        largeLLMToolbox.put(new GetChatHistory().getName(), new GetChatHistory());
        //largeLLMToolbox.put(updateInterestsTool.getName(), updateInterestsTool);
        largeLLMToolbox.put(updateScheduledTool.getName(), updateScheduledTool);
        largeLLMToolbox.put(new GetScheduled().getName(), new GetScheduled());
        //largeLLMToolbox.put(switchModelTool.getName(), switchModelTool);
        //largeLLMToolbox.put(new GetInterests().getName(), new GetInterests());
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
        //this.advancedLLM  = new LLMAdapter(ConfigsManager.ADVANCED_BRAIN_CONFIG);
        //this.plannerLLM   = new LLMAdapter(ConfigsManager.PLANNER_CONFIG);
        //this.SchedulerLLM = new LLMAdapter(ConfigsManager.SCHEDULER_CONFIG);
        this.embLLM       = new LLMAdapter(ConfigsManager.EMBEDDING_CONFIG);
    }

    public LLMAdapter getEmbLLM()       { return embLLM; }

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
        // PBQ 使用 offer 直接入队，自动触发排序
        this.TaskQueue.offer(task);
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
        long now = System.currentTimeMillis();
        long expirationMs = ConfigsManager.TASK_EXPIRATION_TIME_MS;
        int maxAmount = ConfigsManager.MAX_TASK_AMOUNT;

        while (true) {
            int pendingCount = 0;
            for (DefaultAgentTaskUnit t : TaskQueue) {
                if (!t.isInProgress()) {
                    pendingCount++;
                }
            }

            // 第一轮：删除已过期的挂起任务（执行中的不会被删除）
            DefaultAgentTaskUnit expiredVictim = null;
            if (expirationMs > 0) {
                for (DefaultAgentTaskUnit t : TaskQueue) {
                    if (t.isInProgress()) {
                        continue;
                    }
                    long age = now - t.getCreateTime();
                    if (age > expirationMs) {
                        expiredVictim = t;
                        break;
                    }
                }
            }
            if (expiredVictim != null) {
                TaskQueue.remove(expiredVictim);
                // 【核心修改】：同时销毁过期废弃任务的上下文缓存，防止内存泄漏
                LLManager.clearTaskCache(expiredVictim.getUUID());
                long age = now - expiredVictim.getCreateTime();
                log.info("[TaskQueue] 过期任务已删除及其缓存已释放: {} (创建后挂起 {}ms, 阈值 {}ms)",
                        expiredVictim.getClass().getSimpleName(), age, expirationMs);
                continue; // 继续循环检查是否还有其它过期任务
            }

            // 第二轮：pending 任务数仍在队列上限之上，删除最低优先级任务
            if (pendingCount <= maxAmount) {
                break;
            }

            DefaultAgentTaskUnit victim = null;
            for (DefaultAgentTaskUnit t : TaskQueue) {
                if (t.isInProgress()) {
                    continue;
                }
                if (victim == null || t.getPriority() > victim.getPriority()) {
                    victim = t;
                }
            }

            if (victim != null) {
                TaskQueue.remove(victim);
                // 【核心修改】：同时销毁被挤掉的积压任务的上下文缓存
                LLManager.clearTaskCache(victim.getUUID());
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

        // ==========================================
        // 线程 1：极速感官折叠与任务生产 (生产者)
        // ==========================================
        scheduler.scheduleAtFixedRate(() -> {
            try {
                this.tickCounter_CognitiveCycle ++;

                if (this.tickCounter_CognitiveCycle >= ConfigsManager.COGNITIVE_CYCLE_TICKS) {

                    this.cognitiveHeat.set(Math.min(ConfigsManager.MAX_COGNITIVE_HEAT, this.cognitiveHeat.get()));
                    this.cognitiveHeat.set(Math.max(this.cognitiveHeat.get() - 1, 0));

                    if (isGatekeeperThinking.compareAndSet(false, true)) {
                        gatekeeperExecutor.submit(() -> {
                            try {
                                handleCognitiveCycle();
                            } finally {
                                isGatekeeperThinking.set(false);
                            }
                        });
                    }
                    this.tickCounter_CognitiveCycle = 0;
                }

                // 【定量反思任务】
                if (processedTaskCount.get() >= ConfigsManager.TASK_COUNT_FOR_REFLECTION && ConfigsManager.TASK_COUNT_FOR_REFLECTION > 1 ) {
                    processedTaskCount.set(0);
                    log.info("[System] 达到任务处理阈值，正在向潜意识抛入强制反思任务...");
                    TaskQueue.offer(new UpdateThoughtsTask()); // 变更为 offer
                    this.trimTaskQueue();
                }

                this.scheduledTaskCounter ++;

                // 【定时计划任务】
                if (this.scheduledTaskCounter >= ConfigsManager.SCHEDULE_CYCLING_TIME && ConfigsManager.SCHEDULE_CYCLING_TIME > 0) {
                    log.info("[System] 达到定时任务阈值，正在向队列抛入定时任务...");
                    TaskQueue.offer(new ScheduledTask()); // 变更为 offer
                    this.trimTaskQueue();
                    this.scheduledTaskCounter = 0;
                }

            } catch (Exception e) {
                log.error("[com.cna.agent.LivingLoop][SCHE] 任务生产循环异常：", e);
            }
        }, 1, 1, TimeUnit.MILLISECONDS);

        // ==========================================
        // 线程 2：大脑皮层深度思考与动作执行 (消费者)
        // ==========================================
        executorService.submit(() -> {
            log.info("[com.cna.agent.LivingLoop][EXEC] 大脑皮层任务消费线程已启动...");
            ObjectMapper mapper = new ObjectMapper();

            while (!Thread.currentThread().isInterrupted()) {
                try {
                    // 使用 poll 拿取队列头部任务
                    DefaultAgentTaskUnit task = TaskQueue.poll(1, TimeUnit.SECONDS);
                    if (task == null) {
                        continue;
                    }

                    // 粘性执行
                    DefaultAgentTaskUnit stickyTask = task;
                    while (stickyTask != null) {
                        DefaultAgentTaskUnit preemptor = null;
                        double currentPriority = stickyTask.getPriority();
                        for (DefaultAgentTaskUnit t : TaskQueue) {
                            if (t.getPriority() < currentPriority) {
                                preemptor = t;
                                break;
                            }
                        }
                        if (preemptor != null) {
                            log.info("\n[执行总线] 更高优先级的任务插队，挂起当前任务: {} (Priority: {}) -> 优先执行: {} (Priority: {})",
                                    stickyTask.getClass().getSimpleName(), stickyTask.getPriority(),
                                    preemptor.getClass().getSimpleName(), preemptor.getPriority());
                            TaskQueue.remove(preemptor);
                            TaskQueue.offer(stickyTask);
                            stickyTask = preemptor;
                            continue;
                        }

                        log.info("\n[执行总线] 开始处理任务: {}", stickyTask.getClass().getSimpleName());

                        ArrayNode toolsDefinitionArray = mapper.createArrayNode();
                        for (DefaultAgentToolUnit tool : new ArrayList<>(largeLLMToolbox.values())) {
                            if (tool.isAutoLoad()) {
                                toolsDefinitionArray.add(tool.getToolDefinition());
                            }
                        }

                        DefaultAgentTaskHandler handler = taskHandlerRegistry.get(stickyTask.getClass());
                        if (handler != null) {
                            stickyTask = handler.handleTask(stickyTask, LivingLoop.this, toolsDefinitionArray);
                            if (stickyTask != null) {
                                processedTaskCount.incrementAndGet();
                                continue;
                            }
                            processedTaskCount.incrementAndGet();
                            break;
                        } else {
                            log.warn("[执行总线] 遇到未知的任务类型 [{}], 且没有挂载对应的 Handler，已跳过处理。",
                                    stickyTask.getClass().getSimpleName());
                            break;
                        }
                    }

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

        CallResult result;

        StringBuilder currentMemory = new StringBuilder();
        currentMemory.append(Utils.getNowPrecise() + "时,\n");
        //储存本轮任务处理中所有需要被短期记忆记录的东西

        if (turn == 1) {
            // 【核心修改】：第1轮正常压入初始记忆或分析设定
            turnData.put("turnsAddition", taskUnit.getTurnsAddition());

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
            // 【核心修改】：第2轮及以后，切断“上下文套娃”，把 turnsAddition 置空，让大模型完全依靠独立缓存追溯前情！
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
            MemoryManager.getInstance().inputCurrentMemory(currentMemory.toString());

            // 主动销毁该任务由于网络异常半途而废的污染缓存
            LLManager.clearTaskCache(currentTaskId);
            return null;
        }

        // 只要没有工具调用，直接结束任务并归档
        if (!result.isToolCall() || result.getToolCalls() == null
                || !result.getToolCalls().isArray() || result.getToolCalls().isEmpty()) {
            log.info("[EXEC-Engine] 💤 模型未返回工具调用，直接结束任务。响应内容: {}", result.getContent());
            currentMemory.append("在本轮处理中没有调用任何工具，任务自动结束——也许是出错了...");
            MemoryManager.getInstance().inputCurrentMemory(currentMemory.toString());
            lastSolvingTask = null;

            // 【核心修改】：任务自然结束，清空该任务的专属上下文缓存
            LLManager.clearTaskCache(currentTaskId);
            return null;
        }


        StringBuilder toolResults = new StringBuilder("\n\n【第 " + turn + " 轮工具观察结果】:\n");

        for (JsonNode toolCall : result.getToolCalls()) {
            String functionName = toolCall.path("function").path("name").asText();
            String argumentsStr = toolCall.path("function").path("arguments").asText();
            String toolCallId = toolCall.path("id").asText();

            log.info("[EXEC-Engine] 决定采取动作: [{}]", functionName);

            if ("switch_to_advanced_model".equals(functionName)) {
                log.info("[EXEC-Engine] 收到升维请求，下一轮思考将切换至高级大模型。");
                toolResults.append("（调用了工具switch_to_advanced_model,切换到了更高级的大模型;）\n");
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

                    toolResults.append("调用了工具 [").append(functionName).append("], 返回了 [").append(execResult).append("];\n");

                    // 【核心修改】：向该任务的专属缓存中压入 Tool 执行结果
                    LLManager.feedToolResult(currentTaskId, toolCallId, functionName, execResult);

                    if(targetTool.isAutoMemory()){
                        currentMemory.append("调用了工具 [").append(functionName).append("], 返回了 [").append(execResult).append("];\n");
                    }

                } catch (Exception e) {
                    log.error("[EXEC-Engine] 工具解析或执行异常", e);
                    String errorResult = "程序错误: " + e.toString();
                    toolResults.append("调用了工具 [").append(functionName).append("] , 却发生了发生程序错误:[\n" + e.toString() + "\n];\n");
                    // 【核心修改】：异常时同样要压入缓存，告知大模型报错了
                    LLManager.feedToolResult(currentTaskId, toolCallId, functionName, errorResult);
                }
            } else {
                String notFoundResult = "工具 \"" + functionName + "\" 不存在，请检查工具名称是否正确。";
                toolResults.append("调用了工具 [").append(functionName).append("] , 但是这个工具压根不存在;\n");
                // 【核心修改】：工具不存在时压入缓存反馈
                LLManager.feedToolResult(currentTaskId, toolCallId, functionName, notFoundResult);
            }

            if ("finish_task".equals(functionName)) {
                log.info("[EXEC-Engine] 捕捉到 finish_task 工具调用，模型主动判定任务完成。");
                lastSolvingTask = null;

                MemoryManager.getInstance().inputCurrentMemory(currentMemory.toString());

                // 【核心修改】：主动销毁该任务完成后的上下文缓存
                LLManager.clearTaskCache(currentTaskId);
                return null; // 直接终结任务
            }

        }

        // 推进轮数，并检查最大循环限制
        log.info("[EXEC-Engine] 获取到观察线索，转入下一轮思考...");
        nowTurnAddition.append(toolResults.toString());

        taskUnit.setTurnsAddition(nowTurnAddition.toString());
        taskUnit.setCurrentTurn(turn + 1); // 核心：向前推进一步！

        if (taskUnit.getCurrentTurn() > ConfigsManager.CONSUMER_CYCLING_TIME) {
            log.warn("[EXEC-Engine] 任务执行达到 {} 轮上限，防死循环，强制结束。", ConfigsManager.CONSUMER_CYCLING_TIME);
            currentMemory.append("……由于任务处理循环到上限了，这个任务被强制结束了……");
            lastSolvingTask = null;

            List<String> a = new LinkedList<>();
            a.add(currentMemory.toString());
            MemoryManager.getInstance().inputCurrentMemorys(a);

            // 【核心修改】：死循环被干掉时，清空缓存
            LLManager.clearTaskCache(currentTaskId);
            return null; // 超出轮数，销毁
        }
        if(!currentMemory.isEmpty()) {
            List<String> a = new LinkedList<>();
            a.add(currentMemory.toString());
            MemoryManager.getInstance().inputCurrentMemorys(a);
        }

        return taskUnit; // 返回更新后的任务，准备重新入队
    }

    private void handleCognitiveCycle() {
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