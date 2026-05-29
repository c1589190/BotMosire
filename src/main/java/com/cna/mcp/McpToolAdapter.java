package com.cna.mcp;

import com.cna.agent.AgentTool.DefaultAgentToolUnit;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;

/**
 * 将一个 MCP 工具包装为 BotMosire 的 {@link DefaultAgentToolUnit}。
 * <p>
 * 与旧版 {@code com.cna.agent.code.McpToolAdapter} 的关键区别：
 * <ul>
 *   <li>不直接持有 {@code McpSyncClient}，而是通过 {@link McpManager} 统一调用，
 *       使重连/容错逻辑集中在 Manager。</li>
 *   <li>构造函数只接收 serverName + McpSchema.Tool，适配器自身无状态。</li>
 * </ul>
 */
@Slf4j
public class McpToolAdapter implements DefaultAgentToolUnit {

    private static final ObjectMapper mapper = new ObjectMapper();

    private final String serverName;
    private final String toolName;
    private final ObjectNode toolDefinition;

    public McpToolAdapter(String serverName, McpSchema.Tool tool) {
        this.serverName = serverName;
        this.toolName = tool.name();
        this.toolDefinition = buildDefinition(tool);
    }

    private ObjectNode buildDefinition(McpSchema.Tool tool) {
        ObjectNode def = mapper.createObjectNode();
        def.put("type", "function");
        ObjectNode function = def.putObject("function");
        function.put("name", tool.name());
        function.put("description", tool.description() != null ? tool.description() : "");

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

            return McpManager.getInstance().callTool(serverName, toolName, args);
        } catch (Exception e) {
            log.warn("[McpToolAdapter] 调用 MCP 工具 [{}/{}] 失败: {}", serverName, toolName, e.getMessage());
            return "MCP 工具调用异常: " + e.getMessage();
        }
    }

    @Override
    public String getTextRecord() {
        return "调用了 MCP 工具 [" + serverName + "/" + toolName + "]";
    }
}
