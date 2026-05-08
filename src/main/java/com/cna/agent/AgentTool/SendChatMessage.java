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

        ObjectNode message = properties.putObject("message");
        message.put("type", "string");
        message.put("description", p.getCustomDescription("message"));

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

        return ChatAdaptersManager.send(target, msg, replyToId);
    }

    @Override
    public String getTextRecord() {
        if (lastTarget == null) {
            return "尝试调用发送消息工具，但未成功执行;";
        }
        return "向目标 [" + this.lastTarget + "] 发送了消息: {" + this.lastMessage + "};";
    }
}