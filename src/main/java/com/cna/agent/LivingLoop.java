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

    // 新增：Gatekeeper (小模型) 专用异步线程池，防止网络请求阻塞心跳总线
    private final ExecutorService gatekeeperExecutor = Executors.newSingleThreadExecutor();

    // 新增：Gatekeeper 状态锁。保证小模型一次只专注思考一批消息
    private final AtomicBoolean isGatekeeperThinking = new AtomicBoolean(false);

    // 累加器：跨线程安全的任务处理计数器，用于触发定期反思
    private final AtomicInteger processedTaskCount = new AtomicInteger(0);

    private BlockingDeque<DefaultAgentTaskUnit> TaskQueue = new LinkedBlockingDeque<>();
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

    /**
     * 供外部插件动态注册新工具
     * @param tool 自定义的 Agent 工具
     */
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

    /**
     * 供外部插件在卸载时注销工具
     * @param toolName 工具的名称
     */
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
    // 开放注册接口给插件
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

    // 开放一个方法，让 Handler 催熟后能把 Task 塞进主队列
    @Override
    public void pushTask(DefaultAgentTaskUnit task) {
        this.TaskQueue.offerLast(task);
        this.trimTaskQueue();
    }

    @Override
    public Logger getLogger() {
        return log;
    }

    private void trimTaskQueue() {
        while (TaskQueue.size() > ConfigsManager.MAX_TASK_AMOUNT) {
            // 注意这里变成了 pollFirst！因为现在队首是最老的
            DefaultAgentTaskUnit droppedTask = TaskQueue.pollFirst();
            if (droppedTask != null) {
                log.info("[TaskQueue] 队列积压，已抛弃最老的任务: {}", droppedTask.getClass().getSimpleName());
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
                    // 【核心修改】：尝试加锁。如果小模型当前没在思考，就进去。如果在思考，就跳过。
                    if (isGatekeeperThinking.compareAndSet(false, true)) {
                        // 把它扔到专属线程里去执行，让心跳线程立刻返回，继续读秒！
                        gatekeeperExecutor.submit(() -> {
                            try {
                                handleCognitiveCycle();
                            } finally {
                                // 思考完毕，无论成功报错，必定释放锁
                                isGatekeeperThinking.set(false);
                            }
                        });
                    }
                    // else {
                    //     如果它还在思考，这里什么都不做。
                    //     新来的 QQ 消息会自动堆积在 Main.AgentInputTasksQueue 中，
                    //     等它下次释放锁之后，再一次性全部吸干处理，完美符合人类处理消息的逻辑。
                    // }
                    this.tickCounter_CognitiveCycle = 0;
                }

                // 【定时反思任务】检查处理总量，达标则抛入系统反思任务
                if (processedTaskCount.get() >= ConfigsManager.TASK_COUNT_FOR_REFLECTION) {
                    processedTaskCount.set(0); // 瞬间归零，重新计数
                    log.info("[System] 达到任务处理阈值，正在向潜意识抛入强制反思任务...");
                    TaskQueue.offerLast(new UpdateThoughtsTask());
                    this.trimTaskQueue(); // 塞完立马修剪
                }

                this.scheduledTaskCounter ++;

                if (this.scheduledTaskCounter >= ConfigsManager.SCHEDULE_CYCLING_TIME) {
                    log.info("[System] 达到定时任务阈值，正在向队列抛入定时任务...");
                    TaskQueue.offerLast(new ScheduledTask());
                    this.trimTaskQueue(); // 塞完立马修剪
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
                    // 从队尾（最新端）拿取任务
                    DefaultAgentTaskUnit task = TaskQueue.pollLast(1, TimeUnit.SECONDS);
                    if (task == null) {
                        continue;
                    }

                    log.info("\n[执行总线] 开始处理任务: {}", task.getClass().getSimpleName());


                    //自动装配所有Tool
                    ArrayNode toolsDefinitionArray = mapper.createArrayNode();
                    for (DefaultAgentToolUnit tool : largeLLMToolbox.values()) {
                        if(tool.isAutoLoad()){
                            toolsDefinitionArray.add(tool.getToolDefinition());
                        }
                    }

                    // ==========================================
                    // 注入终结工具 (赋予模型主动刹车的能力)
                    // ==========================================
                    ObjectNode finishTool = mapper.createObjectNode();
                    finishTool.put("type", "function");
                    ObjectNode finishFunction = finishTool.putObject("function");
                    finishFunction.put("name", "finish_task");
                    finishFunction.put("description", "当你认为当前任务已经完成所有需要干的事，下一轮不需要进行任何其他行动时，调用此工具以立刻结束思考循环。");
                    finishFunction.putObject("parameters").put("type", "object");
                    toolsDefinitionArray.add(finishTool);

                    // 准备基础数据容器
                    Map<String, Object> baseData = new HashMap<>();

                    // ==========================================
                    // 任务分发与执行 (动态策略路由)
                    // ==========================================
                    DefaultAgentTaskHandler handler = taskHandlerRegistry.get(task.getClass());

                    if (handler != null) {
                        // 找到了对应的处理器，直接移交执行权！
                        handler.handleTask(task, LivingLoop.this, toolsDefinitionArray);

                        // 全局处理计数器 +1
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

    /**
     * 通用实践引擎 (支持多轮、每轮多工具并发、自主中断)
     * 处理各类任务的地方
     */
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
        //使用默认循环模型

        // ==========================================
        // 第 0 轮 - 强制战略规划阶段 (Plan)
        // ==========================================

        log.info("[EXEC-Engine] [{}] 正在进行第 0 轮预思考 (战略规划与任务拆解)...", taskDesc);
        Map<String, Object> turn0Data = new HashMap<>(baseData);

        // 1. 将工具数组格式化为美观的 JSON 字符串
        String toolsDescString = "";
        try {
            toolsDescString = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(toolsDefinitionArray);
        } catch (Exception e) {
            log.warn("序列化工具列表失败", e);
        }

        // 2. 将工具字符串注入到模版数据中
        turn0Data.put("available_tools", toolsDescString);

        if(!Objects.equals(thinkingSceneName, "")){
            try {
                // 关键：第四个参数依然传 null，剥夺物理调用能力，只让它“看”工具
                CallResult planResult = LLManager.executeScene(
                        MDManager.read("prompts/" + thinkingSceneName + ".md"),
                        turn0Data,
                        llm,
                        null
                );

                String planContent = planResult.getContent();
                log.info("[EXEC-Engine] 💡 第 0 轮预思考完毕，生成战略路线图: \n{}", planContent);

                // 将生成的规划作为记忆前缀，永久烙印在后续正式执行轮次的 turnsAddition 中
                turnsAddition += "任务前期规划: [\n" + planContent + "\n];\n";

            } catch (Exception e) {
                log.warn("[EXEC-Engine] 第 0 轮预思考发生异常，将降级直接进入动作循环。", e);
            }
        }

        // ==========================================
        // 正式动作执行循环阶段 (Solve)
        // ==========================================
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

            // 中断条件 1：模型输出纯文本，未调用任何工具 (自然闭环)
            if (!result.isToolCall() || result.getToolCalls() == null || !result.getToolCalls().isArray() || result.getToolCalls().isEmpty()) {
                log.info("[EXEC-Engine] 💤 模型选择不采取物理动作，输出文本闭环: \n{}", result.getContent());
                break;
            }

            StringBuilder toolResults = new StringBuilder("\n\n【第 " + turn + " 轮工具观察结果】:\n");
            boolean hasFinalAction = false;

            // 循环处理本轮中调用的工具类
            for (JsonNode toolCall : result.getToolCalls()) {
                String functionName = toolCall.path("function").path("name").asText();
                String argumentsStr = toolCall.path("function").path("arguments").asText();

                log.info("[EXEC-Engine] 决定采取动作: [{}]", functionName);

                // 中断条件 2：模型主动调用了结束工具 (强制闭环)
                if ("finish_task".equals(functionName)) {
                    log.info("[EXEC-Engine] 捕捉到 finish_task 工具调用，模型主动判定任务完成。");
                    hasFinalAction = true;
                    continue; // 这是一个虚拟工具，跳过物理执行
                }
                if ("switch_to_advanced_model".equals(functionName)) {
                    log.info("[EXEC-Engine] 下一轮思考将切换至高级大模型。");
                    llm = new LLMAdapter(ConfigsManager.ADVANCED_BRAIN_CONFIG);
                }

                // 物理执行常规工具
                DefaultAgentToolUnit targetTool = largeLLMToolbox.get(functionName);
                if (targetTool != null) {
                    try {
                        JsonNode argsNode = mapper.readTree(argumentsStr);
                        String execResult = targetTool.execute(argsNode);
                        log.info("[EXEC-Engine] 动作反馈: {}", execResult);

                        toolResults.append("调用工具 [").append(functionName).append("] 返回 [").append(execResult).append("];\n");

                        // 归档动作记忆
                        List<String> list = new LinkedList<>();
                        list.add(Utils.getNowFormatted() + "," + targetTool.getTextRecord());
                        new MemoryManager().inputCurrentMemorys(list);

                    } catch (Exception e) {
                        log.error("[EXEC-Engine] 工具解析或执行异常", e);
                        toolResults.append("调用工具 [").append(functionName).append("] 发生程序错误;\n");
                    }
                } else {
                    toolResults.append("系统警告：工具 [").append(functionName).append("] 调用失败，该工具不存在。\n");
                    log.warn("[EXEC-Engine] 严重幻觉：模型试图调用不存在的工具: {}", functionName);
                }
            }

            // 评估是否跳出循环
            if (hasFinalAction) {
                log.info("[EXEC-Engine] 终结指令已下达，思考回路闭合。");
                break;
            } else {
                log.info("[EXEC-Engine] 获取到观察线索，转入下一轮思考...");
                turnsAddition += "在第 " + turn + " 轮思考中，你获得了以下信息：\n" + toolResults.toString() + "\n";
            }
        }
    }

    /**
     * 认知周期处理
     * 让小模型判断兴趣、为之前添加过的聊天信息增加信息的地方
     */
    private void handleCognitiveCycle() {
        // 1. 瞬间抽干当前感官缓冲池中的所有消息
        List<DefaultAgentInputUnit> currentBatch = new ArrayList<>();
        Main.AgentInputTasksQueue.drainTo(currentBatch);

        if (!currentBatch.isEmpty()) {
            log.info("[CognitiveCycle] 触发认知觉醒，捕获到 {} 条待处理的感知输入", currentBatch.size());

            // 将混合的 Input 按类型分组
            Map<Class<? extends DefaultAgentInputUnit>, List<DefaultAgentInputUnit>> groupedInputs = new HashMap<>();
            for (DefaultAgentInputUnit input : currentBatch) {
                groupedInputs.computeIfAbsent(input.getClass(), k -> new ArrayList<>()).add(input);
            }

            // 路由分发给对应的 Handler 处理
            for (Map.Entry<Class<? extends DefaultAgentInputUnit>, List<DefaultAgentInputUnit>> entry : groupedInputs.entrySet()) {
                DefaultAgentInputHandler handler = inputHandlerRegistry.get(entry.getKey());
                if (handler != null) {
                    handler.handleInputs(entry.getValue(), this);
                } else {
                    log.warn("[CognitiveCycle] 收到未知的 Input 类型: {}，已丢弃", entry.getKey().getSimpleName());
                }
            }
        }

        // 2. 触发所有已注册感知处理器的 tick (催熟心跳)
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