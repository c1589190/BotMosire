package com.cna.agent.AgentTool;

import com.cna.ChatAdapterManager;
import com.cna.agent.AgentTasksHandlers.ChatTaskHandler;
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

        ObjectNode function = tool.putObject("function");
        function.put("name", getName());
        function.put("description", "当你需要发送消息时调用此工具。如果要在群聊中回复，请将目标设为消息的 source（如 'qq_group:12345'）；如果要私聊回复某人，请将目标设为消息的 role（如 'qqid:12345'）。Discord 私聊用 'discord_dm:{userId}'，Discord 頻道用 'discord_guild:{guildId}:{channelId}'。");

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
        String target = arguments.path("target_namespace").asText().trim();
        String msg    = arguments.path("message").asText();
        long replyToId = ChatTaskHandler.CURRENT_REPLY_TO_ID.get();

        this.lastTarget  = target;
        this.lastMessage = msg;

        return ChatAdapterManager.send(target, msg, replyToId);
    }

    @Override
    public String getTextRecord() {
        if (lastTarget == null) {
            return "尝试调用发送消息工具，但未成功执行;";
        }
        return "向目标 [" + this.lastTarget + "] 发送了消息: {" + this.lastMessage + "};";
    }
}
