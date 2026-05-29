package com.cna.agent.code;

import com.cna.agent.AgentTool.DefaultAgentToolUnit;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 管理一个或多个 MCP server 子进程的生命周期，并把它们的工具暴露为 {@link DefaultAgentToolUnit}。
 * <p>
 * 设计要点：
 * <ul>
 *   <li>子进程<b>长驻</b>（不每任务重启）；连接失败时<b>优雅降级</b>（返回空工具列表，不影响其他能力）。</li>
 *   <li>Windows 下经 {@code cmd /c} 启动 npx，避免 ProcessBuilder 无法直接 exec npx 的问题。</li>
 *   <li>注册 JVM 关闭钩子，进程退出时 closeGracefully。</li>
 * </ul>
 */
@Slf4j
public class McpToolHub {

    private final ObjectMapper mapper;
    private final List<McpSyncClient> clients = new CopyOnWriteArrayList<>();
    private volatile boolean shutdownHookAdded = false;

    public McpToolHub(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /**
     * 把一个 npx 包形式的 MCP server 启动并连接（如 "@playwright/mcp@latest"）。
     * 跨平台处理 npx 调用。
     */
    public List<DefaultAgentToolUnit> connectNpx(String name, String npxPackage, String... extraArgs) {
        String command;
        List<String> args = new ArrayList<>();
        if (isWindows()) {
            command = "cmd.exe";
            args.add("/c");
            args.add("npx");
        } else {
            command = "npx";
        }
        args.add("-y");
        args.add(npxPackage);
        args.addAll(Arrays.asList(extraArgs));
        return connect(name, command, args, Duration.ofSeconds(60));
    }

    /**
     * 把一个 uvx 包形式的 MCP server 启动并连接（如 "windows-mcp"）。跨平台处理 uvx 调用。
     */
    public List<DefaultAgentToolUnit> connectUvx(String name, String uvxPackage, String... extraArgs) {
        String command;
        List<String> args = new ArrayList<>();
        if (isWindows()) {
            command = "cmd.exe";
            args.add("/c");
            args.add("uvx");
        } else {
            command = "uvx";
        }
        args.add(uvxPackage);
        args.addAll(Arrays.asList(extraArgs));
        return connect(name, command, args, Duration.ofSeconds(60));
    }

    /**
     * 通用连接入口。失败返回空列表（优雅降级），绝不抛出。
     */
    public List<DefaultAgentToolUnit> connect(String name, String command, List<String> args, Duration requestTimeout) {
        try {
            ServerParameters params = ServerParameters.builder(command).args(args).build();
            StdioClientTransport transport = new StdioClientTransport(params, new JacksonMcpJsonMapper(mapper));
            McpSyncClient client = McpClient.sync(transport)
                    .requestTimeout(requestTimeout)
                    .initializationTimeout(Duration.ofSeconds(30))
                    .build();

            client.initialize();

            McpSchema.ListToolsResult listed = client.listTools();
            List<DefaultAgentToolUnit> adapters = new ArrayList<>();
            if (listed != null && listed.tools() != null) {
                for (McpSchema.Tool tool : listed.tools()) {
                    adapters.add(new McpToolAdapter(client, tool, mapper));
                }
            }

            clients.add(client);
            ensureShutdownHook();
            log.info("[McpToolHub] 已连接 MCP [{}] (命令: {} {})，注入工具数: {}", name, command, args, adapters.size());
            return adapters;
        } catch (Throwable e) {
            log.warn("[McpToolHub] 连接 MCP [{}] 失败，跳过该组工具（不影响其他能力）: {}", name, e.toString());
            return Collections.emptyList();
        }
    }

    private synchronized void ensureShutdownHook() {
        if (shutdownHookAdded) return;
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "mcp-hub-shutdown"));
        shutdownHookAdded = true;
    }

    public void shutdown() {
        for (McpSyncClient c : clients) {
            try {
                c.closeGracefully();
            } catch (Exception ignored) {
                // 尽力关闭，不阻断
            }
        }
        clients.clear();
    }
}
