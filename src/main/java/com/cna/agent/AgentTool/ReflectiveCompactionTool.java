package com.cna.agent.AgentTool;

import com.cna.config.ToolPromptsManager;
import com.cna.db.MDManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ReflectiveCompactionTool implements DefaultAgentToolUnit {

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String getName() {
        return "reflective_memory_compaction";
    }

    @Override
    public ObjectNode getToolDefinition() {
        ObjectNode tool = mapper.createObjectNode();
        tool.put("type", "function");

        ToolPromptsManager p = new ToolPromptsManager(this.getClass().getName());

        ObjectNode function = tool.putObject("function");
        function.put("name", getName());
        // 【核心说明】：明确告诉大模型这是压缩用的，平时不能用
        function.put("description", p.getToolDescription());

        ObjectNode parameters = function.putObject("parameters");
        parameters.put("type", "object");

        ObjectNode properties = parameters.putObject("properties");

        // 想法压缩
        ObjectNode compactedThoughts = properties.putObject("compacted_thoughts");
        compactedThoughts.put("type", "string");
        compactedThoughts.put("description", p.getCustomDescription("compacted_thoughts"));

        // 日程压缩
        ObjectNode compactedSchedule = properties.putObject("compacted_schedule");
        compactedSchedule.put("type", "string");
        compactedSchedule.put("description", p.getCustomDescription("compacted_schedule"));

        // 设为空数组，代表参数都是可选的，大模型可以按需覆写
        parameters.putArray("required");

        return tool;
    }

    @Override
    public String execute(JsonNode arguments) {

        try {
            String thoughts = arguments.path("compacted_thoughts").asText(null);
            String schedule = arguments.path("compacted_schedule").asText(null);

            StringBuilder resultMsg = new StringBuilder("SUCCESS: 记忆碎片压缩结果：\n");
            boolean modified = false;

            // 覆写 Thoughts
            if (thoughts != null && !thoughts.trim().isEmpty()) {
                MDManager.write("thoughts.md", thoughts);
                resultMsg.append("- 想法认知 (thoughts.md) 已成功覆写。\n");
                modified = true;
            }

            // 覆写 Scheduled
            if (schedule != null && !schedule.trim().isEmpty()) {
                MDManager.write("scheduled.md", schedule);
                resultMsg.append("- 日程任务 (scheduled.md) 已成功覆写。\n");
                modified = true;
            }

            if (!modified) {
                return "WARNING: 你调用了压缩工具，但没有传入任何有效的压缩后内容，文件未发生变化。";
            }

            log.info("[Tool][ReflectiveCompaction] 代理完成了记忆压缩与覆写操作。");
            return resultMsg.toString();

        } catch (Exception e) {
            log.error("执行 reflective_memory_compaction 发生异常", e);
            return "ERROR: 覆写压缩失败，底层异常: " + e.getMessage();
        }
    }

    @Override
    public String getTextRecord(){
        return "在深度反思期，对记忆和计划进行了提炼和碎片压缩;";
    }

    @Override
    public boolean isAutoLoad(){
        return true;
    }
}