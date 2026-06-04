package com.cna.agent.AgentTool;

import com.cna.agent.SleepManager;
import com.cna.config.ToolPromptsManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

/**
 * 查询 Agent 当前的休眠时间段设置。
 */
@Slf4j
public class GetSleepTimeTool implements DefaultAgentToolUnit {

    private static final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String getName() {
        return "get_sleep_time";
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
        String window = SleepManager.getInstance().getSleepWindow();
        boolean sleeping = SleepManager.getInstance().isSleeping();
        String status = sleeping ? "当前正处于休眠中" : "当前处于唤醒状态";
        return String.format("%s。每日休眠时间段: %s", status, window);
    }

    @Override
    public String getTextRecord() {
        return "调用了get_sleep_time，查询了休眠时间设置";
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
