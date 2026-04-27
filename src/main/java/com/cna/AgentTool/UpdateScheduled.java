package com.cna.AgentTool;

import com.cna.db.MDManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class UpdateScheduled implements DefaultAgentToolUnit {

    private final ObjectMapper mapper = new ObjectMapper();
    // 强制锁死目标文件，隔离日程数据与思想数据
    private static final String TARGET_FILE = "scheduled.md";

    public UpdateScheduled() {
    }

    @Override
    public String getName() {
        return "update_scheduled_tasks";
    }

    @Override
    public ObjectNode getToolDefinition() {
        ObjectNode tool = mapper.createObjectNode();
        tool.put("type", "function");

        ObjectNode function = tool.putObject("function");
        function.put("name", getName());
        // 【核心说明书】：定义日程调度的物理边界和覆写规则
        function.put("description", "当你需要规划未来的行动步骤、更新待办事项或调整已有计划时调用此工具。注意：此操作为全量覆写，请务必在参数中输入合并后的、包含所有未完成计划的完整最新日程表，不要遗漏之前的任务。");

        ObjectNode parameters = function.putObject("parameters");
        parameters.put("type", "object");

        ObjectNode properties = parameters.putObject("properties");

        ObjectNode newSchedule = properties.putObject("new_schedule");
        newSchedule.put("type", "string");
        newSchedule.put("description", "你最新、最完整的待办事项列表。必须包含明确的步骤和目标，建议使用 Markdown 列表格式，以保持结构清晰。");

        ArrayNode required = parameters.putArray("required");
        required.add("new_schedule");

        return tool;
    }

    @Override
    public String execute(JsonNode arguments) {
        try {
            String newSchedule = arguments.path("new_schedule").asText();

            log.info("[Tool][UpdateScheduled] 代理发起调度变更，正在覆写 {}...", TARGET_FILE);
            log.debug("写入内容预览: {}", newSchedule.length() > 50 ? newSchedule.substring(0, 50) + "..." : newSchedule);

            // 直接调用封装好的物理覆写方法
            boolean success = MDManager.write(TARGET_FILE, newSchedule);

            if (success) {
                return "SUCCESS: 日程表已成功更新并物理归档。请在后续循环中严格按照此计划执行。";
            } else {
                return "ERROR: 物理写入失败，请检查文件系统 I/O 状态。";
            }

        } catch (Exception e) {
            log.error("执行 update_scheduled_tasks 发生异常", e);
            return "ERROR: 执行更新调度失败，底层异常: " + e.getMessage();
        }
    }

    @Override
    public String getTextRecord(){
        return "为自己更新了定时任务列表;";
    }
}