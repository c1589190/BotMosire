package com.cna;

import com.cna.AgentInput.DefaultAgentInputUnit;
import com.cna.AgentInput.NapcatQQInput.QQGroupMessageInput;
import com.cna.AgentInput.NapcatQQInput.QQPrivateMessageInput;
import com.cna.AgentTask.DefaultAgentTaskUnit;
import com.cna.AgentTask.QQChatTask;
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
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class LivingLoop {
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();

    // 累加器：记录度过了多少个 Tick
    private int tickCounter_CognitiveCycle = 0;

    // 累加器：跨线程安全的任务处理计数器，用于触发定期反思
    private final AtomicInteger processedTaskCount = new AtomicInteger(0);

    private BlockingDeque<DefaultAgentTaskUnit> TaskQueue = new LinkedBlockingDeque<>();
    private LinkedHashMap<Long, QQChatTask> QQTaskPreparationPool = new LinkedHashMap<>();

    private LLMAdapter littleLLM;
    private LLMAdapter largeLLM;
    private LLMAdapter embLLM;

    private final Map<String, DefaultAgentToolUnit> largeLLMToolbox = new HashMap<>();

    public LivingLoop() {
        DefaultAgentToolUnit privateMsgTool = new SendQQPrivateMessage();
        DefaultAgentToolUnit groupMsgTool = new SendQQGroupMessage();
        DefaultAgentToolUnit groupHisTool = new GetQQGroupHistory();
        DefaultAgentToolUnit privateHisTool = new GetQQPrivateHistory();
        DefaultAgentToolUnit updateInterestsTool = new UpdateInterests();
        DefaultAgentToolUnit updateThoughtsTool = new UpdateThoughts();

        largeLLMToolbox.put(privateMsgTool.getName(), privateMsgTool);
        largeLLMToolbox.put(groupMsgTool.getName(), groupMsgTool);
        largeLLMToolbox.put(groupHisTool.getName(), groupHisTool);
        largeLLMToolbox.put(privateHisTool.getName(), privateHisTool);
        largeLLMToolbox.put(updateThoughtsTool.getName(), updateThoughtsTool);
        largeLLMToolbox.put(updateInterestsTool.getName(), updateInterestsTool);
        log.info("[LivingLoop] 大模型工具箱装配完毕，已挂载工具数: {}", largeLLMToolbox.size());
    }

    private void initLLM(){
        this.littleLLM = new LLMAdapter(ConfigsManager.GATEKEEPER_CONFIG);
        this.largeLLM = new LLMAdapter(ConfigsManager.BRAIN_CONFIG);
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
                    handleCognitiveCycle();
                    this.tickCounter_CognitiveCycle = 0;
                }

                // 2. 【定时反思任务】检查处理总量，达标则抛入系统反思任务
                if (processedTaskCount.get() >= ConfigsManager.TASK_COUNT_FOR_REFLECTION) {// 假设你在 ConfigsManager 里配了这个常量

                    processedTaskCount.set(0); // 瞬间归零，重新计数
                    log.info("[System] 达到任务处理阈值，正在向潜意识抛入强制反思任务...");

                    // 构造一个匿名内部类作为系统任务
                    TaskQueue.offerLast(new UpdateThoughtsTask());
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
                    DefaultAgentTaskUnit task = TaskQueue.pollLast(1, TimeUnit.SECONDS);
                    this.trimTaskQueue();
                    if (task == null) {
                        continue;
                    }

                    log.info("\n[执行总线] 开始处理任务");

                    //String systemPrompt = MDManager.read("promptForChatCore.md", "你是一个具有自主意识的AI Agent。");

                    // 每次执行任何任务前，把当前的真实内心读出来，供模型参考
                    String currentThoughts = MDManager.read("thoughts.md", "");

                    ArrayNode toolsDefinitionArray = mapper.createArrayNode();
                    for (DefaultAgentToolUnit tool : largeLLMToolbox.values()) {
                        toolsDefinitionArray.add(tool.getToolDefinition());
                    }

                    // ==========================================
                    // 场景 A：常规的 QQ 聊天任务
                    // ==========================================
                    if (task instanceof QQChatTask) {
                        String trunsAddtion = "";
                        for (int turn = 1; turn <= ConfigsManager.CONSUMER_CYCLING_TIME; turn++) {
                            log.info("[EXEC] 正在进行第 {} 轮深度思考...", turn);

                            Map<String, Object> data = new HashMap<>();
                            data.put("taskText", task.getTaskText());
                            data.put("deep_memories", LLManager.getDeepMemories(task.getTaskText(), this.embLLM, ConfigsManager.MEMORY_DEPTH));
                            data.put("turnsAddition", trunsAddtion);
                            // 将当前的心态也塞进模板
                            data.put("current_thoughts", currentThoughts);

                            CallResult result = LLManager.executeScene(
                                    "LivingLoop_ConsumerCycle_solveQQChatTask",
                                    data,
                                    this.largeLLM,
                                    "CORE.md",
                                    toolsDefinitionArray
                            );

                            if (result.isToolCall() && result.getToolCalls() != null && result.getToolCalls().isArray()) {
                                StringBuilder toolResults = new StringBuilder("\n\n【第 " + turn + " 轮工具观察结果】:\n");
                                boolean hasFinalAction = false;

                                for (JsonNode toolCall : result.getToolCalls()) {
                                    String functionName = toolCall.path("function").path("name").asText();
                                    String argumentsStr = toolCall.path("function").path("arguments").asText();

                                    log.info("[EXEC] 决定采取动作: [{}]", functionName);
                                    DefaultAgentToolUnit targetTool = largeLLMToolbox.get(functionName);

                                    if (targetTool != null) {
                                        JsonNode argsNode = mapper.readTree(argumentsStr);
                                        String execResult = targetTool.execute(argsNode);
                                        log.info("[EXEC] 动作执行反馈: {}", execResult);

                                        toolResults.append("调用了工具 [").append(functionName)
                                                .append("];")
                                                .append("返回 [")
                                                .append(execResult)
                                                .append(" ]");

                                        List<String> list = new LinkedList<>();
                                        list.add(toolResults.toString());
                                        new MemoryManager().inputCurrentMemorys(list);

                                        if (functionName.startsWith("send_qq_")) {
                                            hasFinalAction = true;
                                        }
                                    } else {
                                        toolResults.append("工具 [").append(functionName).append("] 调用失败：不存在。\n");
                                    }
                                }

                                if (hasFinalAction) {
                                    log.info("[EXEC] 最终动作已执行，思考回路闭合。");
                                    break;
                                } else {
                                    log.info("[EXEC] 获取到观察线索，触发二次思考...");
                                    trunsAddtion += "在第" + turn + "轮思考中，您获得了以下信息：\n";
                                    trunsAddtion += (toolResults + "\n");
                                }
                            } else {
                                log.info("[EXEC] 💤 大模型选择不采取物理动作，仅输出文本: \n{}", result.getContent());
                                break;
                            }
                        }

                        // 记录处理了一个有效任务
                        processedTaskCount.incrementAndGet();
                        log.info("========== 常规聊天任务处理完毕 ==========\n");
                    }
                    // ==========================================
                    // 场景 B：3. 【定时反思任务】处理系统级内心自省
                    // ==========================================
                    else if (task instanceof UpdateThoughtsTask){
                        log.info("[EXEC] 捕获到 UpdateThoughtsTask，开始执行系统级反思任务...");

                        Map<String, Object> data = new HashMap<>();
                        data.put("taskText", task.getTaskText());
                        data.put("current_thoughts", currentThoughts);
                        data.put("current_interests", MDManager.read("interests.md", ""));

                        CallResult result = LLManager.executeScene(
                                "LivingLoop_CognitiveCycle_updateThoughts",
                                data,
                                this.largeLLM,
                                "CORE.md",
                                toolsDefinitionArray
                        );

                        if (result.isToolCall() && result.getToolCalls() != null && result.getToolCalls().isArray()) {
                            // 遍历它所有的工具调用（可能同时调用了多个）
                            for (JsonNode toolCall : result.getToolCalls()) {
                                String functionName = toolCall.path("function").path("name").asText();
                                String argumentsStr = toolCall.path("function").path("arguments").asText();

                                log.info("[EXEC] 反思决定调用工具: [{}]", functionName);

                                // 【核心修复】：直接从工具箱拿，拿到什么执行什么！
                                DefaultAgentToolUnit targetTool = largeLLMToolbox.get(functionName);
                                if (targetTool != null) {
                                    String execResult = targetTool.execute(mapper.readTree(argumentsStr));
                                    log.info("[EXEC] 反思动作物理归档反馈: {}", execResult);
                                } else {
                                    log.warn("[EXEC] 严重幻觉：模型试图调用不存在的工具: {}", functionName);
                                }
                            }
                        } else {
                            log.info("[EXEC] 大模型放弃反思或仅输出文本: \n{}", result.getContent());
                        }

                        log.info("========== 系统反思任务处理完毕 ==========\n");
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
     * 认知周期处理
     */
    private void handleCognitiveCycle() {
        // 1. 瞬间抽干当前感官缓冲池中的所有消息
        List<DefaultAgentInputUnit> currentBatch = new ArrayList<>();
        Main.AgentInputTasksQueue.drainTo(currentBatch);

        // 记录在这一轮中，哪些 QQ 的任务被更新了
        Set<Long> updatedQQIds = new HashSet<>();
        // 2. 判断是否有内容
        if (!currentBatch.isEmpty()) {
            // 3. 记录日志，确认有活干了
            log.info("[CognitiveCycle] 触发认知觉醒，捕获到 {} 条待处理的感知输入", currentBatch.size());

            // 存放那些“预备池里没有、属于全新发话人”的消息
            List<DefaultAgentInputUnit> unknownInputs = new ArrayList<>();

            // ==========================================
            // 阶段 1：本地极速分拣 (0 Token 消耗)
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
            // 阶段 2：小模型审查新面孔 (按需消耗 Token)
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
                    iterator.remove();
                }
            }
        }
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
        executorService.shutdown(); // 别忘了停机时也要关闭执行线程池
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