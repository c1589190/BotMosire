package com.cna.agent.AgentTool;

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

        ObjectNode function = tool.putObject("function");
        function.put("name", getName());
        // 【核心说明】：明确告诉大模型这是压缩用的，平时不能用
        function.put("description", "【最高权限工具】仅在系统触发的'定时反思'阶段可用。用于将你过去积累的琐碎想法、日程和兴趣规则进行总结、提炼和压缩，并完全覆写旧文件。这有助于为你清理大脑内存。你可以选择性地覆写其中一个或多个文件。");

        ObjectNode parameters = function.putObject("parameters");
        parameters.put("type", "object");

        ObjectNode properties = parameters.putObject("properties");

        // 想法压缩
        ObjectNode compactedThoughts = properties.putObject("compacted_thoughts");
        compactedThoughts.put("type", "string");
        compactedThoughts.put("description", "(可选) 提炼压缩后的最新内省与核心认知。留空则不修改 thoughts.md。");

        // 日程压缩
        ObjectNode compactedSchedule = properties.putObject("compacted_schedule");
        compactedSchedule.put("type", "string");
        compactedSchedule.put("description", "(可选) 清理掉已完成任务后，重新整理的最新待办日程。留空则不修改 scheduled.md。");

        // 兴趣压缩
        ObjectNode compactedInterests = properties.putObject("compacted_interests");
        compactedInterests.put("type", "string");
        compactedInterests.put("description", "(可选) 梳理去重后的最新注意力雷达与过滤规则。留空则不修改 interests.md。");

        // 设为空数组，代表参数都是可选的，大模型可以按需覆写
        parameters.putArray("required");

        return tool;
    }

    @Override
    public String execute(JsonNode arguments) {

        try {
            String thoughts = arguments.path("compacted_thoughts").asText(null);
            String schedule = arguments.path("compacted_schedule").asText(null);
            String interests = arguments.path("compacted_interests").asText(null);

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

            // 覆写 Interests
            if (interests != null && !interests.trim().isEmpty()) {
                MDManager.write("interests.md", interests);
                resultMsg.append("- 注意力雷达 (interests.md) 已成功覆写。\n");
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
        return false;
    }
}