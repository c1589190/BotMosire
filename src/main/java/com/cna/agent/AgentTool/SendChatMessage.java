package com.cna.agent.AgentTool;

import com.cna.ChatAdaptersManager;
import com.cna.agent.AgentTasksHandlers.ChatTaskHandler;
import com.cna.config.ToolPromptsManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SendChatMessage implements DefaultAgentToolUnit {

    private static final ObjectMapper mapper = new ObjectMapper();

    private String lastTarget = null;
    private String lastMessage = null;

    @Override
    public String getName() {
        return "send_chat_message";
    }

    @Override
    public ObjectNode getToolDefinition() {
        ObjectNode tool = mapper.createObjectNode();
        tool.put("type", "function");

        ToolPromptsManager p = new ToolPromptsManager(this.getClass().getName());

        ObjectNode function = tool.putObject("function");
        function.put("name", getName());
        function.put("description", p.getToolDescription());

        ObjectNode parameters = function.putObject("parameters");
        parameters.put("type", "object");

        ObjectNode properties = parameters.putObject("properties");

        ObjectNode targetNamespace = properties.putObject("target_namespace");
        targetNamespace.put("type", "string");
        targetNamespace.put("description", p.getCustomDescription("target_namespace"));

        // 【修改 1】：将 message 改为 messages，类型从 string 改为 array
        ObjectNode messages = properties.putObject("messages");
        messages.put("type", "array");
        messages.put("description", p.getCustomDescription("messages")); // 提示词里可以写"分段发送的消息数组"
        ObjectNode items = messages.putObject("items");
        items.put("type", "string");

        ArrayNode required = parameters.putArray("required");
        required.add("target_namespace");
        required.add("messages"); // 对应修改为 messages

        parameters.put("additionalProperties", false);
        return tool;
    }

    @Override
    public String execute(JsonNode arguments) {
        String target = arguments.path("target_namespace").asText().trim();
        JsonNode messagesNode = arguments.path("messages");
        long replyToId = ChatTaskHandler.CURRENT_REPLY_TO_ID.get();

        this.lastTarget = target;
        StringBuilder recordBuilder = new StringBuilder();
        StringBuilder resultBuilder = new StringBuilder();

        // 【修改 2】：解析数组并分段发送
        if (messagesNode.isArray()) {
            boolean isFirstMessage = true;
            for (JsonNode msgNode : messagesNode) {
                String msg = msgNode.asText();
                if (msg == null || msg.isBlank()) continue;

                // 核心细节：只有数组里的第一条消息才使用“引用回复”，后续的当做普通追加消息，显得更像真人
                long currentReplyId = isFirstMessage ? replyToId : 0L;

                // 发送并收集结果
                String res = ChatAdaptersManager.send(target, msg, currentReplyId);
                resultBuilder.append(res).append("\n");
                recordBuilder.append("{").append(msg).append("} ");

                isFirstMessage = false;

                // (可选) 稍微休眠一下，防止瞬间发多条导致物理层风控或乱序
                try {
                    Thread.sleep(300);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        } else {
            // 兜底容错：如果大模型抽风还是传了单字符串过来
            String msg = messagesNode.asText();
            if (msg != null && !msg.isBlank()) {
                String res = ChatAdaptersManager.send(target, msg, replyToId);
                resultBuilder.append(res);
                recordBuilder.append("{").append(msg).append("}");
            }
        }

        this.lastMessage = recordBuilder.toString().trim();
        return resultBuilder.toString().trim();
    }

    @Override
    public String getTextRecord() {
        if (lastTarget == null) {
            return "尝试调用发送消息工具，但未成功执行;";
        }
        return "向目标 [" + this.lastTarget + "] 分段发送了消息: " + this.lastMessage + ";";
    }
}