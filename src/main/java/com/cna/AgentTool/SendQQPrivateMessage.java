package com.cna.AgentTool;

import com.cna.NapcatAdapter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import static com.cna.Main.GlobalNapcatAdapter;

@Slf4j
public class SendQQPrivateMessage implements DefaultAgentToolUnit {

    private final ObjectMapper mapper = new ObjectMapper();

    private String message = null;
    private long userId = -1;

    // 通过构造函数注入底层的 Napcat 适配器
    public SendQQPrivateMessage() {
    }

    @Override
    public String getName() {
        return "send_qq_private_message";
    }

    @Override
    public ObjectNode getToolDefinition() {
        ObjectNode tool = mapper.createObjectNode();
        tool.put("type", "function");

        ObjectNode function = tool.putObject("function");
        function.put("name", getName());
        function.put("description", "当你决定回复某个用户的QQ私聊消息时，调用此工具。");

        ObjectNode parameters = function.putObject("parameters");
        parameters.put("type", "object");

        ObjectNode properties = parameters.putObject("properties");

        // 参数 1：目标用户 QQ 号
        // 注意：大模型对长串数字处理不好，定义为 string 最安全，我们在 Java 层再转回 long
        ObjectNode userId = properties.putObject("user_id");
        userId.put("type", "string");
        userId.put("description", "你要回复的用户的QQ号码");

        // 参数 2：消息内容
        ObjectNode message = properties.putObject("message");
        message.put("type", "string");
        message.put("description", "你要发送的具体文本内容");

        ArrayNode required = parameters.putArray("required");
        required.add("user_id").add("message");

        return tool;
    }

    @Override
    public String execute(JsonNode arguments) {
        try {
            this.userId = Long.parseLong(arguments.path("user_id").asText());
            this.message = arguments.path("message").asText();

            log.info("[Tool][SendQQPrivateMessage] 准备向私聊 [QQ:{}] 发送消息: {}", this.userId, this.message);

            // 调用物理层的 WebSocket 发送动作
            GlobalNapcatAdapter.sendPrivateMsg(this.userId, this.message);

            return "SUCCESS: 私聊消息已成功发送给 " + this.userId;
        } catch (NumberFormatException e) {
            log.error("执行 send_qq_private_message 失败: QQ号格式错误", e);
            return "ERROR: user_id 必须是有效的数字字符串";
        } catch (Exception e) {
            log.error("执行 send_qq_private_message 发生物理异常", e);
            return "ERROR: 发送失败，底层物理异常: " + e.getMessage();
        }
    }

    @Override
    public String getTextRecord(){
        if(this.message == null || this.userId == -1){
            return "尝试给QQ用户发送消息，但是没有发送成功，构建Tool的参数：输入的群聊ID：[" + this.userId + "], 输入的消息：[" + this.message + "];";
        } else {
            return "给ID为[" + this.userId + "]的qq用户发送了[" + this.message + "];";
        }
    }
}