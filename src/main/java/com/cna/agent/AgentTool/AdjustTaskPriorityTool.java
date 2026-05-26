package com.cna.agent.AgentTool;

import com.cna.agent.LivingLoop;
import com.cna.config.ConfigsManager;
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
        function.put("name", getName());
        function.put("description",
                "调整任务队列中指定任务的优先级权重。传入任务编号（[#N]，见任务队列概览）和调整量（正数=降低优先级延后处理，负数=提高优先级提前处理）。"
                + "单次调整幅度上限为 ±" + String.format("%.1f", range) + "，优先级最低不低于 0.1。"
                + "当你判断当前正在执行的任务不如待处理队列中的某个任务紧急，或某个挂起任务需要被推迟时使用。");

        ObjectNode params = function.putObject("parameters");
        params.put("type", "object");

        ObjectNode properties = params.putObject("properties");

        ObjectNode taskIdProp = properties.putObject("task_id");
        taskIdProp.put("type", "integer");
        taskIdProp.put("description", "目标任务在队列中的展示编号 [#N]，例如 1、2、3。可先调用 get_task_queue 确认编号。");

        ObjectNode deltaProp = properties.putObject("delta");
        deltaProp.put("type", "number");
        deltaProp.put("description", "优先级调整量。正数=降低优先级（往后排），负数=提高优先级（往前排）。范围 [" + String.format("%.1f", -range) + ", " + String.format("%.1f", range) + "]");

        ArrayNode required = params.putArray("required");
        required.add("task_id");
        required.add("delta");

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
