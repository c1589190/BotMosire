package com.cna.agent.AgentTool;

import com.cna.config.ConfigsManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

import static com.cna.Main.GlobalNapcatAdapter;

@Slf4j
public class GetChatHistory implements DefaultAgentToolUnit {

    private final ObjectMapper mapper = new ObjectMapper();

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
        function.put("description", "当你发觉当前上下文中信息缺失，需要查阅历史聊天记录时调用此工具。查阅群历史请传入 source（如 'qq_group:12345'），查阅某人的私聊历史请传入 role（如 'qqid:12345'）。");

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
        try {
            String targetNamespace = arguments.path("target_namespace").asText().trim();
            this.lastQueriedTarget = targetNamespace;

            int count = ConfigsManager.HISTORY_VIEW_AMOUNT;
            List<String> historyList = null;
            String chatTypeDesc = "";

            log.info("[Tool][GetChatHistory] 大模型申请查阅目标 [{}] 的近期 {} 条历史记录", targetNamespace, count);

            if (targetNamespace.startsWith("qq_group:")) {
                long groupId = Long.parseLong(targetNamespace.substring(9));
                historyList = GlobalNapcatAdapter.getGroupHistorySync(groupId, count);
                chatTypeDesc = "群聊";
            } else if (targetNamespace.startsWith("qqid:")) {
                long userId = Long.parseLong(targetNamespace.substring(5));
                historyList = GlobalNapcatAdapter.getFriendHistorySync(userId, count);
                chatTypeDesc = "私聊";
            } else {
                log.warn("[Tool][GetChatHistory] 格式异常: {}", targetNamespace);
                return "ERROR: 无法识别的 target_namespace 格式。必须带有 'qq_group:' 或 'qqid:' 前缀。";
            }

            if (historyList == null || historyList.isEmpty()) {
                return "SYSTEM_FEEDBACK: 该目标没有最近的历史记录，或物理层获取失败。";
            }

            return String.format("【以下是目标 %s 的最近 %d 条%s记录】:\n%s",
                    targetNamespace, historyList.size(), chatTypeDesc, String.join("\n", historyList));

        } catch (NumberFormatException e) {
            log.error("执行 get_chat_history 失败: ID 解析错误", e);
            return "ERROR: target_namespace 前缀后的 ID 必须是数字";
        } catch (Exception e) {
            log.error("执行 get_chat_history 发生异常", e);
            return "ERROR: 获取历史记录失败，底层异常: " + e.getMessage();
        }
    }

    @Override
    public String getTextRecord(){
        if (this.lastQueriedTarget == null) {
            return "尝试通过工具获取聊天历史记录，但调用失败;";
        } else {
            return "调用工具，获取了目标 [" + this.lastQueriedTarget + "] 的历史聊天记录;";
        }
    }
}