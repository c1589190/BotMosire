package com.cna.AgentTool;

import com.cna.db.MDManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class UpdateThoughts implements DefaultAgentToolUnit {

    private final ObjectMapper mapper = new ObjectMapper();
    // 强制锁死目标文件，防止大模型产生幻觉把你的 Java 源码给改了
    private static final String TARGET_FILE = "thoughts.md";

    public UpdateThoughts() {
    }

    @Override
    public String getName() {
        return "update_inner_thoughts";
    }

    @Override
    public ObjectNode getToolDefinition() {
        ObjectNode tool = mapper.createObjectNode();
        tool.put("type", "function");

        ObjectNode function = tool.putObject("function");
        function.put("name", getName());
        // 【核心说明书】：告诉大模型什么时候用，以及覆写的危险性
        function.put("description", "当你对某些东西产生了新的认知，或者内心积攒了新的情绪、行事准则时，调用此工具更新你的想法文件。注意：此操作会完全覆写你之前的旧想法，请务必在参数中输入你经过总结后、完整且最新的全套新想法。");

        ObjectNode parameters = function.putObject("parameters");
        parameters.put("type", "object");

        ObjectNode properties = parameters.putObject("properties");

        ObjectNode newThoughts = properties.putObject("new_thoughts");
        newThoughts.put("type", "string");
        newThoughts.put("description", "你最新、最完整的内心独白与状态总结。可以包含你对一些东西的看法、想要在当前牢牢记住的事项等。");

        ArrayNode required = parameters.putArray("required");
        required.add("new_thoughts");

        return tool;
    }

    @Override
    public String execute(JsonNode arguments) {
        try {
            String newThoughts = arguments.path("new_thoughts").asText();

            if (newThoughts == null || newThoughts.trim().isEmpty()) {
                return "ERROR: new_thoughts 不能为空，你不能清空自己的内心。";
            }

            log.info("[Tool][UpdateInnerThoughts] 大模型触发顿悟，正在覆写 {}...", TARGET_FILE);
            log.debug("写入内容预览: {}", newThoughts.length() > 50 ? newThoughts.substring(0, 50) + "..." : newThoughts);

            // 直接调用你封装好的物理覆写方法
            boolean success = MDManager.write(TARGET_FILE, newThoughts);

            if (success) {
                return "SUCCESS: 内心独白已成功更新并物理归档。未来的你将带着这份新的认知行事。";
            } else {
                return "ERROR: 物理写入失败，请检查文件系统权限。";
            }

        } catch (Exception e) {
            log.error("执行 update_inner_thoughts 发生异常", e);
            return "ERROR: 执行更新失败，底层异常: " + e.getMessage();
        }
    }
    @Override
    public String getTextRecord(){
        return "为自己更新了内心想法;";
    }
}