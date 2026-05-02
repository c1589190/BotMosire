package com.cna;

import com.cna.AgentInput.DefaultAgentInputUnit;
import com.cna.AgentInput.NapcatQQInput.QQGroupMessageInput;
import com.cna.AgentInput.NapcatQQInput.QQPrivateMessageInput;
import com.cna.AgentTask.DefaultAgentTaskUnit;
import com.cna.AgentTask.QQChatTask;
import com.cna.AgentTask.ScheduledTask;
import com.cna.AgentTask.UpdateThoughtsTask;
import com.cna.AgentTool.*;
import com.cna.config.ConfigsManager;
import com.cna.llm.LLManager;
import com.cna.db.MemoryManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.cna.db.MDManager;
import com.cna.llm.LLMAdapter;
import com.cna.llm.CallResult;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class LivingLoop {
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
    private LinkedHashMap<Long, QQChatTask> QQTaskPreparationPool = new LinkedHashMap<>();

    private LLMAdapter littleLLM;
    private LLMAdapter largeLLM;
    private LLMAdapter SchedulerLLM;
    private LLMAdapter embLLM;

    private final Map<String, DefaultAgentToolUnit> largeLLMToolbox = new HashMap<>();

    public LivingLoop() {
        DefaultAgentToolUnit privateMsgTool = new SendQQPrivateMessage();
        DefaultAgentToolUnit groupMsgTool = new SendQQGroupMessage();
        DefaultAgentToolUnit groupHisTool = new GetQQGroupHistory();
        DefaultAgentToolUnit privateHisTool = new GetQQPrivateHistory();
        DefaultAgentToolUnit updateInterestsTool = new UpdateInterests();
        DefaultAgentToolUnit updateThoughtsTool = new UpdateThoughts();
        DefaultAgentToolUnit updateScheduledTool = new UpdateScheduled();
        DefaultAgentToolUnit switchModelTool = new SwitchToAdvancedModel();

        largeLLMToolbox.put(privateMsgTool.getName(), privateMsgTool);
        largeLLMToolbox.put(groupMsgTool.getName(), groupMsgTool);
        largeLLMToolbox.put(groupHisTool.getName(), groupHisTool);
        largeLLMToolbox.put(privateHisTool.getName(), privateHisTool);
        largeLLMToolbox.put(updateThoughtsTool.getName(), updateThoughtsTool);
        largeLLMToolbox.put(updateInterestsTool.getName(), updateInterestsTool);
        largeLLMToolbox.put(updateScheduledTool.getName(), updateScheduledTool);
        largeLLMToolbox.put(new GetScheduled().getName(), new GetScheduled());
        largeLLMToolbox.put(switchModelTool.getName(), switchModelTool);
        largeLLMToolbox.put(new GetInterests().getName(), new GetInterests());
        log.info("[LivingLoop] 大模型工具箱装配完毕，已挂载工具数: {}", largeLLMToolbox.size());
    }

    private void initLLM(){
        this.littleLLM = new LLMAdapter(ConfigsManager.GATEKEEPER_CONFIG);
        this.largeLLM = new LLMAdapter(ConfigsManager.BRAIN_CONFIG);
        this.SchedulerLLM = new LLMAdapter(ConfigsManager.SCHEDULER_CONFIG);
        this.embLLM = new LLMAdapter(ConfigsManager.EMBEDDING_CONFIG);
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
                log.error("[com.cna.LivingLoop][SCHE] 任务生产循环异常：", e);
            }
        }, 1, 1, TimeUnit.MILLISECONDS);

        // ==========================================
        // 线程 2：大脑皮层深度思考与动作执行 (消费者)
        // ==========================================
        executorService.submit(() -> {
            log.info("[com.cna.LivingLoop][EXEC] 大脑皮层任务消费线程已启动...");
            ObjectMapper mapper = new ObjectMapper();

            while (!Thread.currentThread().isInterrupted()) {
                try {
                    // 从队尾（最新端）拿取任务
                    DefaultAgentTaskUnit task = TaskQueue.pollLast(1, TimeUnit.SECONDS);
                    if (task == null) {
                        continue;
                    }

                    log.info("\n[执行总线] 开始处理任务: {}", task.getClass().getSimpleName());

                    //String currentThoughts = MDManager.read("thoughts.md", "");

                    ArrayNode toolsDefinitionArray = mapper.createArrayNode();
                    for (DefaultAgentToolUnit tool : largeLLMToolbox.values()) {
                        toolsDefinitionArray.add(tool.getToolDefinition());
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
                    // 任务分发与执行
                    // ==========================================
                    switch (task) {
                        case QQChatTask qqChatTask -> {
                            baseData.put("taskText", task.getTaskText());
                            baseData.put("deep_memories", LLManager.getDeepMemories(task.getTaskText(), this.embLLM, ConfigsManager.MEMORY_DEPTH));

                            executeCognitiveCycle("LivingLoop_ConsumerCycle_solveQQChatTask", "LivingLoop_ConsumerCycle_thinkQQChatTask", baseData, this.largeLLM, toolsDefinitionArray, "常规聊天任务");

                            processedTaskCount.incrementAndGet();
                            log.info("========== 常规聊天任务处理完毕 ==========\n");
                        }
                        case UpdateThoughtsTask updateThoughtsTask -> {
                            baseData.put("taskText", task.getTaskText());
                            baseData.put("current_interests", MDManager.read("interests.md", ""));

                            executeCognitiveCycle("LivingLoop_CognitiveCycle_updateThoughts", "", baseData, this.largeLLM, toolsDefinitionArray, "系统级反思任务");

                            log.info("========== 系统反思任务处理完毕 ==========\n");
                        }
                        case ScheduledTask scheduledTask when !MDManager.read("scheduled.md", "").isEmpty() -> {
                            baseData.put("scheduled", MDManager.read("scheduled.md", ""));

                            executeCognitiveCycle("LivingLoop_CognitiveCycle_Scheduled", "", baseData, this.SchedulerLLM, toolsDefinitionArray, "定时计划任务");

                            log.info("========== 定时任务处理完毕 ==========\n");
                        }
                        default -> {
                        }
                    }

                } catch (InterruptedException e) {
                    log.info("[EXEC] 消费者线程收到中断信号，即将退出。");
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    log.error("[com.cna.LivingLoop][EXEC] 任务处理循环异常：", e);
                }
            }
        });
    }

    /**
     * 通用认知执行引擎 (支持多轮、每轮多工具并发、自主中断)
     */
    private void executeCognitiveCycle(String sceneName, String thinkingSceneName, Map<String, Object> baseData, LLMAdapter DefaultLLM, ArrayNode toolsDefinitionArray, String taskDesc) {
        String turnsAddition = "";
        ObjectMapper mapper = new ObjectMapper();
        LLMAdapter llm = DefaultLLM;
        //使用默认循环模型

        // ==========================================
        // 【新增】：第 0 轮 - 强制战略规划阶段 (Plan)
        // ==========================================
        log.info("[EXEC-Engine] [{}] 正在进行第 0 轮预思考 (战略规划与任务拆解)...", taskDesc);
        Map<String, Object> turn0Data = new HashMap<>(baseData);
        // 通过 turnsAddition 注入强制指令，要求模型分析局势并制定计划
        //turn0Data.put("user", "【系统强制指令】：当前为前期规划阶段。请仔细分析当前的任务文本、记忆以及系统设定。不要尝试输出任何工具调用的格式。请直接用自然语言输出一段详细的内心独白，分析目前的局势，理解用户的核心意图，并列出你接下来打算分几步、调用哪些工具来完美解决这个问题。");

        if(!Objects.equals(thinkingSceneName, "")){
            try {
                // 关键：传入空的工具数组 (emptyTools)，剥夺模型在这一轮调用工具的能力，逼迫它只能思考出字
                CallResult planResult = LLManager.executeScene(
                        thinkingSceneName,
                        turn0Data,
                        llm,
                        "CORE.md",
                        null
                );

                String planContent = planResult.getContent();
                log.info("[EXEC-Engine] 💡 第 0 轮预思考完毕，生成战略路线图: \n{}", planContent);

                // 将生成的规划作为记忆前缀，永久烙印在后续正式执行轮次的 turnsAddition 中
                turnsAddition += "任务前期规划: [\n" + planContent + "\n];";

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
                    sceneName,
                    turnData,
                    llm,
                    "CORE.md",
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

    private static Map<String, Object> getTurn0Data(Map<String, Object> turn0Data) {
        return turn0Data;
    }

    /**
     * 认知周期处理
     */
    private void handleCognitiveCycle() {
        // 1. 瞬间抽干当前感官缓冲池中的所有消息
        List<DefaultAgentInputUnit> currentBatch = new ArrayList<>();
        Main.AgentInputTasksQueue.drainTo(currentBatch);

        // 记录在这一轮中，哪些 QQ 的任务被更新了
        Set<Long> updatedQQIds = new HashSet<>();
        if (!currentBatch.isEmpty()) {
            log.info("[CognitiveCycle] 触发认知觉醒，捕获到 {} 条待处理的感知输入", currentBatch.size());

            List<DefaultAgentInputUnit> unknownInputs = new ArrayList<>();

            // ==========================================
            // 阶段 1：本地极速分拣
            // ==========================================
            for (DefaultAgentInputUnit input : currentBatch) {
                QQChatTask existingQQTask;
                long senderId = 0;
                if(input instanceof QQGroupMessageInput){
                    senderId = Long.parseLong(((QQGroupMessageInput) input).getSenderID());
                } else if(input instanceof QQPrivateMessageInput){
                    senderId = Long.parseLong(((QQPrivateMessageInput) input).getSenderID());
                }
                if (QQTaskPreparationPool.containsKey(senderId)) {
                    existingQQTask = QQTaskPreparationPool.get(senderId);
                    existingQQTask.addContext(input.getInputText());
                    updatedQQIds.add(senderId);
                    List<String> l = new LinkedList<>();
                    l.add("为自己创建了任务，有关于 [ " + input.getInputText() + " ]");
                    new MemoryManager().inputCurrentMemorys(l);
                    log.debug("为已有任务 [QQ:{}] 追加了新消息", senderId);
                } else {
                    unknownInputs.add(input);
                }
            }

            // ==========================================
            // 阶段 2：小模型审查新面孔
            // ==========================================
            if (!unknownInputs.isEmpty()) {
                log.info("[Gatekeeper] 审查input……");
                StringBuilder currentInputs = new StringBuilder();
                for (int i = 0; i < unknownInputs.size(); i++) {
                    currentInputs.append(i).append(": { ");
                    currentInputs.append(unknownInputs.get(i).getInputText());
                    currentInputs.append(" }\n");
                }

                log.info("[Gatekeeper] 正在批量审阅 {} 条全新消息...", unknownInputs.size());

                Map<String, Object> data = new HashMap<>();
                data.put("currentInputs", currentInputs.toString());
                data.put("current_interests", MDManager.read("interests.md", ""));
                CallResult result = LLManager.executeScene(
                        "LivingLoop_CognitiveCycle_getInterest",
                        data,
                        this.littleLLM,
                        "CORE.md",
                        buildAttentionToolDefinition()
                );

                List<DefaultAgentInputUnit> interestingInputs = new ArrayList<>();
                if (result.isToolCall() && result.getToolCalls() != null && result.getToolCalls().isArray()) {
                    JsonNode firstToolCall = result.getToolCalls().get(0);
                    if ("submit_attention_list".equals(firstToolCall.path("function").path("name").asText())) {
                        String argumentsStr = firstToolCall.path("function").path("arguments").asText();
                        try {
                            ObjectMapper mapper = new ObjectMapper();
                            JsonNode argsNode = mapper.readTree(argumentsStr);
                            JsonNode indicesArray = argsNode.path("selected_indices");

                            if (indicesArray.isArray() && !indicesArray.isEmpty()) {
                                log.info("[Gatekeeper] 命中！提取到高价值信号索引: {}", indicesArray.toString());
                                for (JsonNode indexNode : indicesArray) {
                                    int index = indexNode.asInt();
                                    if (index >= 0 && index < unknownInputs.size()) {
                                        interestingInputs.add(unknownInputs.get(index));
                                    }
                                }
                            } else {
                                log.info("[Gatekeeper] 全盘否定，判定所有新消息均为噪音。");
                            }
                        } catch (Exception e) {
                            log.error("[Gatekeeper] 解析 JSON 崩溃: {}", argumentsStr, e);
                        }
                    }
                } else {
                    log.warn("[Gatekeeper] 未触发工具，产生了非标输出: {}", result.getContent());
                }

                // 处理被小模型放行的消息
                for (DefaultAgentInputUnit input : interestingInputs) {
                    long senderId = -1;
                    String text = "";

                    if (input instanceof QQGroupMessageInput) {
                        senderId = Long.parseLong(((QQGroupMessageInput) input).getSenderID());
                        text = input.getInputText();
                    } else if (input instanceof QQPrivateMessageInput) {
                        senderId = Long.parseLong(((QQPrivateMessageInput) input).getSenderID());
                        text = input.getInputText();
                    }

                    if (senderId != -1) {
                        if (QQTaskPreparationPool.containsKey(senderId)) {
                            QQTaskPreparationPool.get(senderId).addContext(text);
                            List<String> l = new LinkedList<>();
                            l.add(text);
                            new MemoryManager().inputCurrentMemorys(l);
                            log.info("小模型判定有价值，为当前批次的新目标 [QQ:{}] 合并追加了连贯消息", senderId);
                        } else {
                            QQChatTask task = new QQChatTask(senderId);
                            task.addContext(text);
                            QQTaskPreparationPool.put(senderId, task);
                            List<String> l = new LinkedList<>();
                            l.add(text);
                            new MemoryManager().inputCurrentMemorys(l);
                            log.info("小模型判定有价值，为新目标 [QQ:{}] 创建了预备任务", senderId);
                        }
                        updatedQQIds.add(senderId);
                    }
                }

                // ==========================================
                // 阶段 2.5：无聊打发时间 (随机捞回被拦截的消息)
                // ==========================================
                // 如果本轮没有任何消息被处理（意味着所有的未知消息都被门卫拦截了，且没有旧任务）
                if (updatedQQIds.isEmpty()) {
                    // 找出所有被小模型抛弃的垃圾消息
                    List<DefaultAgentInputUnit> rejectedInputs = new ArrayList<>(unknownInputs);
                    rejectedInputs.removeAll(interestingInputs);

                    if (!rejectedInputs.isEmpty()) {
                        // 设置“无聊打捞”的概率，比如 10% (0.1)
                        double salvageChance = 0.1;

                        if (Math.random() < salvageChance) {
                            log.info("[CognitiveCycle] 💤 系统闲得发慌，决定从垃圾桶里捞一条消息随便回回...");

                            // 随机挑一条被拦截的消息
                            DefaultAgentInputUnit luckyInput = rejectedInputs.get(new java.util.Random().nextInt(rejectedInputs.size()));

                            long senderId = -1;
                            String text = "";

                            if (luckyInput instanceof QQGroupMessageInput) {
                                senderId = Long.parseLong(((QQGroupMessageInput) luckyInput).getSenderID());
                                text = luckyInput.getInputText();
                            } else if (luckyInput instanceof QQPrivateMessageInput) {
                                senderId = Long.parseLong(((QQPrivateMessageInput) luckyInput).getSenderID());
                                text = luckyInput.getInputText();
                            }

                            if (senderId != -1) {
                                QQChatTask task = new QQChatTask(senderId);

                                // 【精髓注入】：给大脑模型加戏，让它知道自己为什么要回这条消息
                                String innerMonologue = "【系统环境注入】：这条消息 [ " + text + " ] 原本不在你的兴趣雷达内，你觉得它是废话。但是因为你现在实在太无聊了，没有任何人找你，你决定勉为其难地随便回复一下它，找点乐子或者发发牢骚。";
                                task.addContext(innerMonologue);

                                QQTaskPreparationPool.put(senderId, task);
                                updatedQQIds.add(senderId); // 加入存活名单，防止立刻被催熟

                                List<String> l = new LinkedList<>();
                                l.add("实在太闲了，从垃圾桶里捞了一条原本不想理的消息来回复。");
                                new MemoryManager().inputCurrentMemorys(l);

                                log.info("🎲 运气爆发，被拦截的消息 [QQ:{}] 成功复活进入预备池", senderId);
                            }
                        }
                    }
                }
            }
        }

        // ==========================================
        // 阶段 3：巡检与催熟 (处理 static_cnt)
        // ==========================================
        Iterator<Map.Entry<Long, QQChatTask>> iterator = QQTaskPreparationPool.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Long, QQChatTask> entry = iterator.next();
            Long qqId = entry.getKey();
            QQChatTask task = entry.getValue();

            if (!updatedQQIds.contains(qqId)) {
                task.addStatic();
                if (task.getStatic_cnt() > ConfigsManager.MESSAGE_WAITING_TIME) {
                    log.info("任务 [QQ:{}] 已熟透，移交至执行总线", qqId);
                    TaskQueue.offerLast(task);
                    this.trimTaskQueue(); // 塞完立马修剪
                    iterator.remove();
                }
            }
        }
    }
    /**
     * 【补充方法】：获取允许主动发起对话的目标列表
     * 你需要在这个方法里对接你的 NapcatAdapter，把它的 friendNameCache 或 groupNameCache 的 Key 拿出来
     */
    private List<Long> getAvailableTargetsForProactiveChat() {
        List<Long> targets = new ArrayList<>();
        // 示例思路：如果你在 LivingLoop 里能拿到 NapcatAdapter 的实例（假设叫 napcatClient）
        // targets.addAll(napcatClient.getFriendNameCache().keySet());
        // targets.addAll(napcatClient.getGroupNameCache().keySet());

        // 注意：如果你不想让它随便去骚扰陌生的群，可以在这里加白名单过滤！

        return targets;
    }

    private ArrayNode buildAttentionToolDefinition() {
        ObjectMapper mapper = new ObjectMapper();
        ArrayNode tools = mapper.createArrayNode();

        ObjectNode tool = tools.addObject();
        tool.put("type", "function");

        ObjectNode function = tool.putObject("function");
        function.put("name", "submit_attention_list");
        function.put("description", "提交主脑需要关注的消息编号列表");

        ObjectNode parameters = function.putObject("parameters");
        parameters.put("type", "object");

        ObjectNode properties = parameters.putObject("properties");

        ObjectNode selectedIndices = properties.putObject("selected_indices");
        selectedIndices.put("type", "array");
        selectedIndices.put("description", "被选中的消息的数字编号（index）列表");

        ObjectNode items = selectedIndices.putObject("items");
        items.put("type", "integer");

        ArrayNode required = parameters.putArray("required");
        required.add("selected_indices");

        return tools;
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