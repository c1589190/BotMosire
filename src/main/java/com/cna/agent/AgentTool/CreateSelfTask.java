package com.cna.agent.AgentTool;

import com.cna.agent.AgentTask.SelfTask;
import com.cna.agent.LivingLoop;
import com.cna.config.ToolPromptsManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 让 Agent 为自己创建任意任务并投递到执行队列。
 *
 * 这是 Agent 自运行任务管理体系的核心工具——Agent 在认知循环中
 * 感知到需要做的事、想调查的问题、想跟进的方向时，用它把想法变成
 * 一个实实在在会被执行的 SelfTask。
 */
@Slf4j
public class CreateSelfTask implements DefaultAgentToolUnit {

    private static final ObjectMapper mapper = new ObjectMapper();
    private final LivingLoop engine;
    private String lastRecord = "";

    public CreateSelfTask(LivingLoop engine) {
        this.engine = engine;
    }

    @Override
    public String getName() {
        return "create_self_task";
    }

    @Override
    public ObjectNode getToolDefinition() {
        ObjectNode toolDef = mapper.createObjectNode();
        toolDef.put("type", "function");

        ToolPromptsManager p = new ToolPromptsManager(this.getClass().getName());

        ObjectNode function = toolDef.putObject("function");
        function.put("name", getName());
        function.put("description", p.getToolDescription());

        ObjectNode params = function.putObject("parameters");
        params.put("type", "object");

        ObjectNode properties = params.putObject("properties");

        ObjectNode descProp = properties.putObject("task_description");
        descProp.put("type", "string");
        descProp.put("description", p.getCustomDescription("task_description"));

        ObjectNode priProp = properties.putObject("priority");
        priProp.put("type", "number");
        priProp.put("description", p.getCustomDescription("priority"));

        ObjectNode srcProp = properties.putObject("sources");
        srcProp.put("type", "array");
        srcProp.put("description", p.getCustomDescription("sources"));
        ObjectNode srcItems = srcProp.putObject("items");
        srcItems.put("type", "string");

        ObjectNode parentProp = properties.putObject("parent_task_display_id");
        parentProp.put("type", "integer");
        parentProp.put("description", p.getCustomDescription("parent_task_display_id"));

        ArrayNode required = params.putArray("required");
        required.add("task_description");

        params.put("additionalProperties", false);
        return toolDef;
    }

    @Override
    public String execute(JsonNode arguments) {
        try {
            String taskDescription = arguments.path("task_description").asText();
            if (taskDescription == null || taskDescription.isBlank()) {
                return "无法创建任务：task_description 不能为空。";
            }

            double priority = arguments.path("priority").asDouble(3.0);
            priority = Math.max(0.1, priority);

            // 解析 sources
            List<String> sources = new ArrayList<>();
            JsonNode srcNode = arguments.path("sources");
            if (srcNode.isArray()) {
                for (JsonNode s : srcNode) {
                    String src = s.asText();
                    if (src != null && !src.isBlank()) {
                        sources.add(src);
                    }
                }
            }
            if (sources.isEmpty()) {
                sources.add("system:self");
            }

            // 解析父任务
            UUID parentId = null;
            int parentDisplayId = arguments.path("parent_task_display_id").asInt(-1);
            if (parentDisplayId > 0) {
                parentId = resolveParentUUID(parentDisplayId);
            }

            SelfTask task = new SelfTask(taskDescription, sources, parentId);
            task.setPriority(priority);

            engine.pushTask(task);

            this.lastRecord = "创建了自主任务: \"" + taskDescription + "\"（来源: " + String.join(", ", sources) + "）";

            String parentInfo = parentId != null ? "，父任务 [#" + parentDisplayId + "]" : "";
            return String.format(
                    "已创建自主任务，任务描述: \"%s\"，优先级: %.1f，来源: %s%s。任务将在队列中按优先级等待执行。",
                    taskDescription, priority, String.join(", ", sources), parentInfo);

        } catch (Exception e) {
            log.error("[CreateSelfTask] 执行异常", e);
            return "创建自主任务失败: " + e.getMessage();
        }
    }

    /** 根据 displayId 解析父任务 UUID */
    private UUID resolveParentUUID(int displayId) {
        LivingLoop.TaskQueueSnapshot snapshot = engine.getTaskQueueSnapshot();
        int idCounter = 0;

        if (snapshot.executingTask() != null) {
            snapshot.executingTask().setDisplayId(++idCounter);
            if (snapshot.executingTask().getDisplayId() == displayId) {
                return snapshot.executingTask().getUUID();
            }
        }
        for (var t : snapshot.pendingTasks()) {
            t.setDisplayId(++idCounter);
            if (t.getDisplayId() == displayId) {
                return t.getUUID();
            }
        }
        return null;
    }

    @Override
    public String getTextRecord() {
        return this.lastRecord;
    }

    @Override
    public boolean isAutoLoad() {
        return true;
    }

    @Override
    public boolean isAutoMemory() {
        return true;
    }
}
