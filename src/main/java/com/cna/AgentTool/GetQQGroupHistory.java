package com.cna.AgentTool;

import com.cna.config.ConfigsManager; // 引入你的配置类
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

import static com.cna.Main.GlobalNapcatAdapter;

@Slf4j
public class GetQQGroupHistory implements DefaultAgentToolUnit {

    private final ObjectMapper mapper = new ObjectMapper();

    public GetQQGroupHistory() {
    }

    @Override
    public String getName() {
        return "get_qq_group_history";
    }

    @Override
    public ObjectNode getToolDefinition() {
        ObjectNode tool = mapper.createObjectNode();
        tool.put("type", "function");

        ObjectNode function = tool.putObject("function");
        function.put("name", getName());
        function.put("description", "当你发觉当前群聊上下文中信息缺失，需要往前翻阅群聊历史记录以理解对话背景时，调用此工具。系统会自动为你展示该群最近的一批聊天记录。");

        ObjectNode parameters = function.putObject("parameters");
        parameters.put("type", "object");

        ObjectNode properties = parameters.putObject("properties");

        ObjectNode groupId = properties.putObject("group_id");
        groupId.put("type", "string");
        groupId.put("description", "需要查询历史记录的目标QQ群号");

        // 【删除了 count 参数的定义，不让大模型操心这个】

        ArrayNode required = parameters.putArray("required");
        required.add("group_id"); // 现在只要求必填群号

        return tool;
    }

    @Override
    public String execute(JsonNode arguments) {
        try {
            long groupId = Long.parseLong(arguments.path("group_id").asText());

            // 【核心修改】：强制使用 ConfigsManager 里的常量！
            int count = ConfigsManager.HISTORY_VIEW_AMOUNT;

            log.info("[Tool][GetQQGroupHistory] 大模型主动申请查阅群 [{}] 的近期 {} 条历史记录", groupId, count);

            List<String> historyList = GlobalNapcatAdapter.getGroupHistorySync(groupId, count);

            if (historyList == null || historyList.isEmpty()) {
                return "SYSTEM_FEEDBACK: 该群没有最近的历史记录，或物理层获取失败。";
            }

            return "【以下是该群最近的 " + historyList.size() + " 条聊天记录】:\n" + String.join("\n", historyList);

        } catch (NumberFormatException e) {
            log.error("执行 get_qq_group_history 失败: 群号格式错误", e);
            return "ERROR: group_id 必须是有效的数字字符串";
        } catch (Exception e) {
            log.error("执行 get_qq_group_history 发生异常", e);
            return "ERROR: 获取群历史记录失败，底层物理异常: " + e.getMessage();
        }
    }
}