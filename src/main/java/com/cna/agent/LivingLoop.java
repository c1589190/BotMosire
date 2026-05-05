package com.cna.agent;

import com.cna.agent.AgentInput.ChatMessageInput;
import com.cna.agent.AgentInput.DefaultAgentInputUnit;
import com.cna.agent.AgentInputHandlers.ChatMessageInputHandler;
import com.cna.agent.AgentInputHandlers.DefaultAgentInputHandler;
import com.cna.agent.AgentTask.DefaultAgentTaskUnit;
import com.cna.agent.AgentTask.ChatTask;
import com.cna.agent.AgentTask.ScheduledTask;
import com.cna.agent.AgentTask.UpdateThoughtsTask;
import com.cna.Main;
import com.cna.Utils;
import com.cna.agent.AgentTasksHandlers.*;
import com.cna.agent.AgentTool.*;
import com.cna.config.ConfigsManager;
import com.cna.llm.LLManager;
import com.cna.db.MemoryManager;
import com.cna.plugin.MosireAPI;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.cna.db.MDManager;
import com.cna.llm.LLMAdapter;
import com.cna.llm.CallResult;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class LivingLoop implements MosireAPI {
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
            1145, Comparator.comparingInt(DefaultAgentTaskUnit::getPriority)
    );

    private LinkedHashMap<String, ChatTask> ChatTaskPreparationPool = new LinkedHashMap<>();

    private final Map<Class<? extends DefaultAgentTaskUnit>, DefaultAgentTaskHandler> taskHandlerRegistry = new ConcurrentHashMap<>();

    private final Map<Class<? extends DefaultAgentInputUnit>, DefaultAgentInputHandler> inputHandlerRegistry = new ConcurrentHashMap<>();

    private LLMAdapter littleLLM;
    private LLMAdapter largeLLM;
    private LLMAdapter SchedulerLLM;
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
        largeLLMToolbox.put(switchModelTool.getName(), switchModelTool);
        largeLLMToolbox.put(new GetInterests().getName(), new GetInterests());

        this.registerTool(new GetMoreCurrentMemorys());
        this.registerTool(new QueryDeepMemory());
        this.registerTool(new ReflectiveCompactionTool());
        this.registerTool(new SendConsoleMessage());


        log.info("[LivingLoop] 大模型默认工具箱装配完毕，已挂载工具数: {}", largeLLMToolbox.size());

        this.registerTaskHandler(new ChatTaskHandler());            // 常规聊天
        this.registerTaskHandler(new ScheduledTaskHandler());       // 定时计划
        this.registerTaskHandler(new UpdateThoughtsTaskHandler());  // 潜意识反思

        this.registerInputHandler(new ChatMessageInputHandler());
        this.registerTaskHandler(new ConsoleChatTaskHandler());
    }

    private void initLLM(){
        this.littleLLM = new LLMAdapter(ConfigsManager.GATEKEEPER_CONFIG);
        this.largeLLM = new LLMAdapter(ConfigsManager.BRAIN_CONFIG);
        this.SchedulerLLM = new LLMAdapter(ConfigsManager.SCHEDULER_CONFIG);
        this.embLLM = new LLMAdapter(ConfigsManager.EMBEDDING_CONFIG);
    }

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
    public void registerInputHandler(DefaultAgentInputHandler handler) {
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

    @Override
    public Logger getLogger() {
        return log;
    }

    private void trimTaskQueue() {
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

                // 【定时反思任务】
                if (processedTaskCount.get() >= ConfigsManager.TASK_COUNT_FOR_REFLECTION) {
                    processedTaskCount.set(0);
                    log.info("[System] 达到任务处理阈值，正在向潜意识抛入强制反思任务...");
                    TaskQueue.offer(new UpdateThoughtsTask()); // 变更为 offer
                    this.trimTaskQueue();
                }

                this.scheduledTaskCounter ++;

                // 【定时计划任务】
                if (this.scheduledTaskCounter >= ConfigsManager.SCHEDULE_CYCLING_TIME) {
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
                    // 【修改点 4】：使用 poll 拿取队列头部任务（因为构造器传了升序比较器，所以拿到的必定是优先级数值最小的，即最高优任务）
                    DefaultAgentTaskUnit task = TaskQueue.poll(1, TimeUnit.SECONDS);
                    if (task == null) {
                        continue;
                    }

                    log.info("\n[执行总线] 开始处理任务: {}", task.getClass().getSimpleName());


                    ArrayNode toolsDefinitionArray = mapper.createArrayNode();
                    for (DefaultAgentToolUnit tool : largeLLMToolbox.values()) {
                        if(tool.isAutoLoad()){
                            toolsDefinitionArray.add(tool.getToolDefinition());
                        }
                    }

                    ObjectNode finishTool = mapper.createObjectNode();
                    finishTool.put("type", "function");
                    ObjectNode finishFunction = finishTool.putObject("function");
                    finishFunction.put("name", "finish_task");
                    finishFunction.put("description", "当你认为当前任务已经完成所有需要干的事，下一轮不需要进行任何其他行动时，调用此工具以立刻结束思考循环。");
                    finishFunction.putObject("parameters").put("type", "object");
                    toolsDefinitionArray.add(finishTool);

                    Map<String, Object> baseData = new HashMap<>();

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

    public void executeCognitiveCycle(
            String sceneName,
            String thinkingSceneName,
            Map<String, Object> baseData,
            LLMAdapter DefaultLLM,
            ArrayNode toolsDefinitionArray,
            String taskDesc) {
        String turnsAddition = "";
        ObjectMapper mapper = new ObjectMapper();
        LLMAdapter llm = DefaultLLM;

        log.info("[EXEC-Engine] [{}] 正在进行第 0 轮预思考 (战略规划与任务拆解)...", taskDesc);
        Map<String, Object> turn0Data = new HashMap<>(baseData);

        String toolsDescString = "";
        try {
            toolsDescString = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(toolsDefinitionArray);
        } catch (Exception e) {
            log.warn("序列化工具列表失败", e);
        }

        turn0Data.put("available_tools", toolsDescString);

        if(!Objects.equals(thinkingSceneName, "")){
            try {
                CallResult planResult = LLManager.executeScene(
                        MDManager.read("prompts/" + thinkingSceneName + ".md"),
                        turn0Data,
                        llm,
                        null
                );

                String planContent = planResult.getContent();
                log.info("[EXEC-Engine] 💡 第 0 轮预思考完毕，生成战略路线图: \n{}", planContent);

                turnsAddition += "任务前期规划: [\n" + planContent + "\n];\n";

            } catch (Exception e) {
                log.warn("[EXEC-Engine] 第 0 轮预思考发生异常，将降级直接进入动作循环。", e);
            }
        }

        for (int turn = 1; turn <= ConfigsManager.CONSUMER_CYCLING_TIME; turn++) {
            log.info("[EXEC-Engine] [{}] 正在进行第 {} 轮深度思考与动作执行...", taskDesc, turn);

            Map<String, Object> turnData = new HashMap<>(baseData);
            turnData.put("turnsAddition", turnsAddition);

            CallResult result = LLManager.executeScene(
                    MDManager.read("prompts/" + sceneName + ".md"),
                    turnData,
                    llm,
                    toolsDefinitionArray
            );

            if (!result.isToolCall() || result.getToolCalls() == null || !result.getToolCalls().isArray() || result.getToolCalls().isEmpty()) {
                log.info("[EXEC-Engine] 💤 模型选择不采取物理动作，输出文本闭环: \n{}", result.getContent());
                break;
            }

            StringBuilder toolResults = new StringBuilder("\n\n【第 " + turn + " 轮工具观察结果】:\n");
            boolean hasFinalAction = false;

            for (JsonNode toolCall : result.getToolCalls()) {
                String functionName = toolCall.path("function").path("name").asText();
                String argumentsStr = toolCall.path("function").path("arguments").asText();

                log.info("[EXEC-Engine] 决定采取动作: [{}]", functionName);

                if ("finish_task".equals(functionName)) {
                    log.info("[EXEC-Engine] 捕捉到 finish_task 工具调用，模型主动判定任务完成。");
                    hasFinalAction = true;
                    continue;
                }
                if ("switch_to_advanced_model".equals(functionName)) {
                    log.info("[EXEC-Engine] 下一轮思考将切换至高级大模型。");
                    llm = new LLMAdapter(ConfigsManager.ADVANCED_BRAIN_CONFIG);
                }

                DefaultAgentToolUnit targetTool = largeLLMToolbox.get(functionName);
                if (targetTool != null) {
                    try {
                        JsonNode argsNode = mapper.readTree(argumentsStr);
                        String execResult = targetTool.execute(argsNode);
                        log.info("[EXEC-Engine] 动作反馈: {}", execResult);

                        toolResults.append("调用工具 [").append(functionName).append("] 返回 [").append(execResult).append("];\n");

                        if(targetTool.isAutoMemory()){
                            List<String> list = new LinkedList<>();
                            list.add(Utils.getNowFormatted() + "," + targetTool.getTextRecord());
                            new MemoryManager().inputCurrentMemorys(list);
                        }

                    } catch (Exception e) {
                        log.error("[EXEC-Engine] 工具解析或执行异常", e);
                        toolResults.append("调用工具 [").append(functionName).append("] 发生程序错误;\n");
                    }
                } else {
                    toolResults.append("系统警告：工具 [").append(functionName).append("] 调用失败，该工具不存在。\n");
                    log.warn("[EXEC-Engine] 严重幻觉：模型试图调用不存在的工具: {}", functionName);
                }
            }

            if (hasFinalAction) {
                log.info("[EXEC-Engine] 终结指令已下达，思考回路闭合。");
                break;
            } else {
                log.info("[EXEC-Engine] 获取到观察线索，转入下一轮思考...");
                turnsAddition += "在第 " + turn + " 轮思考中，你获得了以下信息：\n" + toolResults.toString() + "\n";
            }
        }
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
                DefaultAgentInputHandler handler = inputHandlerRegistry.get(entry.getKey());
                if (handler != null) {
                    handler.handleInputs(entry.getValue(), this);
                } else {
                    log.warn("[CognitiveCycle] 收到未知的 Input 类型: {}，已丢弃", entry.getKey().getSimpleName());
                }
            }
        }

        for (DefaultAgentInputHandler handler : inputHandlerRegistry.values()) {
            handler.tick(this);
        }
    }

    public void stop() {
        log.info("[BrainLoop] 收到停机指令，正在关闭心跳引擎...");
        scheduler.shutdown();
        executorService.shutdown();
        try {
            if (!scheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
            if (!executorService.awaitTermination(2, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            executorService.shutdownNow();
        }
    }
}