package com.cna.AgentTool;

import com.cna.NapcatAdapter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import static com.cna.Main.GlobalNapcatAdapter;

@Slf4j
public class SendQQGroupMessage implements DefaultAgentToolUnit {

    private final ObjectMapper mapper = new ObjectMapper();

    private String message = null;
    private long groupId = -1;

    public SendQQGroupMessage() {

    }

    @Override
    public String getName() {
        return "send_qq_group_message";
    }

    @Override
    public ObjectNode getToolDefinition() {
        ObjectNode tool = mapper.createObjectNode();
        tool.put("type", "function");

        ObjectNode function = tool.putObject("function");
        function.put("name", getName());
        function.put("description", "当你决定向某个QQ群组发送消息时，调用此工具。注意：必须是群聊任务才能调用！");

        ObjectNode parameters = function.putObject("parameters");
        parameters.put("type", "object");

        ObjectNode properties = parameters.putObject("properties");

        ObjectNode groupId = properties.putObject("group_id");
        groupId.put("type", "string");
        groupId.put("description", "你要发送消息的目标QQ群号");

        ObjectNode message = properties.putObject("message");
        message.put("type", "string");
        message.put("description", "你要发送的具体文本内容");

        ArrayNode required = parameters.putArray("required");
        required.add("group_id").add("message");

        return tool;
    }

    @Override
    public String execute(JsonNode arguments) {
        try {
            this.groupId = Long.parseLong(arguments.path("group_id").asText());
            this.message = arguments.path("message").asText();

            log.info("[Tool][SendQQGroupMessage] 准备向群聊 [群:{}] 发送消息: {}", this.groupId, this.message);

            // 调用物理层的 WebSocket 发送动作
            GlobalNapcatAdapter.sendGroupMsg(this.groupId, this.message);

            return "SUCCESS: 群消息已成功发送至群 " + this.groupId;
        } catch (NumberFormatException e) {
            log.error("执行 send_qq_group_message 失败: 群号格式错误", e);
            return "ERROR: group_id 必须是有效的数字字符串";
        } catch (Exception e) {
            log.error("执行 send_qq_group_message 发生物理异常", e);
            return "ERROR: 发送失败，底层物理异常: " + e.getMessage();
        }
    }

    @Override
    public String getTextRecord(){
        if(this.message == null || this.groupId == -1){
            return "尝试给QQ群聊发送消息，但是没有发送成功，构建Tool的参数：输入的群聊ID：[" + this.groupId + "], 输入的消息：[" + this.message + "];";
        } else {
            return "给ID为[" + this.groupId + "]的qq群聊发送了[" + this.message + "];";
        }
    }
}
