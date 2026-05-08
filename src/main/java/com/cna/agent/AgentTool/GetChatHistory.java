package com.cna.agent.AgentTool;

import com.cna.ChatAdaptersManager;
import com.cna.config.ConfigsManager;
import com.cna.config.ToolPromptsManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class GetChatHistory implements DefaultAgentToolUnit {

    private static final ObjectMapper mapper = new ObjectMapper();

    private String lastQueriedTarget = null;

    @Override
    public String getName() {
        return "get_chat_history";
    }

    @Override
    public ObjectNode getToolDefinition() {
        ObjectNode tool = mapper.createObjectNode();
        tool.put("type", "function");

        // 实例化提示词管理器
        ToolPromptsManager p = new ToolPromptsManager(this.getClass().getName());

        ObjectNode function = tool.putObject("function");
        function.put("name", getName());
        // 获取工具描述
        function.put("description", p.getToolDescription());

        ObjectNode parameters = function.putObject("parameters");
        parameters.put("type", "object");

        ObjectNode properties = parameters.putObject("properties");

        ObjectNode targetNamespace = properties.putObject("target_namespace");
        targetNamespace.put("type", "string");
        // 获取参数描述
        targetNamespace.put("description", p.getCustomDescription("target_namespace"));

        ArrayNode required = parameters.putArray("required");
        required.add("target_namespace");

        return tool;
    }

    @Override
    public String execute(JsonNode arguments) {
        String targetNamespace = arguments.path("target_namespace").asText().trim();
        this.lastQueriedTarget = targetNamespace;
        int count = ConfigsManager.HISTORY_VIEW_AMOUNT;
        log.info("[Tool][GetChatHistory] 大模型申请查阅目标 [{}] 的近期 {} 条历史记录", targetNamespace, count);
        return ChatAdaptersManager.getHistory(targetNamespace, count);
    }

    @Override
    public String getTextRecord() {
        if (this.lastQueriedTarget == null) {
            return "尝试通过工具获取聊天历史记录，但调用失败;";
        }
        return "调用工具，获取了目标 [" + this.lastQueriedTarget + "] 的历史聊天记录;";
    }
}