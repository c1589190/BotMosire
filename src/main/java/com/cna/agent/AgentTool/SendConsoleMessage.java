package com.cna.agent.AgentTool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SendConsoleMessage implements DefaultAgentToolUnit {

    private final ObjectMapper mapper = new ObjectMapper();
    private String lastMessage = null;

    @Override
    public String getName() {
        return "send_console_message";
    }

    @Override
    public ObjectNode getToolDefinition() {
        ObjectNode tool = mapper.createObjectNode();
        tool.put("type", "function");

        ObjectNode function = tool.putObject("function");
        function.put("name", getName());
        // 【说明书】：明确告诉大模型这是用来和部署者/后台终端直接沟通的
        function.put("description", "当你需要直接向控制台（后台终端）输出信息、向部署者报告内部运行状态、或者发送调试警告时调用此工具。");

        ObjectNode parameters = function.putObject("parameters");
        parameters.put("type", "object");

        ObjectNode properties = parameters.putObject("properties");

        ObjectNode message = properties.putObject("message");
        message.put("type", "string");
        message.put("description", "要输出到控制台的具体文本内容。");

        ArrayNode required = parameters.putArray("required");
        required.add("message");

        return tool;
    }

    @Override
    public String execute(JsonNode arguments) {
        try {
            String msg = arguments.path("message").asText();

            if (msg == null || msg.trim().isEmpty()) {
                return "ERROR: message 不能为空。";
            }

            this.lastMessage = msg;

            // 使用醒目的格式打印，防止被常规底层日志淹没
            log.info("\n================ [Agent -> Console] ================\n{}\n==================================================\n", msg);

            return "SUCCESS: 消息已成功打印至系统控制台。";

        } catch (Exception e) {
            log.error("执行 send_console_message 发生异常", e);
            return "ERROR: 打印至控制台失败，底层异常: " + e.getMessage();
        }
    }

    @Override
    public String getTextRecord() {
        if (lastMessage == null) {
            return "尝试向控制台输出信息，但未成功;";
        }
        return "向控制台输出了内部状态信息: {" + this.lastMessage + "};";
    }
}