package com.cna.AgentTool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

import static com.cna.Main.GlobalNapcatAdapter;

@Slf4j
public class SendQQPrivateMessage implements DefaultAgentToolUnit {

    private final ObjectMapper mapper = new ObjectMapper();

    private List<String> messages = null;
    private long userId = -1;

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
        function.put("description", "当你决定回复某个用户的QQ私聊消息时调用此工具。可以传入多个字符串，系统会自动根据文字长度模拟人类打字延迟，分多条发送。");

        ObjectNode parameters = function.putObject("parameters");
        parameters.put("type", "object");

        ObjectNode properties = parameters.putObject("properties");

        // 参数 1：目标用户 QQ 号
        ObjectNode userIdNode = properties.putObject("user_id");
        userIdNode.put("type", "string");
        userIdNode.put("description", "你要回复的用户的QQ号码");

        // 参数 2：消息内容列表 (修改为 Array)
        ObjectNode messagesNode = properties.putObject("messages");
        messagesNode.put("type", "array");
        messagesNode.put("description", "你要发送的消息列表（按顺序）。即使只发送一句话，也请放在数组中。");

        ObjectNode items = messagesNode.putObject("items");
        items.put("type", "string");

        ArrayNode required = parameters.putArray("required");
        required.add("user_id").add("messages");

        return tool;
    }

    @Override
    public String execute(JsonNode arguments) {
        try {
            this.userId = Long.parseLong(arguments.path("user_id").asText());
            this.messages = new ArrayList<>();

            // 解析大模型传入的字符串数组
            JsonNode messagesArray = arguments.path("messages");
            if (messagesArray.isArray()) {
                for (JsonNode msgNode : messagesArray) {
                    this.messages.add(msgNode.asText());
                }
            }

            if (this.messages.isEmpty()) {
                return "ERROR: messages 数组为空，没有发送任何消息。";
            }

            log.info("[Tool][SendQQPrivateMessage] 准备向私聊 [QQ:{}] 分段发送 {} 条消息", this.userId, this.messages.size());

            for (int i = 0; i < this.messages.size(); i++) {
                String msg = this.messages.get(i);
                if (msg == null || msg.isEmpty()) continue;

                // 如果不是第一条消息，则执行线程等待（模拟打字过程）
                if (i > 0) {
                    // 模拟打字延迟：基础反应时间 500ms + 每字符约 150ms
                    long typingDelay = 100L + (msg.length() * 100L);

                    // 设定一个最大延迟上限（例如 4000ms），防止单条长文本导致总线阻塞过久
                    typingDelay = Math.min(typingDelay, 2000L);

                    try {
                        Thread.sleep(typingDelay);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        log.warn("打字延迟被中断", e);
                    }
                }

                // 调用物理层的 WebSocket 发送动作
                GlobalNapcatAdapter.sendPrivateMsg(this.userId, msg);
            }

            return "SUCCESS: 私聊消息已成功分段发送给 " + this.userId;

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
        if(this.messages == null || this.messages.isEmpty() || this.userId == -1){
            return "尝试给QQ用户发送消息，但是没有发送成功，输入的QQ号：[" + this.userId + "];";
        } else {
            return "给ID为[" + this.userId + "]的qq用户分段发送了: [" + String.join("] , [", this.messages) + "];";
        }
    }
}