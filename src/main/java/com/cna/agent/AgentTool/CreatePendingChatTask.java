package com.cna.agent.AgentTool;

import com.cna.agent.AgentTask.ChatTask;
import com.cna.agent.LivingLoop;
import com.cna.config.ToolPromptsManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class CreatePendingChatTask implements DefaultAgentToolUnit {

    private final ObjectMapper mapper = new ObjectMapper();
    // 核心：持有 LivingLoop 的引用，用于投递任务
    private final LivingLoop engine;
    private String lastRecord = "";

    // 通过构造函数把引擎传进来
    public CreatePendingChatTask(LivingLoop engine) {
        this.engine = engine;
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

            // 1. 创建预备挂起任务
            ChatTask pendingTask = new ChatTask(source, sourceName, role, roleName, 0);
            // 私聊任务降低优先级（更优先执行）
            if ("private".equalsIgnoreCase(source)) {
                pendingTask.setPriority(3.0 - 0.5);
            }

            // 2. 写入大模型当时的OS，作为未来被唤醒时的上下文
            String innerMonologue = "在之前你回复完消息后，你还想要继续回复 " + roleName + "(" + role + ") 在 " + sourceName + "(" + source + ") 的消息，理由是:[ " + reason + " ], 以下将会是ta的新消息;";
            pendingTask.addContext(innerMonologue);

            // 3. 极其优雅：直接通过传入的 LivingLoop 实例将任务塞进快递柜！
            engine.submitPendingRequest(pendingTask);

            // 4. 记录本次动作，用于后续存入短期记忆
            this.lastRecord = "主动等待 " + roleName + "(" + role + ") 在 " + sourceName + "(" + source + ") 的新消息, 理由是：" + reason;

            log.info("[Tool][CreatePendingTask] 已成功将预定挂起任务发送至引擎总线，目标: {}", role);

            return "SYSTEM_FEEDBACK: 成功设局！预设挂起任务已进入缓冲队列，等待底层感官模块提取，目标一旦出现将立刻被捕获。";

        } catch (Exception e) {
            log.error("执行 create_pending_chat_task 发生异常", e);
            return "ERROR: 创建预定挂起任务失败，异常: " + e.getMessage();
        }
    }

    @Override
    public String getTextRecord() {
        return this.lastRecord;
    }
}