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
    private PriorityBlockingQueue<DefaultAgentTaskUnit> TaskQueue = new PriorityBlockingQueue<>(
            1145, Comparator.comparingDouble(DefaultAgentTaskUnit::getPriority)
    );

    //Input队列
    private final ConcurrentLinkedQueue<DefaultAgentTaskUnit> globalPendingRequests = new ConcurrentLinkedQueue<>();

    //private LinkedHashMap<String, ChatTask> ChatTaskPreparationPool = new LinkedHashMap<>();

    private final Map<Class<? extends DefaultAgentTaskUnit>, DefaultAgentTaskHandler> taskHandlerRegistry = new ConcurrentHashMap<>();

    private final Map<Class<? extends DefaultAgentInputUnit>, DefaultAgentInputHandlerUnit> inputHandlerRegistry = new ConcurrentHashMap<>();

    volatile DefaultAgentTaskUnit lastSolvingTask = null;
    //状态管理方法是，当一个任务结束时，这玩意也必须成为null


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
        DefaultAgentToolUnit updateThoughtsTool = new UpdateThoughts();
        DefaultAgentToolUnit updateScheduledTool = new UpdateScheduled();
        DefaultAgentToolUnit switchModelTool = new SwitchToAdvancedModel();

        largeLLMToolbox.put(new SendChatMessage().getName(), new SendChatMessage());
        largeLLMToolbox.put(new GetChatHistory().getName(), new GetChatHistory());
        largeLLMToolbox.put(updateThoughtsTool.getName(), updateThoughtsTool);
        largeLLMToolbox.put(updateInterestsTool.getName(), updateInterestsTool);
        largeLLMToolbox.put(updateScheduledTool.getName(), updateScheduledTool);
        largeLLMToolbox.put(new GetScheduled().getName(), new GetScheduled());
        //largeLLMToolbox.put(switchModelTool.getName(), switchModelTool);
        largeLLMToolbox.put(new GetInterests().getName(), new GetInterests());
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

    //public LLMAdapter getLittleLLM()    { return littleLLM; }
    //public LLMAdapter getLargeLLM()     { return largeLLM; }
    //public LLMAdapter getAdvancedLLM()  { return advancedLLM; }
    //public LLMAdapter getSchedulerLLM() { return SchedulerLLM; }
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
        while (TaskQueue.size() > ConfigsManager.MAX_TASK_AMOUNT) {
            // 队列积压时，遍历找到优先级数值最大（即优先级最低）的任务进行移除
            DefaultAgentTaskUnit lowestPriorityTask = null;
            for (DefaultAgentTaskUnit t : TaskQueue) {
                if (lowestPriorityTask == null || t.getPriority() > lowestPriorityTask.getPriority()) {
                    lowestPriorityTask = t;
                }
            }
            if (lowestPriorityTask != null) {
                TaskQueue.remove(lowestPriorityTask);
                log.info("[TaskQueue] 队列积压，已抛弃最低优先级的任务: {} (Priority: {})",
                        lowestPriorityTask.getClass().getSimpleName(), lowestPriorityTask.getPriority());
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
                    // 使用 poll 拿取队列头部任务（因为构造器传了升序比较器，所以拿到的必定是优先级数值最小的，即最高优任务）
                    DefaultAgentTaskUnit task = TaskQueue.poll(1, TimeUnit.SECONDS);
                    if (task == null) {
                        continue;
                    }


                    log.info("\n[执行总线] 开始处理任务: {}", task.getClass().getSimpleName());


                    ArrayNode toolsDefinitionArray = mapper.createArrayNode();
                    // 对工具箱做快照，避免迭代期间被插件系统并发修改
                    for (DefaultAgentToolUnit tool : new ArrayList<>(largeLLMToolbox.values())) {
                        if(tool.isAutoLoad()){
                            toolsDefinitionArray.add(tool.getToolDefinition());
                        }
                    }

                    //Map<String, Object> baseData = new HashMap<>();

                    DefaultAgentTaskHandler handler = taskHandlerRegistry.get(task.getClass());

                    if (handler != null) {
                        handler.handleTask(task, LivingLoop.this, toolsDefinitionArray);
                        processedTaskCount.incrementAndGet();
                    } else {
                        log.warn("[执行总线] 遇到未知的任务类型 [{}], 且没有挂载对应的 Handler，已跳过处理。", task.getClass().getSimpleName());
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

        // 【修复点 1】：动态决定使用哪个模型（支持高级模型切换）
        LLMAdapter llm = DefaultLLM;
        int turn = taskUnit.getCurrentTurn();

        log.info("[EXEC-Engine] [{}] 正在进行第 {} 轮深度思考与动作执行...", taskDesc, turn);

        Map<String, Object> turnData = new HashMap<>(baseData);
        // 注入之前的思考轨迹
        turnData.put("turnsAddition", taskUnit.getTurnsAddition());

        CallResult result;

        StringBuilder currentMemory = new StringBuilder();
        currentMemory.append(Utils.getNowPrecise() + "时,\n");
        //储存本轮任务处理中所有需要被短期记忆记录的东西

        if (turn == 1) {
            if(lastSolvingTask != null){
                //这说明这个任务插队了
                currentMemory.append("上一轮执行的任务被挂起, " + taskUnit.getTaskName() + " 因判断后的执行权重更高被优先处理...\n");
            } else {
                currentMemory.append(taskUnit.getTaskName() + " 开始被处理...\n");
            }
            //MemoryManager.getInstance().inputCurrentMemorys(t);
            lastSolvingTask = taskUnit;
            if(scenePrompts.getThinkingPrompt() != null && !scenePrompts.getThinkingPrompt().isEmpty() && !scenePrompts.getThinkingPrompt().equals("")) {
                result = LLManager.executeScene(scenePrompts.getThinkingPrompt(), turnData, llm, toolsDefinitionArray);
            } else {
                result = LLManager.executeScene(scenePrompts.getSolvingPrompt(), turnData, llm, toolsDefinitionArray);
            }
        } else {
            result = LLManager.executeScene(scenePrompts.getSolvingPrompt(), turnData, llm, toolsDefinitionArray);
            currentMemory.append("之前的 " + taskUnit.getTaskName() + " 正在进行第" + turn + "轮处理...\n");
        }
        StringBuilder nowTurnAddition = new StringBuilder();
        nowTurnAddition.append(taskUnit.getTurnsAddition());//把之前的工具调用结果压入

        nowTurnAddition.append("在任务 " + taskUnit.getTaskName() + " 的第" + turn + "轮思考中，");
        if(result.getContent() != null && !result.getContent().isEmpty() && !result.getContent().equals("") && !result.getContent().equals(" ")) {
            nowTurnAddition.append("你产生了以下想法:{\n");
            nowTurnAddition.append(result.getContent());
            nowTurnAddition.append("\n};\n");
            currentMemory.append("你的想法是: \"" + result.getContent() + "\";\n");
        }

        // 如果没有调用任何工具，直接判定闭环
        if (!result.isToolCall() || result.getToolCalls() == null || !result.getToolCalls().isArray() || result.getToolCalls().isEmpty()) {
            log.info("[EXEC-Engine] 💤 模型选择不采取物理动作，输出文本闭环: \n{}", result.getContent());
            currentMemory.append("你在本轮处理中没有调用任何工具。");
            nowTurnAddition.append("你没有调用任何工具;\n");
            nowTurnAddition.append("（也许你该结束这次任务了？或是自己一时抽抽，忘记把工具调用JSON错误地放到了文本栏一起输出了？自行判断吧）\n");
            taskUnit.setTurnsAddition(nowTurnAddition.toString());
            taskUnit.setCurrentTurn(turn + 1); // 向前推进一步！
            if (taskUnit.getCurrentTurn() > ConfigsManager.CONSUMER_CYCLING_TIME) {
                log.warn("[EXEC-Engine] 任务执行达到 {} 轮上限，防死循环，强制结束。", ConfigsManager.CONSUMER_CYCLING_TIME);
                currentMemory.append("……由于任务处理循环到上限了，这个任务被强制结束了……");
                lastSolvingTask = null;

                MemoryManager.getInstance().inputCurrentMemory(currentMemory.toString());
                return null; // 超出轮数，销毁
            }

            MemoryManager.getInstance().inputCurrentMemory(currentMemory.toString());
            return taskUnit; // 返回更新后的任务，准备重新入队
        }


        StringBuilder toolResults = new StringBuilder("\n\n【第 " + turn + " 轮工具观察结果】:\n");

        for (JsonNode toolCall : result.getToolCalls()) {
            String functionName = toolCall.path("function").path("name").asText();
            String argumentsStr = toolCall.path("function").path("arguments").asText();

            log.info("[EXEC-Engine] 决定采取动作: [{}]", functionName);

            if ("switch_to_advanced_model".equals(functionName)) {
                log.info("[EXEC-Engine] 收到升维请求，下一轮思考将切换至高级大模型。");
                //没想好怎么写
                toolResults.append("（调用了工具switch_to_advanced_model,切换到了更高级的大模型;）\n");
                continue;
            }

            DefaultAgentToolUnit targetTool = largeLLMToolbox.get(functionName);
            if (targetTool != null) {
                try {
                    JsonNode argsNode = mapper.readTree(argumentsStr);
                    String execResult = targetTool.execute(argsNode);
                    log.info("[EXEC-Engine] 动作反馈: {}", execResult);

                    toolResults.append("调用了工具 [").append(functionName).append("], 返回了 [").append(execResult).append("];\n");

                    if(targetTool.isAutoMemory()){
                        currentMemory.append("调用了工具 [").append(functionName).append("], 返回了 [").append(execResult).append("];\n");
                        //MemoryManager.getInstance().inputCurrentMemorys(list);
                    }

                } catch (Exception e) {
                    log.error("[EXEC-Engine] 工具解析或执行异常", e);
                    toolResults.append("调用了工具 [").append(functionName).append("] , 却发生了发生程序错误:[\n" + e.toString() + "\n];\n");
                }
            } else {
                toolResults.append("调用了工具 [").append(functionName).append("] , 但是这个工具压根不存在;\n");
            }

            if ("finish_task".equals(functionName)) {
                log.info("[EXEC-Engine] 捕捉到 finish_task 工具调用，模型主动判定任务完成。");
                lastSolvingTask = null;

                MemoryManager.getInstance().inputCurrentMemory(currentMemory.toString());
                return null; // 直接终结任务
            }

        }

        // 【修复点 3】：推进轮数，并检查最大循环限制
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