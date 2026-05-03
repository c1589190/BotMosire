package com.cna.agent.AgentTool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import static com.cna.Main.GlobalNapcatAdapter;

@Slf4j
public class SendChatMessage implements DefaultAgentToolUnit {

    private final ObjectMapper mapper = new ObjectMapper();

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

        ObjectNode function = tool.putObject("function");
        function.put("name", getName());
        function.put("description", "当你需要发送消息时调用此工具。如果要在群聊中回复，请将目标设为消息的 source（如 'qq_group:12345'）；如果要私聊回复某人，请将目标设为消息的 role（如 'qqid:12345'）。");

        ObjectNode parameters = function.putObject("parameters");
        parameters.put("type", "object");

        ObjectNode properties = parameters.putObject("properties");

        ObjectNode targetNamespace = properties.putObject("target_namespace");
        targetNamespace.put("type", "string");
        targetNamespace.put("description", "发送目标的命名空间标识符。群聊填入 source，私聊填入 role。必须带有前缀！");

        ObjectNode message = properties.putObject("message");
        message.put("type", "string");
        message.put("description", "你要发送的具体文本内容。");

        ArrayNode required = parameters.putArray("required");
        required.add("target_namespace");
        required.add("message");

        return tool;
    }

    @Override
    public String execute(JsonNode arguments) {
        try {
            String target = arguments.path("target_namespace").asText().trim();
            String msg = arguments.path("message").asText();

            this.lastTarget = target;
            this.lastMessage = msg;

            if (target.startsWith("qq_group:")) {
                long groupId = Long.parseLong(target.substring(9));
                GlobalNapcatAdapter.sendGroupMsg(groupId, msg);
                log.info("[Tool][SendChatMessage] 代理向群聊 [{}] 发送了消息", groupId);
                return "SUCCESS: 消息已成功发送至群聊 " + target;

            } else if (target.startsWith("qqid:")) {
                long userId = Long.parseLong(target.substring(5));
                GlobalNapcatAdapter.sendPrivateMsg(userId, msg);
                log.info("[Tool][SendChatMessage] 代理向用户 [{}] 发送了私聊消息", userId);
                return "SUCCESS: 消息已成功发送至用户 " + target;

            } else {
                log.warn("[Tool][SendChatMessage] 无法识别的目标格式: {}", target);
                return "ERROR: 无法识别的 target_namespace 格式。必须带有 'qq_group:' 或 'qqid:' 前缀。";
            }

        } catch (NumberFormatException e) {
            log.error("执行 send_chat_message 失败: 标识符解析错误", e);
            return "ERROR: target_namespace 前缀后的 ID 必须是有效的数字。";
        } catch (Exception e) {
            log.error("执行 send_chat_message 发生异常", e);
            return "ERROR: 消息发送失败，底层异常: " + e.getMessage();
        }
    }

    @Override
    public String getTextRecord() {
        if (lastTarget == null) {
            return "尝试调用发送消息工具，但未成功执行;";
        }
        return "向目标 [" + this.lastTarget + "] 发送了消息: {" + this.lastMessage + "};";
    }
}