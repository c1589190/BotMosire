package com.cna.agent.AgentTool;

import com.cna.ChatAdapterManager;
import com.cna.config.ConfigsManager;
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

        ObjectNode function = tool.putObject("function");
        function.put("name", getName());
        function.put("description", "当你发觉当前上下文中信息缺失，需要查阅历史聊天记录时调用此工具。支持格式：QQ群聊传 'qq_group:groupId'，QQ私聊传 'qqid:userId'，Discord频道传 'discord_guild:guildId:channelId'，Discord私聊传 'discord_dm:userId'。");

        ObjectNode parameters = function.putObject("parameters");
        parameters.put("type", "object");

        ObjectNode properties = parameters.putObject("properties");

        ObjectNode targetNamespace = properties.putObject("target_namespace");
        targetNamespace.put("type", "string");
        targetNamespace.put("description", "需要查询历史记录的目标标识符。必须包含前缀！");

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
        return ChatAdapterManager.getHistory(targetNamespace, count);
    }

    @Override
    public String getTextRecord() {
        if (this.lastQueriedTarget == null) {
            return "尝试通过工具获取聊天历史记录，但调用失败;";
        }
        return "调用工具，获取了目标 [" + this.lastQueriedTarget + "] 的历史聊天记录;";
    }
}
