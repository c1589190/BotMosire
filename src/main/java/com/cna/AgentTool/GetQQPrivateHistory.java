package com.cna.AgentTool;

import com.cna.config.ConfigsManager; // 引入配置类
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

import static com.cna.Main.GlobalNapcatAdapter;

@Slf4j
public class GetQQPrivateHistory implements DefaultAgentToolUnit {

    private final ObjectMapper mapper = new ObjectMapper();

    private long UserID = -1;

    public GetQQPrivateHistory() {
    }

    @Override
    public String getName() {
        return "get_qq_private_history";
    }

    @Override
    public ObjectNode getToolDefinition() {
        ObjectNode tool = mapper.createObjectNode();
        tool.put("type", "function");

        ObjectNode function = tool.putObject("function");
        function.put("name", getName());
        function.put("description", "当你发觉当前私聊上下文中信息缺失，需要往前翻阅私聊（好友）历史记录以理解对话背景时，调用此工具。系统会自动为你展示最近的一批聊天记录。");

        ObjectNode parameters = function.putObject("parameters");
        parameters.put("type", "object");

        ObjectNode properties = parameters.putObject("properties");

        ObjectNode userId = properties.putObject("user_id");
        userId.put("type", "string");
        userId.put("description", "需要查询历史记录的目标好友QQ号");

        ArrayNode required = parameters.putArray("required");
        required.add("user_id"); // 现在只要求必填QQ号

        return tool;
    }

    @Override
    public String execute(JsonNode arguments) {
        try {
            this.UserID = Long.parseLong(arguments.path("user_id").asText());

            // 【核心修改】：强制使用 ConfigsManager 里的常量！
            int count = ConfigsManager.HISTORY_VIEW_AMOUNT;

            log.info("[Tool][GetQQPrivateHistory] 大模型主动申请查阅私聊对象 [{}] 的近期 {} 条历史记录", this.UserID, count);

            List<String> historyList = GlobalNapcatAdapter.getFriendHistorySync(this.UserID, count);

            if (historyList == null || historyList.isEmpty()) {
                return "SYSTEM_FEEDBACK: 与该用户的私聊没有最近的历史记录，或物理层获取失败。";
            }

            return "【以下是你与该用户最近的 " + historyList.size() + " 条聊天记录】:\n" + String.join("\n", historyList);

        } catch (NumberFormatException e) {
            log.error("执行 get_qq_private_history 失败: QQ号格式错误", e);
            return "ERROR: user_id 必须是有效的数字字符串";
        } catch (Exception e) {
            log.error("执行 get_qq_private_history 发生异常", e);
            return "ERROR: 获取私聊历史记录失败，底层物理异常: " + e.getMessage();
        }
    }

    @Override
    public String getTextRecord(){
        if(this.UserID == -1){
            //该工具没有调用记录
            return "尝试通过工具获取私聊历史记录，但是这个工具之前并没有被调用过;";
        } else {
            return "调用工具，获取了和QQ号为" + this.UserID + "的用户的历史聊天记录;";
        }
    }
}