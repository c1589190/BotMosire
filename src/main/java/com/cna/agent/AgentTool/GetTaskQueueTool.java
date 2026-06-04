package com.cna.agent.AgentTool;

import com.cna.agent.LivingLoop;
import com.cna.config.ToolPromptsManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

/**
 * 查询 Agent 当前任务队列的完整详情。
 */
@Slf4j
public class GetTaskQueueTool implements DefaultAgentToolUnit {

    private static final ObjectMapper mapper = new ObjectMapper();
    private final LivingLoop engine;

    public GetTaskQueueTool(LivingLoop engine) {
        this.engine = engine;
    }

    @Override
    public String getName() {
        return "get_task_queue";
    }

    @Override
    public ObjectNode getToolDefinition() {
        ObjectNode toolDef = mapper.createObjectNode();
        toolDef.put("type", "function");

        ObjectNode function = toolDef.putObject("function");
        ToolPromptsManager p = new ToolPromptsManager(this.getClass().getName());
        function.put("name", getName());
        function.put("description", p.getToolDescription());

        ObjectNode params = function.putObject("parameters");
        params.put("type", "object");
        params.putObject("properties");
        params.putArray("required");

        params.put("additionalProperties", false);
        return toolDef;
    }

    @Override
    public String execute(JsonNode arguments) {
        return engine.buildTaskQueueDetail();
    }

    @Override
    public String getTextRecord() {
        return "调用了get_task_queue，查询了任务队列详情";
    }

    @Override
    public boolean isAutoLoad() {
        return true;
    }

    @Override
    public boolean isAutoMemory() {
        return false;
    }
}
