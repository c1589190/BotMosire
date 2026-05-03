package com.cna.agent.AgentInputHandlers;

import com.cna.Main;
import com.cna.agent.AgentInput.ChatMessageInput;
import com.cna.agent.AgentInput.DefaultAgentInputUnit;
import com.cna.agent.AgentTask.ChatTask;
import com.cna.agent.LivingLoop;
import com.cna.config.ConfigsManager;
import com.cna.db.MDManager;
import com.cna.db.MemoryManager;
import com.cna.llm.CallResult;
import com.cna.llm.LLMAdapter;
import com.cna.llm.LLManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;

import java.util.*;

@Slf4j
public class ChatMessageInputHandler implements DefaultAgentInputHandler {

    // 把原本 LivingLoop 里的预备池搬到这里来！这是聊天消息专属的孵化器！
    private LinkedHashMap<String, ChatTask> ChatTaskPreparationPool = new LinkedHashMap<>();

    // 记录上一轮被更新的 Role，用于跨方法判定（比如 tick 里催熟用）
    private Set<String> updatedRoles = new HashSet<>();

    @Override
    public Class<? extends DefaultAgentInputUnit> getSupportedInputClass() {
        return ChatMessageInput.class;
    }

    @Override
    public void handleInputs(List<DefaultAgentInputUnit> inputs, LivingLoop engine) {
        // 每次收到新消息时清空全局状态，准备记录本轮活跃的 Role
        updatedRoles.clear();

        // 【修复1】：不再从 Main 抽干队列，直接使用参数传进来的 inputs
        if (!inputs.isEmpty()) {
            log.info("[CognitiveCycle] ChatMessage处理器捕获到 {} 条待处理的感知输入", inputs.size());

            List<DefaultAgentInputUnit> unknownInputs = new ArrayList<>();

            // ==========================================
            // 阶段 1：本地极速分拣
            // ==========================================
            for (DefaultAgentInputUnit input : inputs) { // 【修复1】：遍历 inputs
                ChatTask existingChatTask;
                String senderRole = null;

                if (input instanceof ChatMessageInput) {
                    senderRole = ((ChatMessageInput) input).getRole();
                }

                if (senderRole != null && !senderRole.isBlank() && ChatTaskPreparationPool.containsKey(senderRole)) {
                    existingChatTask = ChatTaskPreparationPool.get(senderRole);
                    existingChatTask.addContext(((ChatMessageInput) input).getContent());

                    // 【修复2】：直接使用全局的 updatedRoles
                    updatedRoles.add(senderRole);

                    List<String> l = new LinkedList<>();
                    l.add("为自己创建了任务，有关于 [ " + input.getInputText() + " ]");
                    new MemoryManager().inputCurrentMemorys(l);

                    log.debug("为已有任务 [Role:{}] 追加了新消息", senderRole);
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
                        MDManager.read("prompts/LivingLoop_CognitiveCycle_getInterest.md"),
                        data,
                        new LLMAdapter(ConfigsManager.GATEKEEPER_CONFIG), // 或者用 engine.getLittleLLM()
                        buildAttentionToolDefinition()
                );

                List<DefaultAgentInputUnit> interestingInputs = new ArrayList<>();
                if (result.isToolCall() && result.getToolCalls() != null && result.getToolCalls().isArray()) {
                    // ... 解析 submit_attention_list 的逻辑不变 ...
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
                        if (ChatTaskPreparationPool.containsKey(senderRole)) {
                            ChatTaskPreparationPool.get(senderRole).addContext(text);

                            List<String> l = new LinkedList<>();
                            l.add("想要回复这条消息:{\n" + input.getInputText() + "\n};");
                            new MemoryManager().inputCurrentMemorys(l);

                            log.info("小模型判定有价值，为当前批次的新目标 [Role:{}] 合并追加了连贯消息", senderRole);
                        } else {
                            ChatTask task = new ChatTask(source, source_name, senderRole, senderName);
                            task.addContext(text);
                            ChatTaskPreparationPool.put(senderRole, task);

                            List<String> l = new LinkedList<>();
                            l.add("想要回复这条消息:{\n" + input.getInputText() + "\n};");
                            new MemoryManager().inputCurrentMemorys(l);

                            log.info("小模型判定有价值，为新目标 [Role:{}] 创建了预备任务", senderRole);
                        }
                        // 【修复2】：直接使用全局的 updatedRoles
                        updatedRoles.add(senderRole);
                    }
                }

                // ==========================================
                // 阶段 2.5：无聊打发时间 (随机捞回被拦截的消息)
                // ==========================================
                // 【修复2】：直接使用全局的 updatedRoles
                if (updatedRoles.isEmpty()) {
                    List<DefaultAgentInputUnit> rejectedInputs = new ArrayList<>(unknownInputs);
                    rejectedInputs.removeAll(interestingInputs);

                    if (!rejectedInputs.isEmpty()) {
                        double salvageChance = 0.1;

                        if (Math.random() < salvageChance) {
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
                                ChatTask task = new ChatTask(source, source_name, senderRole, senderName);

                                String innerMonologue = "【系统环境注入】：这条消息 [ " + text + " ] 原本不在你的兴趣雷达内，你觉得它是废话。但是因为你现在实在太无聊了，没有任何人找你，你决定勉为其难地随便回复一下它，找点乐子或者发发牢骚。";
                                task.addContext(innerMonologue);

                                ChatTaskPreparationPool.put(senderRole, task);

                                // 【修复2】：直接使用全局的 updatedRoles
                                updatedRoles.add(senderRole);

                                List<String> l = new LinkedList<>();
                                l.add("实在太闲了，从垃圾桶里捞了一条原本不想理的消息来回复:{\n" + luckyInput.getInputText() + "\n};");
                                new MemoryManager().inputCurrentMemorys(l);

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
                    log.info("任务 [Role:{}] 已熟透，移交至执行总线", roleId);

                    // 【核心】：用 engine 的接口把任务塞进大模型消费队列！
                    engine.pushTask(task);

                    iterator.remove();
                }
            }
        }

        // 催熟完毕后，清空 updatedRoles 等待下一个 Tick
        updatedRoles.clear();
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
}