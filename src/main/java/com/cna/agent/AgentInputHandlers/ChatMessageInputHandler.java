package com.cna.agent.AgentInputHandlers;

import com.cna.ChatAdaptersManager;
import com.cna.agent.AgentInput.ChatMessageInput;
import com.cna.agent.AgentInput.DefaultAgentInputUnit;
import com.cna.agent.AgentTask.ChatTask;
import com.cna.agent.LivingLoop;
import com.cna.config.ConfigsManager;
import com.cna.config.ScenePromptsManager;
import com.cna.db.MDManager;
import com.cna.agent.MemoryManager;
import com.cna.llm.CallResult;
import com.cna.llm.LLMAdapter;
import com.cna.llm.LLManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

@Slf4j
public class ChatMessageInputHandler implements DefaultAgentInputHandlerUnit {

    private static final ObjectMapper sharedMapper = new ObjectMapper();

    protected LinkedHashMap<String, ChatTask> ChatTaskPreparationPool = new LinkedHashMap<>();
    protected Set<String> updatedRoles = new HashSet<>();
    protected final Map<String, Long> lastTaskPushedTime = new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public Class<? extends DefaultAgentInputUnit> getSupportedInputClass() {
        return ChatMessageInput.class;
    }

    @Override
    public void handleInputs(List<DefaultAgentInputUnit> inputs, LivingLoop engine) {
        // 每次收到新消息时清空全局状态，准备记录本轮活跃的 Role
        updatedRoles.clear();

        if (!inputs.isEmpty()) {
            log.info("[CognitiveCycle] ChatMessage处理器捕获到 {} 条待处理的感知输入", inputs.size());

            List<DefaultAgentInputUnit> unknownInputs = new ArrayList<>();

            // ==========================================
            // 阶段 1：本地极速分拣
            // ==========================================
            for (DefaultAgentInputUnit input : inputs) {

                if (input instanceof ChatMessageInput) {
                    ChatTask existingChatTask;
                    String senderRole = ((ChatMessageInput) input).getRole();

                    if (senderRole != null && !senderRole.isBlank() && ChatTaskPreparationPool.containsKey(senderRole)) {
                        existingChatTask = ChatTaskPreparationPool.get(senderRole);
                        existingChatTask.addContext(((ChatMessageInput) input).getContent());

                        // 若這條消息有引用，更新任務的 replyToMessageId
                        long quotedId = ((ChatMessageInput) input).getQuotedMessageId();
                        if (quotedId > 0) {
                            existingChatTask.setReplyToMessageId(quotedId);
                        }

                        updatedRoles.add(senderRole);

                        List<String> l = new LinkedList<>();
                        l.add("为自己创建了任务，有关于 [ " + input.getInputText() + " ]");
                        MemoryManager.getInstance().inputCurrentMemorys(l);

                        log.debug("为已有任务 [Role:{}] 追加了新消息", senderRole);
                    } else {
                        unknownInputs.add(input);
                    }
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


                String namespace = ((ChatMessageInput) unknownInputs.get(0)).getSource();
                String recentHistory = ChatAdaptersManager.getHistory(namespace, ConfigsManager.CHATHISTORY_VIEW_AMOUNT);

                Map<String, Object> data = new HashMap<>();
                data.put("currentInputs", currentInputs.toString());
                data.put("recent_history", recentHistory); // 给小模型也塞一份上下文
                data.put("current_interests", MDManager.read("interests.md", ""));

                CallResult result = LLManager.executeScene(
                        new ScenePromptsManager(ChatMessageInput.class.getName()).getSolvingPrompt(),
                        data,
                        new LLMAdapter(ConfigsManager.GATEKEEPER_CONFIG),
                        buildAttentionToolDefinition()
                );

                // 【修改】：使用 Map 来存储消息单元以及对应的判断理由
                Map<DefaultAgentInputUnit, String> interestingInputsWithReasons = new LinkedHashMap<>();

                if (result.isToolCall() && result.getToolCalls() != null && result.getToolCalls().isArray()) {
                    JsonNode firstToolCall = result.getToolCalls().get(0);
                    if ("submit_attention_list".equals(firstToolCall.path("function").path("name").asText())) {
                        String argumentsStr = firstToolCall.path("function").path("arguments").asText();
                        try {
                            ObjectMapper mapper = new ObjectMapper();
                            JsonNode argsNode = mapper.readTree(argumentsStr);

                            // 【修改】：解析新的 selected_items 结构
                            JsonNode itemsArray = argsNode.path("selected_items");

                            if (itemsArray.isArray() && !itemsArray.isEmpty()) {
                                log.info("[Gatekeeper] 命中！提取到高价值信号及理由: {}", itemsArray.toString());
                                for (JsonNode itemNode : itemsArray) {
                                    int index = itemNode.path("index").asInt(-1);
                                    String reason = itemNode.path("reason").asText("");

                                    if (index >= 0 && index < unknownInputs.size()) {
                                        interestingInputsWithReasons.put(unknownInputs.get(index), reason);
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
                for (Map.Entry<DefaultAgentInputUnit, String> entry : interestingInputsWithReasons.entrySet()) {
                    DefaultAgentInputUnit input = entry.getKey();
                    String reason = entry.getValue();

                    String source = null;
                    String source_name = null;
                    String senderRole = null;
                    String senderName = null;
                    String text = "";

                    if (input instanceof ChatMessageInput) {
                        senderRole = ((ChatMessageInput) input).getRole();
                        senderName = ((ChatMessageInput) input).getRole_name();
                        source = ((ChatMessageInput) input).getSource();
                        source_name = ((ChatMessageInput) input).getSource_name();
                        text = ((ChatMessageInput) input).getContent();
                    }

                    if (senderRole != null && !senderRole.isBlank()) {
                        long quotedId = ((ChatMessageInput) input).getQuotedMessageId();

                        // 组装要添加到上下文中供主脑参考的理由
                        String reasonContext = "( 注意到这条消息的理由: " + reason + " )";

                        if (ChatTaskPreparationPool.containsKey(senderRole)) {
                            ChatTask existingTask = ChatTaskPreparationPool.get(senderRole);
                            existingTask.addContext(reasonContext);
                            existingTask.addContext(text);

                            if (quotedId > 0) {
                                existingTask.setReplyToMessageId(quotedId);
                            }

                            List<String> l = new LinkedList<>();
                            l.add("想要回复这条消息:{\n" + input.getInputText() + "\n}; 理由是: " + reason);
                            MemoryManager.getInstance().inputCurrentMemorys(l);

                            log.info("小模型判定有价值，为当前批次的新目标 [Role:{}] 合并追加了连贯消息及理由", senderRole);
                        } else {
                            ChatTask task = new ChatTask(source, source_name, senderRole, senderName, quotedId);
                            task.addContext(text);
                            task.addContext(reasonContext); // 【修改】：追加判断理由
                            ChatTaskPreparationPool.put(senderRole, task);

                            List<String> l = new LinkedList<>();
                            l.add("想要回复这条消息:{\n" + input.getInputText() + "\n}; 理由是: " + reason);
                            MemoryManager.getInstance().inputCurrentMemorys(l);

                            log.info("小模型判定有价值，为新目标 [Role:{}] 创建了预备任务及理由", senderRole);
                        }
                        updatedRoles.add(senderRole);
                    }
                }

                // ==========================================
                // 阶段 2.5：无聊打发时间 (随机捞回被拦截的消息)
                // ==========================================
                if (updatedRoles.isEmpty()) {
                    List<DefaultAgentInputUnit> rejectedInputs = new ArrayList<>(unknownInputs);
                    // 【修改】：从原来的 interestingInputs 改为从 Map 的 KeySet 中移除
                    rejectedInputs.removeAll(interestingInputsWithReasons.keySet());

                    if (!rejectedInputs.isEmpty()) {

                        if (Math.random() < ConfigsManager.RANDOM_CHAT_CHANCE) {
                            log.info("[CognitiveCycle] 💤 系统闲得发慌，决定从垃圾桶里捞一条消息随便回回...");

                            DefaultAgentInputUnit luckyInput = rejectedInputs.get(new java.util.Random().nextInt(rejectedInputs.size()));

                            String source = null;
                            String source_name = null;
                            String senderRole = null;
                            String senderName = null;
                            String text = "";

                            if (luckyInput instanceof ChatMessageInput) {
                                senderRole = ((ChatMessageInput) luckyInput).getRole();
                                senderName = ((ChatMessageInput) luckyInput).getRole_name();
                                source = ((ChatMessageInput) luckyInput).getSource();
                                source_name = ((ChatMessageInput) luckyInput).getSource_name();
                                text = ((ChatMessageInput) luckyInput).getContent();
                            }

                            if (senderRole != null && !senderRole.isBlank()) {
                                long quotedId = ((ChatMessageInput) luckyInput).getQuotedMessageId();
                                ChatTask task = new ChatTask(source, source_name, senderRole, senderName, quotedId);

                                String innerMonologue = "这条消息 [ " + text + " ] 原本不在你的兴趣雷达内，你觉得它是废话。但是因为你现在实在太无聊了，没有任何人找你，你决定勉为其难地随便回复一下它。";
                                task.addContext(innerMonologue);

                                ChatTaskPreparationPool.put(senderRole, task);

                                updatedRoles.add(senderRole);

                                List<String> l = new LinkedList<>();
                                l.add("实在太闲了，从垃圾桶里捞了一条原本不想理的消息来回复:{\n" + luckyInput.getInputText() + "\n};");
                                MemoryManager.getInstance().inputCurrentMemorys(l);

                                log.info("🎲 运气爆发，被拦截的消息 [Role:{}] 成功复活进入预备池", senderRole);
                            }
                        }
                    }
                }
            }
        }
    }

    @Override
    public void tick(LivingLoop engine) {

        Iterator<Map.Entry<String, ChatTask>> iterator = ChatTaskPreparationPool.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, ChatTask> entry = iterator.next();
            String roleId = entry.getKey();
            ChatTask task = entry.getValue();

            if (!updatedRoles.contains(roleId)) {
                task.addStatic();
                if (task.getStatic_cnt() > ConfigsManager.MESSAGE_WAITING_TIME) {
                    long now = System.currentTimeMillis();
                    Long lastPushed = lastTaskPushedTime.get(roleId);
                    if (lastPushed != null && now - lastPushed < ConfigsManager.RATE_LIMIT_MS) {
                        log.info("[RateLimit] 用户 [{}] 请求过于频繁，跳过本次推送", roleId);
                        iterator.remove();
                    } else {
                        log.info("任务 [Role:{}] 已熟透，移交至执行总线", roleId);
                        lastTaskPushedTime.put(roleId, now);
                        engine.pushTask(task);
                        iterator.remove();
                    }
                }
            }
        }
        updatedRoles.clear();
    }

    // 【修改】：重构 Tool 定义，让大模型返回对象数组包含 index 和 reason
    private ArrayNode buildAttentionToolDefinition() {
        ObjectMapper mapper = new ObjectMapper();
        ArrayNode tools = mapper.createArrayNode();

        ObjectNode tool = tools.addObject();
        tool.put("type", "function");

        ObjectNode function = tool.putObject("function");
        function.put("name", "submit_attention_list");
        function.put("description", "提交主脑需要关注的消息列表及选中理由");

        ObjectNode parameters = function.putObject("parameters");
        parameters.put("type", "object");

        ObjectNode properties = parameters.putObject("properties");

        ObjectNode selectedItems = properties.putObject("selected_items");
        selectedItems.put("type", "array");
        selectedItems.put("description", "被选中的消息及其理由的列表");

        ObjectNode items = selectedItems.putObject("items");
        items.put("type", "object");

        ObjectNode itemProperties = items.putObject("properties");

        ObjectNode indexNode = itemProperties.putObject("index");
        indexNode.put("type", "integer");
        indexNode.put("description", "被选中消息的数字编号（index）");

        ObjectNode reasonNode = itemProperties.putObject("reason");
        reasonNode.put("type", "string");
        reasonNode.put("description", "判定这条消息有价值并需要回复的详细理由");

        ArrayNode itemRequired = items.putArray("required");
        itemRequired.add("index").add("reason");

        ArrayNode required = parameters.putArray("required");
        required.add("selected_items");

        return tools;
    }
}