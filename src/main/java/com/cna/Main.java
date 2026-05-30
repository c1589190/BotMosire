package com.cna;

import com.cna.agent.AgentInput.DefaultAgentInputUnit;
import com.cna.agent.LivingLoop;
import com.cna.llm.LLManager;
import com.cna.cmd.ConsoleCommandSystem;
import com.cna.config.ConfigsLoader;
import com.cna.config.ConfigsManager;
import com.cna.agent.MemoryManager;
import com.cna.db.FeelingDimensionManager;
import com.cna.db.MemoryDB;
import com.cna.plugin.PluginsManager;
import com.cna.workspace.WebServer;
import com.cna.workspace.WorkSpaceManager;
import lombok.extern.slf4j.Slf4j;

import java.net.URISyntaxException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

@Slf4j
public class Main {

    public static BlockingQueue<DefaultAgentInputUnit> AgentInputTasksQueue = new LinkedBlockingQueue<>(4096);

    public static volatile NapcatAdapter GlobalNapcatAdapter;
    public static volatile DiscordAdapter GlobalDiscordAdapter;
    public static final LivingLoop loop = new LivingLoop();
    // 声明插件管理器
    public static volatile PluginsManager pluginsManager;

    public static volatile ConsoleCommandSystem consoleCommandSystem;

    public static final WorkSpaceManager workspaceManager = new WorkSpaceManager();

    public static volatile WebServer webServer;

    /** 統一入隊入口，滿了 log warn 而非靜默丟棄 */
    public static void offerInput(DefaultAgentInputUnit input, String source) {
        if (!AgentInputTasksQueue.offer(input)) {
            log.warn("[Queue] AgentInputTasksQueue 已滿 ({}/4096)，丟棄來自 [{}] 的輸入",
                    AgentInputTasksQueue.size(), source);
        }
    }

    public static void main(String[] args){

        //new BotGUI();

        ConfigsManager.init();
        ConfigsLoader.loadAll();

        // 必须在 ConfigsLoader.loadAll() 之后初始化，否则 EMBEDDING_CONFIG / DB_URL 还是 null
        FeelingDimensionManager.init(new MemoryDB());
        com.cna.db.FeelingHypergraphManager.init(new com.cna.db.MemoryDB());
        com.cna.agent.CuriosityListManager.init(new MemoryDB());

        workspaceManager.initWebsite(); // 确保 website 目录和初始 index.html 存在
        // 去掉了你多敲的那个点，并保存了实例
        webServer = new WebServer(workspaceManager.getCurrentDir());
        webServer.start(8080);


        try {
            GlobalNapcatAdapter = new NapcatAdapter();
            GlobalNapcatAdapter.connect();
        } catch (URISyntaxException e) {
            log.warn("[Main] Napcat 連線失敗: {}，繼續啟動（QQ 將不可用）", e.getMessage());
        }

        // ── 初始化 Discord Adapter ─────────────────────────────────────────
        String discordToken = ConfigsManager.getConfig("discord.botToken", "");
        boolean discordEnabled = Boolean.parseBoolean(ConfigsManager.getConfig("discord.enabled", "true"));
        if (discordEnabled && discordToken != null && !discordToken.trim().isEmpty()) {
            try {
                GlobalDiscordAdapter = new DiscordAdapter(discordToken);
                GlobalDiscordAdapter.connect();
            } catch (Exception e) {
                log.warn("[Main] Discord 連線失敗: {}，繼續啟動（Discord 將不可用）", e.getMessage());
            }
        } else if (!discordEnabled) {
            log.info("[Main] discord.enabled=false，Discord 適配器已停用");
        } else {
            log.info("[Main] 未設定 discord.botToken，Discord 適配器已跳過");
        }

        // 1. 启动核心循环
        LLManager.init(loop);
        loop.start();

        // 2. 启动插件管理器，把 loop (实现了 MosireAPI) 传给它！
        pluginsManager = new PluginsManager(loop);
        pluginsManager.loadPlugins();

        // 3. 注册 JVM 关闭钩子 (安全停机)
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("[System] 正在关闭系统，准备安全卸载插件...");
            if (pluginsManager != null) {
                pluginsManager.disableAll();
            }
            if (GlobalNapcatAdapter != null) {
                GlobalNapcatAdapter.shutdown();
            }
            if (GlobalDiscordAdapter != null) {
                GlobalDiscordAdapter.disconnect();
            }
            if (webServer != null) {
                webServer.stop(); // 避免重启时 port 8080 被佔用
            }
            loop.stop();
            MemoryManager.getInstance().stop();
            com.cna.mcp.McpManager.getInstance().shutdown(); // 关闭所有 MCP 子进程
            com.cna.db.MemoryDB.shutdown(); // 关闭 HikariCP 连接池（I19）
        }));

        consoleCommandSystem = new ConsoleCommandSystem();
        consoleCommandSystem.start();
    }
}