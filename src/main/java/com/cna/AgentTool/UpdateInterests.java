package com.cna.AgentTool;

import com.cna.db.MDManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class UpdateInterests implements DefaultAgentToolUnit {

    private final ObjectMapper mapper = new ObjectMapper();
    // 锁定目标文件为注意力雷达配置文件
    private static final String TARGET_FILE = "interests.md";

    public UpdateInterests() {
    }

    @Override
    public String getName() {
        return "update_interests";
    }

    @Override
    public ObjectNode getToolDefinition() {
        ObjectNode tool = mapper.createObjectNode();
        tool.put("type", "function");

        ObjectNode function = tool.putObject("function");
        function.put("name", getName());
        // 【核心说明书】：让大模型明白这个文件的作用是控制“守门员”
        function.put("description", "当你对某个新话题产生兴趣，或者对某个经常刷屏的人或话题感到厌烦时，调用此工具更新你的注意力雷达。注意：此操作会【完全覆写】你之前的旧偏好，请务必在参数中输入完整且最新的注意力过滤规则。底层的感知系统（门卫）会根据这份雷达，决定以后拦截哪些垃圾消息，以及将哪些感兴趣的消息放行交给你处理。");

        ObjectNode parameters = function.putObject("parameters");
        parameters.put("type", "object");

        ObjectNode properties = parameters.putObject("properties");

        ObjectNode newInterests = properties.putObject("new_interests");
        newInterests.put("type", "string");
        newInterests.put("description", "你最新、最完整的兴趣列表与注意力过滤规则。可以明确列出你希望重点关注的话题、对象，以及极度厌恶、希望直接在底层屏蔽的噪音类型。");

        ArrayNode required = parameters.putArray("required");
        required.add("new_interests");

        return tool;
    }

    @Override
    public String execute(JsonNode arguments) {
        try {
            String newInterests = arguments.path("new_interests").asText();

            if (newInterests == null || newInterests.trim().isEmpty()) {
                return "ERROR: new_interests 不能为空，你不能让自己的注意力雷达变成盲区。";
            }

            log.info("[Tool][UpdateInterests] 大模型主动调整注意力，正在覆写 {}...", TARGET_FILE);
            log.debug("雷达规则预览: {}", newInterests.length() > 50 ? newInterests.substring(0, 50) + "..." : newInterests);

            // 直接调用物理覆写方法
            boolean success = MDManager.write(TARGET_FILE, newInterests);

            if (success) {
                return "SUCCESS: 注意力雷达已成功更新并物理归档。底层的过滤系统将立刻采用这份新规则进行消息拦截与放行。";
            } else {
                return "ERROR: 物理写入失败，请检查文件系统权限。";
            }

        } catch (Exception e) {
            log.error("执行 update_interests 发生异常", e);
            return "ERROR: 执行更新失败，底层异常: " + e.getMessage();
        }
    }
}