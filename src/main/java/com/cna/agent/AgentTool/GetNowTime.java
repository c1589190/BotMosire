package com.cna.agent.AgentTool;

import com.cna.Utils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

/**
 * 获取当前精确时间（精确到秒）
 * 当 LLM 需要知道具体当前时分秒时调用此工具，避免 prompt 中注入精确时间破坏 DeepSeek KV-cache。
 */
@Slf4j
public class GetNowTime implements DefaultAgentToolUnit {

    private static final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String getName() {
        return "get_now_time";
    }

    @Override
    public ObjectNode getToolDefinition() {
        ObjectNode toolDef = mapper.createObjectNode();
        toolDef.put("type", "function");

        ObjectNode function = toolDef.putObject("function");
        function.put("name", getName());
        function.put("description",
                "获取当前系统的精确时间（精确到秒，格式如 2026年4月22日22:30:00）。"
                + "当你需要知道具体的时分秒来执行定时任务、安排日程或记录时间节点时调用此工具。");

        ObjectNode params = function.putObject("parameters");
        params.put("type", "object");

        ObjectNode properties = params.putObject("properties");
        // 无参数工具，但仍需要空的 properties 和 required
        ArrayNode required = params.putArray("required");
        // required 为空数组

        return toolDef;
    }

    @Override
    public String execute(JsonNode arguments) {
        String preciseTime = Utils.getNowPrecise();
        log.info("[GetNowTime] 返回当前精确时间: {}", preciseTime);
        return "当前系统精确时间: " + preciseTime;
    }

    @Override
    public String getTextRecord() {
        return "调用了get_now_time，获取了当前精确时间";
    }

    @Override
    public boolean isAutoLoad() {
        return true;
    }

    @Override
    public boolean isAutoMemory() {
        return false; // 获取时间不需要写入短期记忆
    }
}