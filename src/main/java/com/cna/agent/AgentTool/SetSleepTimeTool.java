package com.cna.agent.AgentTool;

import com.cna.agent.SleepManager;
import com.cna.config.ToolPromptsManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

/**
 * 设置 Agent 每日休眠时间段。
 * Agent 可主动调用此工具设定每天的睡觉时间窗口，在此期间不再接收任何外部 Input。
 */
@Slf4j
public class SetSleepTimeTool implements DefaultAgentToolUnit {

    private static final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String getName() {
        return "set_sleep_time";
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

        ObjectNode properties = params.putObject("properties");
        ObjectNode startProp = properties.putObject("start_time");
        startProp.put("type", "string");
        startProp.put("description", p.getCustomDescription("start_time"));

        ObjectNode endProp = properties.putObject("end_time");
        endProp.put("type", "string");
        endProp.put("description", p.getCustomDescription("end_time"));

        params.putArray("required").add("start_time").add("end_time");

        params.put("additionalProperties", false);
        return toolDef;
    }

    @Override
    public String execute(JsonNode arguments) {
        String startTime = arguments.path("start_time").asText("").trim();
        String endTime = arguments.path("end_time").asText("").trim();

        if (startTime.isEmpty() || startTime.equals("0") ||
                endTime.isEmpty() || endTime.equals("0")) {
            SleepManager.getInstance().clearSleepWindow();
            return "休眠时间段已清空，Agent 将始终保持唤醒状态。";
        }

        if (SleepManager.parseTime(startTime) < 0 || SleepManager.parseTime(endTime) < 0) {
            return "错误：时间格式无效，请使用 HH:mm 格式（如 \"02:00\"、\"22:30\"）。";
        }

        SleepManager.getInstance().setSleepWindow(startTime, endTime);
        log.info("[SetSleepTimeTool] Agent 设置休眠时间段: {} ~ {}", startTime, endTime);
        return String.format("休眠时间段已设置为 %s ~ %s。每天到达 %s 自动入睡，%s 自动唤醒。",
                startTime, endTime, startTime, endTime);
    }

    @Override
    public String getTextRecord() {
        return "调用了set_sleep_time，设置了每日休眠时间段: " + SleepManager.getInstance().getSleepWindow();
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
