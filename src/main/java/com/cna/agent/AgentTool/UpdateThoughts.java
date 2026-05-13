package com.cna.agent.AgentTool;

import com.cna.config.ToolPromptsManager;
import com.cna.db.MDManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Slf4j
public class UpdateThoughts implements DefaultAgentToolUnit {

    private final ObjectMapper mapper = new ObjectMapper();
    private static final String TARGET_FILE = "thoughts.md";

    @Override
    public String getName() {
        // 建议更名为 add_thought 语义更准确
        return "add_inner_thought";
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

        ObjectNode thoughtItem = properties.putObject("thought_item");
        thoughtItem.put("type", "string");
        thoughtItem.put("description", p.getCustomDescription("thought_item"));

        ArrayNode required = parameters.putArray("required");
        required.add("thought_item");

        return tool;
    }

    @Override
    public String execute(JsonNode arguments) {
        try {
            String thoughtItem = arguments.path("thought_item").asText();

            if (thoughtItem == null || thoughtItem.trim().isEmpty()) {
                return "ERROR: thought_item 不能为空。";
            }

            // 1. 格式化内容：添加时间戳和换行，使其更像日志/笔记
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            String contentToAppend = String.format("\n\n### [%s]\n%s", timestamp, thoughtItem);

            log.info("[Tool][AddInnerThought] 记录新认知: {}", thoughtItem);

            // 2. 调用追加方法
            // 注意：这里假设你的 MDManager 有 append 方法；
            // 如果 MDManager 只支持覆盖，你需要先用 MDManager.read 读取旧内容，拼接后再 write。
            boolean success = MDManager.append(TARGET_FILE, contentToAppend);

            if (success) {
                return "SUCCESS: 新认知已追加到内心归档中。";
            } else {
                return "ERROR: 物理写入失败。";
            }

        } catch (Exception e) {
            log.error("执行 add_inner_thought 发生异常", e);
            return "ERROR: 执行追加失败: " + e.getMessage();
        }
    }

    @Override
    public String getTextRecord(){
        return "在内心想法中新增了一条记录;";
    }
}