package com.cna.agent.AgentTool;

import com.cna.config.ToolPromptsManager;
import com.cna.db.MDManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ToolUsageReader implements DefaultAgentToolUnit {

    private static final ObjectMapper mapper = new ObjectMapper();
    private String lastQueriedTool = null;

    @Override
    public String getName() {
        return "get_tool_usage_detail";
    }

    @Override
    public ObjectNode getToolDefinition() {
        ObjectNode tool = mapper.createObjectNode();
        tool.put("type", "function");

        ToolPromptsManager p = new ToolPromptsManager(this.getClass().getName());

        ObjectNode function = tool.putObject("function");
        function.put("name", getName());
        // 直接使用配置中的描述，不再提供默认回退
        function.put("description", p.getToolDescription());

        ObjectNode parameters = function.putObject("parameters");
        parameters.put("type", "object");

        ObjectNode properties = parameters.putObject("properties");

        ObjectNode toolName = properties.putObject("tool_name");
        toolName.put("type", "string");
        toolName.put("description", p.getCustomDescription("tool_name"));

        ArrayNode required = parameters.putArray("required");
        required.add("tool_name");

        return tool;
    }

    @Override
    public String execute(JsonNode arguments) {
        String toolName = arguments.path("tool_name").asText().trim();
        this.lastQueriedTool = toolName;

        // 构建外部 Markdown 文件路径
        String filePath = "prompts/" + toolName + ".md";
        log.info("[Tool][GetToolUsageDetail] 大模型请求查阅工具 [{}] 的详细说明，对应文件：{}", toolName, filePath);

        // 使用 MDManager 读取文件，若文件不存在则不会自动创建（defaultContent = null）
        String content = MDManager.read(filePath, null);
        if (content == null || content.isBlank()) {
            log.warn("[Tool][GetToolUsageDetail] 未找到工具 [{}] 的说明文档", toolName);
            return "暂无关于工具 [" + toolName + "] 的详细使用说明。";
        }

        log.info("[Tool][GetToolUsageDetail] 成功返回工具 [{}] 的说明文档，内容长度：{} 字符", toolName, content.length());
        return content;
    }

    @Override
    public String getTextRecord() {
        if (this.lastQueriedTool == null) {
            return "尝试通过工具获取工具使用说明，但调用失败；";
        }
        return "调用工具，获取了工具 [" + this.lastQueriedTool + "] 的详细使用说明；";
    }
}