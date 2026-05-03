package com.cna.agent.AgentTool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class SwitchToAdvancedModel implements DefaultAgentToolUnit {

    @Override
    public String getName() {
        return "switch_to_advanced_model";
    }

    @Override
    public ObjectNode getToolDefinition() {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode tool = mapper.createObjectNode();
        tool.put("type", "function");

        ObjectNode function = tool.putObject("function");
        function.put("name", getName());
        // Description 写得越清晰，大模型触发它的时机就越准
        function.put("description", "当你遇到极度复杂的逻辑推理、长代码编写、或者发现当前模型能力不足以完成任务时，必须调用此工具将思考核心切换至参数更大、更全能的高级深度思考模型。调用后下一轮思考将由更强的模型接管。");

        ObjectNode parameters = function.putObject("parameters");
        parameters.put("type", "object");

        ObjectNode properties = parameters.putObject("properties");
        ObjectNode reasonNode = properties.putObject("reason");
        reasonNode.put("type", "string");
        reasonNode.put("description", "简述需要切换到高级模型的原因（例如：'需要编写复杂的Java框架代码'）");

        ArrayNode required = parameters.putArray("required");
        required.add("reason");

        return tool;
    }

    @Override
    public String execute(JsonNode arguments) {
        String reason = arguments.path("reason").asText("遇到了复杂任务，需要更高算力");
        // 返回一段文本反馈，让大模型在下一轮（由大模型接管后）知道切换已经成功
        return "[SYSTEM_ACTION: SWITCH_MODEL] 核心切换指令已受理！原因：[" + reason + "]。当前处理核心已升级为高级模型，请利用你更强的逻辑能力继续处理该任务。";
    }

    @Override
    public String getTextRecord() {
        return "感知到任务复杂度过高，主动将处理核心切换至了高级模型，看到这条信息意味着您应该直接认真作答、发送消息并结束这个任务循环；";
    }
}