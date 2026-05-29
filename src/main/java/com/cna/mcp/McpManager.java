package com.cna.mcp;

import com.cna.agent.AgentTool.DefaultAgentToolUnit;
import com.cna.config.ConfigsManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 统一 MCP 管理器（单例）。
 * <p>
 * 管理所有 MCP 服务器子进程的生命周期，同时服务：
 * <ul>
 *   <li><b>主 Agent</b>：通过 {@link #listAllServers()} / {@link #callTool(String, String, JsonNode)}
 *       为 McpBridge 提供 list/use 能力</li>
 *   <li><b>CodeAgent</b>：通过 {@link #getAllToolAdapters()} 为子执行体注入逐工具适配器</li>
 * </ul>
 * <p>
 * 配置文件：优先读取工作目录下的 {@code mcp-servers.json}；若不存在则从旧格式
 * {@code application.properties}（mcp.servers + mcp.server.*）自动迁移并写出 JSON。
 */
@Slf4j
public class McpManager {

    private static volatile McpManager INSTANCE;

    private static final ObjectMapper jsonMapper = new ObjectMapper();
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration INIT_TIMEOUT = Duration.ofSeconds(30);

    // 配置
    private final Map<String, McpServerConfig> configs = new LinkedHashMap<>();

    // 活跃连接
    private final Map<String, McpSyncClient> clients = new ConcurrentHashMap<>();

    // 工具缓存：serverName → List<ToolDef>
    private final Map<String, List<ToolDef>> toolCache = new ConcurrentHashMap<>();

    // 适配器缓存
    private final Map<String, List<DefaultAgentToolUnit>> adapterCache = new ConcurrentHashMap<>();

    private final List<McpSyncClient> allClients = new CopyOnWriteArrayList<>();
    private volatile boolean shutdownHookAdded = false;
    private volatile boolean configLoaded = false;

    // ── 单例 ────────────────────────────────────────────────────────────────

    private McpManager() {
        loadConfigs();
    }

    public static McpManager getInstance() {
        if (INSTANCE == null) {
            synchronized (McpManager.class) {
                if (INSTANCE == null) {
                    INSTANCE = new McpManager();
                }
            }
        }
        return INSTANCE;
    }

    // ── 配置加载 ────────────────────────────────────────────────────────────

    private void loadConfigs() {
        // 1. 优先读 mcp-servers.json
        Path jsonPath = Paths.get("mcp-servers.json");
        if (Files.exists(jsonPath)) {
            loadFromJson(jsonPath);
        } else {
            // 2. 降级：从 application.properties 迁移
            loadFromLegacyProperties();
        }
        configLoaded = true;
        log.info("[McpManager] 配置加载完毕，共 {} 个服务器 (启用: {})",
                configs.size(),
                configs.values().stream().filter(McpServerConfig::isEnabled).count());
    }

    @SuppressWarnings("unchecked")
    private void loadFromJson(Path path) {
        try {
            String raw = Files.readString(path);
            JsonNode root = jsonMapper.readTree(raw);
            JsonNode servers = root.path("servers");
            if (!servers.isArray()) {
                log.warn("[McpManager] mcp-servers.json 中 servers 不是数组，跳过");
                return;
            }
            for (JsonNode s : servers) {
                String name = s.path("name").asText("").trim();
                if (name.isEmpty()) continue;

                McpServerConfig.ServerType type = McpServerConfig.ServerType.COMMAND;
                String typeStr = s.path("type").asText("").trim();
                if ("npx".equalsIgnoreCase(typeStr)) type = McpServerConfig.ServerType.NPX;
                else if ("uvx".equalsIgnoreCase(typeStr)) type = McpServerConfig.ServerType.UVX;

                List<String> args = new ArrayList<>();
                JsonNode argsNode = s.path("args");
                if (argsNode.isArray()) {
                    for (JsonNode a : argsNode) args.add(a.asText());
                }

                Map<String, String> env = new LinkedHashMap<>();
                JsonNode envNode = s.path("env");
                if (envNode.isObject()) {
                    Iterator<String> fieldNames = envNode.fieldNames();
                    while (fieldNames.hasNext()) {
                        String key = fieldNames.next();
                        env.put(key, envNode.path(key).asText());
                    }
                }

                List<String> extraArgs = new ArrayList<>();
                JsonNode extraNode = s.path("extraArgs");
                if (extraNode.isArray()) {
                    for (JsonNode a : extraNode) extraArgs.add(a.asText());
                }

                McpServerConfig config = McpServerConfig.builder()
                        .name(name)
                        .type(type)
                        .command(s.path("command").asText("").trim())
                        .args(args)
                        .mcpPackage(s.path("package").asText("").trim())
                        .extraArgs(extraArgs)
                        .env(env)
                        .enabled(s.path("enabled").asBoolean(true))
                        .requestTimeoutSeconds(s.path("requestTimeoutSeconds").asInt(0))
                        .build();
                configs.put(name, config);
                log.info("[McpManager] 从 JSON 加载: {} (type={}, enabled={})", name, type, config.isEnabled());
            }
        } catch (IOException e) {
            log.error("[McpManager] 读取 mcp-servers.json 失败: {}", e.getMessage());
        }
    }

    private void loadFromLegacyProperties() {
        String serverList = ConfigsManager.getConfig("mcp.servers", "");
        if (serverList.isBlank()) {
            log.info("[McpManager] 未找到 mcp-servers.json 且旧格式 mcp.servers 为空，MCP 功能暂不可用");
            return;
        }

        log.info("[McpManager] 检测到旧格式 application.properties MCP 配置，正在迁移...");
        List<McpServerConfig> migrated = new ArrayList<>();

        for (String name : serverList.split(",")) {
            name = name.trim();
            if (name.isEmpty()) continue;

            String prefix = "mcp.server." + name + ".";
            String command = ConfigsManager.getConfig(prefix + "command", "");
            if (command.isBlank()) {
                log.warn("[McpManager] 跳过旧格式 MCP 服务器 [{}]: 未配置 command", name);
                continue;
            }

            String argsStr = ConfigsManager.getConfig(prefix + "args", "");
            List<String> args = new ArrayList<>();
            if (!argsStr.isBlank()) {
                args = Arrays.asList(argsStr.split(","));
            }

            String enabledStr = ConfigsManager.getConfig(prefix + "enabled", "true");
            boolean enabled = !"false".equalsIgnoreCase(enabledStr.trim());

            Map<String, String> env = new LinkedHashMap<>();
            String envStr = ConfigsManager.getConfig(prefix + "env", "");
            if (!envStr.isBlank()) {
                for (String pair : envStr.split(",")) {
                    String[] kv = pair.split("=", 2);
                    if (kv.length == 2) {
                        env.put(kv[0].trim(), kv[1].trim());
                    }
                }
            }

            McpServerConfig config = McpServerConfig.builder()
                    .name(name)
                    .type(McpServerConfig.ServerType.COMMAND)
                    .command(command)
                    .args(args)
                    .env(env)
                    .enabled(enabled)
                    .build();
            configs.put(name, config);
            migrated.add(config);
            log.info("[McpManager] 从旧格式迁移: {} (enabled={}, cmd={})", name, enabled, command);
        }

        if (!migrated.isEmpty()) {
            writeJsonConfig();
        }
    }

    /**
     * 将内存中的配置写出为 mcp-servers.json。
     */
    private void writeJsonConfig() {
        try {
            ObjectNode root = jsonMapper.createObjectNode();
            var arr = root.putArray("servers");
            for (McpServerConfig c : configs.values()) {
                ObjectNode s = arr.addObject();
                s.put("name", c.getName());
                s.put("enabled", c.isEnabled());
                s.put("type", c.getType().name().toLowerCase());
                if (c.getType() == McpServerConfig.ServerType.COMMAND) {
                    s.put("command", c.getCommand());
                    var argsNode = s.putArray("args");
                    c.getArgs().forEach(argsNode::add);
                    if (!c.getEnv().isEmpty()) {
                        var envNode = s.putObject("env");
                        c.getEnv().forEach(envNode::put);
                    }
                } else {
                    s.put("package", c.getMcpPackage());
                    if (!c.getExtraArgs().isEmpty()) {
                        var extraNode = s.putArray("extraArgs");
                        c.getExtraArgs().forEach(extraNode::add);
                    }
                }
                if (c.getRequestTimeoutSeconds() > 0) {
                    s.put("requestTimeoutSeconds", c.getRequestTimeoutSeconds());
                }
            }
            String json = jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
            Files.writeString(Paths.get("mcp-servers.json"), json);
            log.info("[McpManager] 已将配置迁移写入 mcp-servers.json");
        } catch (IOException e) {
            log.warn("[McpManager] 写入 mcp-servers.json 失败: {}", e.getMessage());
        }
    }

    // ── 连接管理 ────────────────────────────────────────────────────────────

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private McpSyncClient getOrConnect(McpServerConfig config) {
        return clients.computeIfAbsent(config.getName(), name -> {
            try {
                McpSyncClient client = buildClient(config);
                client.initialize();
                allClients.add(client);
                ensureShutdownHook();
                log.info("[McpManager] 已连接 MCP [{}]", name);
                return client;
            } catch (Throwable e) {
                log.warn("[McpManager] 连接 MCP [{}] 失败: {}", name, e.toString());
                // 不在 map 中留下 null/broken 引用；直接抛出让 computeIfAbsent 不存储
                throw new RuntimeException("连接失败: " + e.getMessage(), e);
            }
        });
    }

    private McpSyncClient buildClient(McpServerConfig config) {
        List<String> cmd = buildCommand(config);
        // 如果有环境变量，用 env 命令注入（兼容 SDK 0.18.x 无 environmentVariables API）
        List<String> finalCmd;
        if (!config.getEnv().isEmpty()) {
            finalCmd = new ArrayList<>();
            finalCmd.add("env");
            config.getEnv().forEach((k, v) -> finalCmd.add(k + "=" + v));
            finalCmd.addAll(cmd);
        } else {
            finalCmd = cmd;
        }
        String executable = finalCmd.get(0);
        List<String> procArgs = finalCmd.subList(1, finalCmd.size());

        ServerParameters params = ServerParameters.builder(executable)
                .args(procArgs)
                .build();

        StdioClientTransport transport = new StdioClientTransport(params, new JacksonMcpJsonMapper(jsonMapper));
        Duration timeout = config.getRequestTimeoutSeconds() > 0
                ? Duration.ofSeconds(config.getRequestTimeoutSeconds())
                : DEFAULT_REQUEST_TIMEOUT;

        return McpClient.sync(transport)
                .requestTimeout(timeout)
                .initializationTimeout(INIT_TIMEOUT)
                .build();
    }

    /**
     * 根据配置类型构建进程启动命令。
     */
    private List<String> buildCommand(McpServerConfig config) {
        List<String> cmd = new ArrayList<>();
        switch (config.getType()) {
            case NPX -> {
                if (isWindows()) {
                    cmd.add("cmd.exe");
                    cmd.add("/c");
                    cmd.add("npx");
                } else {
                    cmd.add("npx");
                }
                cmd.add("-y");
                cmd.add(config.getMcpPackage());
                cmd.addAll(config.getExtraArgs());
            }
            case UVX -> {
                if (isWindows()) {
                    cmd.add("cmd.exe");
                    cmd.add("/c");
                    cmd.add("uvx");
                } else {
                    cmd.add("uvx");
                }
                cmd.add(config.getMcpPackage());
                cmd.addAll(config.getExtraArgs());
            }
            default -> {
                cmd.add(config.getCommand());
                cmd.addAll(config.getArgs());
            }
        }
        return cmd;
    }

    private synchronized void ensureShutdownHook() {
        if (shutdownHookAdded) return;
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "mcp-manager-shutdown"));
        shutdownHookAdded = true;
    }

    // ── 工具发现 ────────────────────────────────────────────────────────────

    /**
     * 获取服务器的工具元数据列表。
     */
    public List<ToolDef> listTools(String serverName) {
        McpServerConfig config = configs.get(serverName);
        if (config == null || !config.isEnabled()) return Collections.emptyList();

        return toolCache.computeIfAbsent(serverName, k -> {
            try {
                McpSyncClient client = getOrConnect(config);
                McpSchema.ListToolsResult result = client.listTools();
                if (result == null || result.tools() == null) return Collections.emptyList();
                List<ToolDef> tools = new ArrayList<>();
                for (McpSchema.Tool t : result.tools()) {
                    tools.add(new ToolDef(
                            t.name(),
                            t.description() != null ? t.description() : "",
                            t.inputSchema() != null ? jsonMapper.valueToTree(t.inputSchema()) : null));
                }
                return tools;
            } catch (Throwable e) {
                log.warn("[McpManager] 列出工具失败 [{}]: {}", serverName, e.toString());
                return Collections.emptyList();
            }
        });
    }

    // ── 主 Agent 接口（McpBridge 使用）──────────────────────────────────────

    /**
     * 列出所有已配置 MCP 服务器及其状态和工具。返回与旧 McpBridge 兼容的 Map 结构。
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> listAllServers() {
        if (configs.isEmpty()) {
            return Collections.singletonMap("__empty__",
                    "未配置任何 MCP 服务器。请编辑 mcp-servers.json 添加服务器配置。");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        for (McpServerConfig config : configs.values()) {
            Map<String, Object> serverInfo = new LinkedHashMap<>();
            serverInfo.put("enabled", config.isEnabled());

            if (!config.isEnabled()) {
                serverInfo.put("status", "disabled");
                serverInfo.put("message", "该服务器已在 mcp-servers.json 中禁用");
                result.put(config.getName(), serverInfo);
                continue;
            }

            try {
                List<ToolDef> tools = listTools(config.getName());
                serverInfo.put("status", "connected");
                serverInfo.put("tools", tools);
                result.put(config.getName(), serverInfo);
            } catch (Exception e) {
                serverInfo.put("status", "error");
                serverInfo.put("message", "连接/查询失败: " + e.getMessage());
                result.put(config.getName(), serverInfo);
                log.warn("[McpManager] 服务器 [{}] 连接失败", config.getName(), e);
            }
        }
        return result;
    }

    /**
     * 调用指定 MCP 服务器的指定工具（JsonNode 参数版本 — McpBridge use 动作使用）。
     */
    public String callTool(String serverName, String toolName, JsonNode arguments) {
        // 参数校验
        if (serverName == null || serverName.isBlank()) {
            return "错误：必须提供 server 参数。请先调用 mcp_bridge list 查看可用服务器。";
        }
        if (toolName == null || toolName.isBlank()) {
            return "错误：必须提供 tool 参数。";
        }
        McpServerConfig config = configs.get(serverName);
        if (config == null) {
            return "错误：未知的 MCP 服务器 [" + serverName + "]。可用: " +
                    String.join(", ", configs.keySet());
        }
        if (!config.isEnabled()) {
            return "错误：MCP 服务器 [" + serverName + "] 已被禁用。";
        }

        // 将 JsonNode 转为 Map<String, Object>
        Map<String, Object> args;
        try {
            args = (arguments != null && !arguments.isNull())
                    ? jsonMapper.convertValue(arguments, Map.class)
                    : new HashMap<>();
        } catch (Exception e) {
            return "错误：无法解析 arguments 参数: " + e.getMessage();
        }

        return callToolInternal(serverName, toolName, args);
    }

    /**
     * 调用指定 MCP 服务器的指定工具（Map 参数版本 — McpToolAdapter 使用）。
     */
    public String callTool(String serverName, String toolName, Map<String, Object> arguments) {
        return callToolInternal(serverName, toolName,
                arguments != null ? arguments : new HashMap<>());
    }

    private String callToolInternal(String serverName, String toolName, Map<String, Object> args) {
        try {
            // 验证工具存在
            List<ToolDef> tools = listTools(serverName);
            boolean toolExists = tools.stream().anyMatch(t -> t.name().equals(toolName));
            if (!toolExists) {
                List<String> toolNames = tools.stream().map(ToolDef::name).toList();
                return "错误：服务器 [" + serverName + "] 上不存在工具 [" + toolName +
                        "]。可用: " + String.join(", ", toolNames);
            }

            McpServerConfig config = configs.get(serverName);
            McpSyncClient client = getOrConnect(config);

            McpSchema.CallToolResult result = client.callTool(
                    new McpSchema.CallToolRequest(toolName, args));

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
                return "⚠️ 工具执行返回错误: " + (text.isEmpty() ? "(无详情)" : text);
            }
            return text.isEmpty() ? "(无文本返回)" : text;
        } catch (Throwable e) {
            log.error("[McpManager] 调用 [{}/{}] 失败", serverName, toolName, e);
            // 移除失效连接，下次重连
            clients.remove(serverName);
            toolCache.remove(serverName);
            adapterCache.remove(serverName);
            return "MCP 调用失败: " + e.getMessage();
        }
    }

    // ── CodeAgent 接口 ──────────────────────────────────────────────────────

    /**
     * 获取指定服务器的所有工具适配器。
     */
    public List<DefaultAgentToolUnit> getToolAdapters(String serverName) {
        McpServerConfig config = configs.get(serverName);
        if (config == null || !config.isEnabled()) return Collections.emptyList();

        return adapterCache.computeIfAbsent(serverName, k -> {
            try {
                McpSyncClient client = getOrConnect(config);
                McpSchema.ListToolsResult result = client.listTools();
                if (result == null || result.tools() == null) return Collections.emptyList();
                List<DefaultAgentToolUnit> adapters = new ArrayList<>();
                for (McpSchema.Tool t : result.tools()) {
                    adapters.add(new McpToolAdapter(serverName, t));
                }
                log.info("[McpManager] 为 [{}] 创建 {} 个工具适配器", serverName, adapters.size());
                return adapters;
            } catch (Throwable e) {
                log.warn("[McpManager] 获取 [{}] 适配器失败: {}", serverName, e.toString());
                return Collections.emptyList();
            }
        });
    }

    /**
     * 获取所有已启用服务器的全部工具适配器（CodeAgent 使用）。
     */
    public List<DefaultAgentToolUnit> getAllToolAdapters() {
        List<DefaultAgentToolUnit> all = new ArrayList<>();
        for (McpServerConfig config : configs.values()) {
            if (!config.isEnabled()) continue;
            all.addAll(getToolAdapters(config.getName()));
        }
        return all;
    }

    // ── 生命周期 ────────────────────────────────────────────────────────────

    public void shutdown() {
        for (McpSyncClient c : allClients) {
            try {
                c.closeGracefully();
            } catch (Exception ignored) {
            }
        }
        allClients.clear();
        clients.clear();
        toolCache.clear();
        adapterCache.clear();
        log.info("[McpManager] 所有 MCP 连接已关闭");
    }

    // ── 内部类型 ────────────────────────────────────────────────────────────

    /**
     * MCP 工具元数据（替代旧 McpConnection.ToolDef）。
     */
    public record ToolDef(String name, String description, JsonNode inputSchema) {}
}
