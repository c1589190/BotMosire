package com.cna.agent.AgentTool;

import com.cna.config.ToolPromptsManager;
import com.cna.db.MDManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Slf4j
public class UpdateScheduled implements DefaultAgentToolUnit {

    private final ObjectMapper mapper = new ObjectMapper();
    private static final String TARGET_FILE = "scheduled.md";

    @Override
    public String getName() {
        return "add_scheduled_task"; // 语义改为“增加”
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

        ObjectNode properties = parameters.get("properties") == null ? parameters.putObject("properties") : (ObjectNode) parameters.get("properties");

        ObjectNode taskItem = properties.putObject("task_item");
        taskItem.put("type", "string");
        taskItem.put("description", p.getCustomDescription("task_item"));

        ArrayNode required = parameters.putArray("required");
        required.add("task_item");

        return tool;
    }

    @Override
    public String execute(JsonNode arguments) {
        try {
            String taskItem = arguments.path("task_item").asText();
            if (taskItem == null || taskItem.trim().isEmpty()) {
                return "ERROR: 任务内容不能为空。";
            }

            // 格式化：使用短横线列表格式，并带上创建时间
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
            String contentToAppend = String.format("\n- [ ] %s (创建于: %s)", taskItem, timestamp);

            log.info("[Tool][AddScheduled] 记录新任务: {}", taskItem);

            boolean success = MDManager.append(TARGET_FILE, contentToAppend);

            if (success) {
                return "SUCCESS: 新任务已添加到日程表末尾。";
            } else {
                return "ERROR: 物理写入失败。";
            }
        } catch (Exception e) {
            log.error("执行 add_scheduled_task 发生异常", e);
            return "ERROR: 执行失败: " + e.getMessage();
        }
    }

    @Override
    public String getTextRecord(){
        return "在日程表中新增了任务记录;";
    }
}