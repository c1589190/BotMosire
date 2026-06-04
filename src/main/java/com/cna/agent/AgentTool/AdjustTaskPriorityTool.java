package com.cna.agent.AgentTool;

import com.cna.agent.LivingLoop;
import com.cna.config.ConfigsManager;
import com.cna.config.ToolPromptsManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

/**
 * 允许 Agent 在认知循环中手动调整特定任务的优先级权重。
 * 通过任务队列中展示的 [#N] 编号引用目标任务。
 */
@Slf4j
public class AdjustTaskPriorityTool implements DefaultAgentToolUnit {

    private static final ObjectMapper mapper = new ObjectMapper();
    private final LivingLoop engine;

    public AdjustTaskPriorityTool(LivingLoop engine) {
        this.engine = engine;
    }

    @Override
    public String getName() {
        return "adjust_task_priority";
    }

    @Override
    public ObjectNode getToolDefinition() {
        ObjectNode toolDef = mapper.createObjectNode();
        toolDef.put("type", "function");

        double range = ConfigsManager.TASK_PRIORITY_ADJUSTMENT_RANGE;

        ObjectNode function = toolDef.putObject("function");
        ToolPromptsManager p = new ToolPromptsManager(this.getClass().getName());
        function.put("name", getName());
        function.put("description",
                p.getToolDescription()
                + " 单次调整幅度上限为 ±" + String.format("%.1f", range) + "，优先级最低不低于 0.1。");

        ObjectNode params = function.putObject("parameters");
        params.put("type", "object");

        ObjectNode properties = params.putObject("properties");

        ObjectNode taskIdProp = properties.putObject("task_id");
        taskIdProp.put("type", "integer");
        taskIdProp.put("description", p.getCustomDescription("task_id"));

        ObjectNode deltaProp = properties.putObject("delta");
        deltaProp.put("type", "number");
        deltaProp.put("description", p.getCustomDescription("delta")
                + " 范围 [" + String.format("%.1f", -range) + ", " + String.format("%.1f", range) + "]");

        ArrayNode required = params.putArray("required");
        required.add("task_id");
        required.add("delta");

        params.put("additionalProperties", false);
        return toolDef;
    }

    @Override
    public String execute(JsonNode arguments) {
        int taskId = arguments.path("task_id").asInt(-1);
        double delta = arguments.path("delta").asDouble(0.0);

        if (taskId <= 0) {
            return "无效的任务编号，请提供队列中显示的有效 [#N] 编号。";
        }

        String result = engine.adjustTaskPriority(taskId, delta);
        log.info("[AdjustTaskPriority] Agent 调整任务 [#{}] 优先级: delta={}", taskId, delta);
        return result;
    }

    @Override
    public String getTextRecord() {
        return "调用了adjust_task_priority，手动调整了某个任务的优先级";
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
