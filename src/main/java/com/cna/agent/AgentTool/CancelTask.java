package com.cna.agent.AgentTool;

import com.cna.agent.AgentTask.DefaultAgentTaskUnit;
import com.cna.agent.LivingLoop;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

/**
 * 让 Agent 取消队列中的任意任务。
 *
 * Agent 有权判断某个待处理任务不再有意义——可能是情况变了、
 * 被更高优先级的任务覆盖了、或者它当初的判断已经过时。
 * 不能取消当前正在执行的任务（应该用 finish_task 正常结束）。
 */
@Slf4j
public class CancelTask implements DefaultAgentToolUnit {

    private static final ObjectMapper mapper = new ObjectMapper();
    private final LivingLoop engine;
    private String lastRecord = "";

    public CancelTask(LivingLoop engine) {
        this.engine = engine;
    }

    @Override
    public String getName() {
        return "cancel_task";
    }

    @Override
    public ObjectNode getToolDefinition() {
        ObjectNode toolDef = mapper.createObjectNode();
        toolDef.put("type", "function");

        ObjectNode function = toolDef.putObject("function");
        function.put("name", getName());
        function.put("description",
                "取消任务队列中的某个待处理任务。传入任务编号（[#N]，见任务队列概览）。"
                + "当你判断某个排队的任务已经不再有意义、被其他任务覆盖、或情况已变化时使用。"
                + "注意：不能取消当前正在执行的任务——如果当前任务需要终止，请使用 finish_task。");

        ObjectNode params = function.putObject("parameters");
        params.put("type", "object");

        ObjectNode properties = params.putObject("properties");

        ObjectNode idProp = properties.putObject("task_id");
        idProp.put("type", "integer");
        idProp.put("description", "要取消的任务在队列中的展示编号 [#N]。可先调用 get_task_queue 确认编号。不能取消正在执行的任务 [#1]。");

        ObjectNode reasonProp = properties.putObject("reason");
        reasonProp.put("type", "string");
        reasonProp.put("description", "取消原因，供日志和记忆记录");

        ArrayNode required = params.putArray("required");
        required.add("task_id");
        required.add("reason");

        return toolDef;
    }

    @Override
    public String execute(JsonNode arguments) {
        try {
            int taskId = arguments.path("task_id").asInt(-1);
            String reason = arguments.path("reason").asText("未提供原因");

            if (taskId <= 0) {
                return "无效的任务编号，请提供队列中显示的有效 [#N] 编号。";
            }
            if (taskId == 1) {
                return "不能取消当前正在执行的任务 [#1]。如果你需要终止当前任务，请使用 finish_task。";
            }

            LivingLoop.TaskQueueSnapshot snapshot = engine.getTaskQueueSnapshot();
            int idCounter = 1; // [#1] 是 executing task

            DefaultAgentTaskUnit target = null;
            for (DefaultAgentTaskUnit t : snapshot.pendingTasks()) {
                idCounter++;
                t.setDisplayId(idCounter);
                if (t.getDisplayId() == taskId) {
                    target = t;
                    break;
                }
            }

            if (target == null) {
                return "未找到编号为 [#" + taskId + "] 的待处理任务，请先调用 get_task_queue 确认当前队列状态。";
            }

            boolean removed = engine.cancelPendingTask(target.getUUID());
            if (removed) {
                this.lastRecord = "取消了待处理任务 [#" + taskId + "] \"" + target.getTaskName()
                        + "\"，原因: " + reason;
                log.info("[CancelTask] {} 取消了任务 [#{}]: {}, 原因: {}",
                        target.getTaskName(), taskId, target.getTaskName(), reason);
                return "已取消任务 [#" + taskId + "] " + target.getTaskName() + "。原因: " + reason;
            } else {
                return "取消失败：任务 [#" + taskId + "] 可能已不在队列中。";
            }

        } catch (Exception e) {
            log.error("[CancelTask] 执行异常", e);
            return "取消任务失败: " + e.getMessage();
        }
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
