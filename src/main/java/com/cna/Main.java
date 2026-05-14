package com.cna;

import com.cna.agent.AgentInput.DefaultAgentInputUnit;
import com.cna.agent.LivingLoop;
import com.cna.cmd.ConsoleCommandSystem;
import com.cna.config.ConfigsLoader;
import com.cna.config.ConfigsManager;
import com.cna.agent.MemoryManager;
import com.cna.plugin.PluginsManager;
import com.cna.workspace.WorkSpaceManager;
import lombok.extern.slf4j.Slf4j;

import java.net.URISyntaxException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

@Slf4j
public class Main {

    public static BlockingQueue<DefaultAgentInputUnit> AgentInputTasksQueue = new LinkedBlockingQueue<>(4096);

    public static NapcatAdapter GlobalNapcatAdapter;
    public static DiscordAdapter GlobalDiscordAdapter;
    public static LivingLoop loop = new LivingLoop();
    // 声明插件管理器
    public static PluginsManager pluginsManager;

    public static ConsoleCommandSystem consoleCommandSystem;

    public static WorkSpaceManager workspaceManager = new WorkSpaceManager();

    public static void main(String[] args){

        //new BotGUI();

        ConfigsManager.init();
        ConfigsLoader.loadAll();

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
            loop.stop();
            MemoryManager.getInstance().stop();
        }));

        consoleCommandSystem = new ConsoleCommandSystem();
        consoleCommandSystem.start();
    }
}