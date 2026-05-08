package com.cna.agent.AgentTool;

import com.cna.config.ToolPromptsManager;
import com.cna.db.MDManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class GetScheduled implements DefaultAgentToolUnit {

    private final ObjectMapper mapper = new ObjectMapper();

    // 标记该工具是否在当前周期内被真正调用过，用于完善记忆记录
    private boolean isCalled = false;

    public GetScheduled() {
    }

    @Override
    public String getName() {
        return "get_scheduled";
    }

    @Override
    public ObjectNode getToolDefinition() {
        ObjectNode tool = mapper.createObjectNode();
        tool.put("type", "function");

        ToolPromptsManager p = new ToolPromptsManager(this.getClass().getName());

        ObjectNode function = tool.putObject("function");
        function.put("name", getName());
        function.put("description", p.getToolDescription());

        ObjectNode parameters = function.putObject("parameters");
        parameters.put("type", "object");

        // 该工具不需要大模型提供任何参数，因此 properties 为空
        parameters.putObject("properties");

        return tool;
    }

    @Override
    public String execute(JsonNode arguments) {
        try {
            this.isCalled = true;
            log.info("[Tool][GetScheduled] 大模型主动申请查阅当前日程安排 (scheduled.md)");

            // 直接利用项目中现有的 MDManager 读取文件内容
            String scheduledContent = MDManager.read("scheduled.md", "");

            if (scheduledContent == null || scheduledContent.trim().isEmpty()) {
                return "SYSTEM_FEEDBACK: 当前 scheduled.md 文件为空，没有正在进行的日程安排或计划。";
            }

            return "【以下是当前的日程安排内容】:\n" + scheduledContent;

        } catch (Exception e) {
            log.error("执行 get_scheduled 发生异常", e);
            return "ERROR: 获取日程安排失败，底层物理异常: " + e.getMessage();
        }
    }

    @Override
    public String getTextRecord() {
        if (!this.isCalled) {
            return "尝试通过工具查看日程安排，但是这个工具之前并没有被调用过;";
        } else {
            return "调用工具，查看了当前的日程安排 (scheduled.md);";
        }
    }
}