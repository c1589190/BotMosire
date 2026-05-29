package com.cna.agent.code;

import com.cna.agent.AgentTool.DefaultAgentToolUnit;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;

/**
 * 把一个 MCP server 暴露的工具包装成 BotMosire 的 {@link DefaultAgentToolUnit}，
 * 使其能直接进入 {@link CodeAgent} 的工具循环。getToolDefinition 用 MCP 的 inputSchema
 * 转成 OpenAI function 格式；execute 把参数转 Map 后路由到 mcpClient.callTool。
 */
@Slf4j
public class McpToolAdapter implements DefaultAgentToolUnit {

    private final McpSyncClient client;
    private final String toolName;
    private final ObjectMapper mapper;
    private final ObjectNode toolDefinition;

    public McpToolAdapter(McpSyncClient client, McpSchema.Tool tool, ObjectMapper mapper) {
        this.client = client;
        this.toolName = tool.name();
        this.mapper = mapper;
        this.toolDefinition = buildDefinition(tool);
    }

    private ObjectNode buildDefinition(McpSchema.Tool tool) {
        ObjectNode def = mapper.createObjectNode();
        def.put("type", "function");
        ObjectNode function = def.putObject("function");
        function.put("name", tool.name());
        function.put("description", tool.description() != null ? tool.description() : "");

        // MCP 的 inputSchema 本身就是一份 JSON Schema，直接转成 OpenAI 的 parameters
        JsonNode params = tool.inputSchema() != null ? mapper.valueToTree(tool.inputSchema()) : null;
        if (params != null && params.isObject()) {
            function.set("parameters", params);
        } else {
            ObjectNode p = function.putObject("parameters");
            p.put("type", "object");
            p.putObject("properties");
        }
        return def;
    }

    @Override
    public String getName() {
        return toolName;
    }

    @Override
    public ObjectNode getToolDefinition() {
        return toolDefinition.deepCopy();
    }

    @Override
    public String execute(JsonNode arguments) {
        try {
            Map<String, Object> args = arguments != null && !arguments.isNull()
                    ? mapper.convertValue(arguments, new TypeReference<Map<String, Object>>() {})
                    : new HashMap<>();

            McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest(toolName, args));

            StringBuilder sb = new StringBuilder();
            if (result.content() != null) {
                for (McpSchema.Content c : result.content()) {
                    if (c instanceof McpSchema.TextContent tc) {
                        sb.append(tc.text()).append("\n");
                    }
                }
            }
            String text = sb.toString().trim();

            if (Boolean.TRUE.equals(result.isError())) {
                return "工具返回错误: " + (text.isEmpty() ? "(无详情)" : text);
            }
            return text.isEmpty() ? "(无文本返回)" : text;
        } catch (Exception e) {
            log.warn("[McpToolAdapter] 调用 MCP 工具 [{}] 失败: {}", toolName, e.getMessage());
            return "MCP 工具调用异常: " + e.getMessage();
        }
    }

    @Override
    public String getTextRecord() {
        return "调用了 MCP 工具 " + toolName;
    }
}
