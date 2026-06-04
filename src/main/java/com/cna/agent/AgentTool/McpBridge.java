package com.cna.agent.AgentTool;

import com.cna.mcp.McpManager;
import com.cna.config.ToolPromptsManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * MCP 桥接工具 — LLM 通过此工具发现和调用外部 MCP 服务器的工具。
 * <p>
 * 支持两种操作:
 * <ul>
 *   <li><b>list</b> — 列出所有已配置 MCP 服务器及其可用工具（含参数 schema）</li>
 *   <li><b>use</b> — 调用指定服务器上的指定工具并返回执行结果</li>
 * </ul>
 * <p>
 * 线程安全：无实例状态，所有状态由 {@link McpManager} 单例管理。
 */
@Slf4j
public class McpBridge implements DefaultAgentToolUnit {

    private static final ObjectMapper mapper = new ObjectMapper();
    private String lastAction = "";

    public McpBridge() {
    }

    @Override
    public String getName() {
        return "mcp_bridge";
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

        // action
        ObjectNode actionProp = properties.putObject("action");
        actionProp.put("type", "string");
        actionProp.put("description", p.getCustomDescription("action"));
        ArrayNode actionEnum = actionProp.putArray("enum");
        actionEnum.add("list").add("use");

        // server
        ObjectNode serverProp = properties.putObject("server");
        serverProp.put("type", "string");
        serverProp.put("description", p.getCustomDescription("server"));

        // tool
        ObjectNode toolProp = properties.putObject("tool");
        toolProp.put("type", "string");
        toolProp.put("description", p.getCustomDescription("tool"));

        // arguments (use 时必填)
        ObjectNode argumentsProp = properties.putObject("arguments");
        argumentsProp.put("type", "object");
        argumentsProp.put("description", p.getCustomDescription("arguments"));

        ArrayNode required = parameters.putArray("required");
        required.add("action");

        parameters.put("additionalProperties", false);
        return tool;
    }

    @Override
    public String execute(JsonNode arguments) {
        try {
            String action = arguments.path("action").asText("").trim();
            McpManager manager = McpManager.getInstance();

            switch (action) {
                case "list": {
                    this.lastAction = "mcp list";
                    return formatServerList(manager.listAllServers());
                }

                case "use": {
                    String server = arguments.path("server").asText("").trim();
                    String toolName = arguments.path("tool").asText("").trim();
                    JsonNode toolArgs = arguments.path("arguments");
                    if (toolArgs.isMissingNode()) {
                        toolArgs = mapper.createObjectNode();
                    }

                    this.lastAction = "mcp use: " + server + "/" + toolName;
                    log.info("[McpBridge] 调用 MCP 工具: server={}, tool={}", server, toolName);
                    return manager.callTool(server, toolName, toolArgs);
                }

                default:
                    return "错误：未知操作 [" + action + "]，仅支持 list / use。";
            }

        } catch (Exception e) {
            log.error("[McpBridge] 执行异常", e);
            this.lastAction = "mcp error: " + e.getMessage();
            return "MCP 桥接异常: " + e.getMessage();
        }
    }

    // ── 格式化 ──────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private String formatServerList(Map<String, Object> serverMap) {
        if (serverMap.containsKey("__empty__")) {
            return (String) serverMap.get("__empty__");
        }

        StringBuilder sb = new StringBuilder(1024);
        sb.append("🌐 MCP 服务器连接状态：\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");

        for (Map.Entry<String, Object> entry : serverMap.entrySet()) {
            String name = entry.getKey();
            Map<String, Object> info = (Map<String, Object>) entry.getValue();
            String status = (String) info.get("status");

            sb.append("\n【").append(name).append("】");

            switch (status) {
                case "disabled" -> {
                    sb.append(" ❌ 未启用\n");
                    sb.append("  ").append(info.get("message")).append("\n");
                }
                case "error" -> {
                    sb.append(" ⚠️ 连接异常\n");
                    sb.append("  ").append(info.get("message")).append("\n");
                }
                case "connected" -> {
                    List<McpManager.ToolDef> tools =
                            (List<McpManager.ToolDef>) info.get("tools");
                    sb.append(" ✅ 已连接");
                    sb.append(" (").append(tools.size()).append(" 个工具)\n");

                    for (McpManager.ToolDef tool : tools) {
                        sb.append("  📦 ").append(tool.name());
                        String desc = tool.description();
                        if (desc != null && !desc.isBlank()) {
                            sb.append(" — ").append(desc);
                        }
                        sb.append("\n");
                        // 格式化参数 schema
                        formatSchemaBrief(sb, "     ", tool.inputSchema());
                    }
                }
            }
        }

        sb.append("\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
        sb.append("提示: 使用 mcp_bridge {action:\"use\", server:\"<服务器名>\",");
        sb.append(" tool:\"<工具名>\", arguments:{...}} 来调用具体工具。\n");
        return sb.toString();
    }

    /**
     * 将 JSON Schema 简要格式化为人类可读的参数说明。
     */
    private void formatSchemaBrief(StringBuilder sb, String indent, JsonNode schema) {
        if (schema == null || schema.isMissingNode()) return;

        JsonNode properties = schema.path("properties");
        if (properties.isMissingNode() || properties.isEmpty()) return;

        JsonNode required = schema.path("required");
        sb.append(indent).append("参数: ");
        boolean first = true;
        Iterator<String> fieldNames = properties.fieldNames();
        while (fieldNames.hasNext()) {
            String field = fieldNames.next();
            if (!first) sb.append(", ");
            first = false;

            JsonNode prop = properties.get(field);
            String type = prop.path("type").asText("any");
            boolean isRequired = false;
            if (required.isArray()) {
                for (JsonNode r : required) {
                    if (r.asText("").equals(field)) {
                        isRequired = true;
                        break;
                    }
                }
            }
            String marker = isRequired ? "必填" : "可选";
            sb.append(field).append("(").append(type).append(",").append(marker).append(")");
        }
        sb.append("\n");
    }

    @Override
    public String getTextRecord() {
        return lastAction.isEmpty() ? "使用了 MCP 桥接工具" : "MCP 桥接: " + lastAction;
    }

    @Override
    public boolean isAutoLoad() {
        return true;
    }

    @Override
    public boolean isAutoMemory() {
        return false;
    }
}
