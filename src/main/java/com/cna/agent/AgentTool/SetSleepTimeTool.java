package com.cna.agent.AgentTool;

import com.cna.agent.SleepManager;
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
        function.put("name", getName());
        function.put("description",
                "设置 Agent 每日的休眠时间段（24小时制）。"
                + "设定后，每天到达开始时间自动进入休眠，到达结束时间自动唤醒。"
                + "休眠期间 Agent 不接收或处理任何外部输入（聊天消息、Web事件等）。"
                + "支持跨天时间段（如 22:00 ~ 06:00 表示晚上10点到第二天早上6点）。"
                + "传入空字符串或 \"0\" 可清空休眠设置，让 Agent 始终保持唤醒。"
                + "适合设定固定的夜间休息时间、或避开不希望被打扰的时段。");

        ObjectNode params = function.putObject("parameters");
        params.put("type", "object");

        ObjectNode properties = params.putObject("properties");
        ObjectNode startProp = properties.putObject("start_time");
        startProp.put("type", "string");
        startProp.put("description", "休眠开始时间，格式 HH:mm（如 \"02:00\"、\"22:30\"）。传空字符串或\"0\"则清空休眠设置。");

        ObjectNode endProp = properties.putObject("end_time");
        endProp.put("type", "string");
        endProp.put("description", "休眠结束时间，格式 HH:mm（如 \"08:00\"、\"06:00\"）。须与 start_time 同时提供。");

        params.putArray("required").add("start_time").add("end_time");

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
