package com.cna.agent.AgentTool;

import com.cna.apcore.model.CognitivePrepareUnit;
import com.cna.apcore.pool.CognitivePreparePool;
import com.cna.config.ToolPromptsManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * V4 版 CreatePendingChatTask — 不再依赖旧 LivingLoop 任务队列，
 * 直接将挂起任务作为内源 CognitivePrepareUnit 推入 V4 认知准备池。
 *
 * 当目标来源有新消息到达时，ChatMessageActionDeveloper 会将其聚合为
 * 外源单元，与池中已有的挂起单元自然竞争注意力。
 */
@Slf4j
public class CreatePendingChatTask implements DefaultAgentToolUnit {

    private final ObjectMapper mapper = new ObjectMapper();
    /** V4 认知准备池引用，用于直接推送内源挂起任务 */
    private final CognitivePreparePool pool;
    private String lastRecord = "";

    public CreatePendingChatTask(CognitivePreparePool pool) {
        this.pool = pool;
    }

    @Override
    public String getName() {
        return "create_pending_chat_task";
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

        ObjectNode properties = parameters.putObject("properties");

        properties.putObject("target_source").put("type", "string").put("description", p.getCustomDescription("target_source"));
        properties.putObject("target_source_name").put("type", "string").put("description", p.getCustomDescription("target_source_name"));
        properties.putObject("target_role").put("type", "string").put("description", p.getCustomDescription("target_role"));
        properties.putObject("target_role_name").put("type", "string").put("description", p.getCustomDescription("target_role_name"));
        properties.putObject("reason").put("type", "string").put("description", p.getCustomDescription("reason"));

        ArrayNode required = parameters.putArray("required");
        required.add("target_source").add("target_role").add("reason");

        return tool;
    }

    @Override
    public String execute(JsonNode arguments) {
        try {
            String source = arguments.path("target_source").asText();
            String sourceName = arguments.path("target_source_name").asText("未知来源");
            String role = arguments.path("target_role").asText();
            String roleName = arguments.path("target_role_name").asText("未知目标");
            String reason = arguments.path("reason").asText();

            // 构建挂起任务的文本描述
            String taskText = "等待 " + roleName + "(" + role + ") 在 " + sourceName + "(" + source + ") 的新消息。\n理由是: " + reason;

            // ★ V4：直接推入认知准备池作为内源单元
            CognitivePrepareUnit unit = CognitivePrepareUnit.create(
                    taskText,
                    List.of(source + ":" + role),
                    0.3  // 较低初始 SE，等目标有新消息时自然抬升
            );
            unit.setEndogenous(true);  // 标记为自生成任务

            pool.push(unit);

            this.lastRecord = "主动等待 " + roleName + "(" + role + ") 在 " + sourceName + "(" + source + ") 的新消息, 理由是：" + reason;

            log.info("[Tool][CreatePendingTask] ✅ 已将挂起任务推入 V4 认知准备池: target={}, source={}",
                    role, source);

            return "SYSTEM_FEEDBACK: 成功！挂起任务已进入 V4 认知准备池，目标有新消息时将自动竞争注意力。";

        } catch (Exception e) {
            log.error("执行 create_pending_chat_task 发生异常", e);
            return "ERROR: 创建挂起任务失败，异常: " + e.getMessage();
        }
    }

    @Override
    public String getTextRecord() {
        return this.lastRecord;
    }
}
