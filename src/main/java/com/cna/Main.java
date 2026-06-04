package com.cna;

import com.cna.agent.AgentInput.DefaultAgentInputUnit;
import com.cna.agent.LivingLoop;
import com.cna.apcore.ActionLoop;
import com.cna.llm.LLManager;
import com.cna.apcore.console.ApcoreConsole;
import com.cna.config.ConfigsLoader;
import com.cna.config.ConfigsManager;
import com.cna.agent.MemoryManager;
import com.cna.db.FeelingDimensionManager;
import com.cna.db.MemoryDB;
import com.cna.plugin.MosireAPI;
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

    // ── V4 核心：ActionLoop 接管一切，暴露为 MosireAPI 供插件/控制台使用 ──
    // 旧的 LivingLoop 已被禁用，不再创建实例
    public static MosireAPI loop; // 在 main() 中赋值为 ActionLoop 实例

    /** 保留旧 LivingLoop 引用以兼容需要它的代码路径（如 AgentTool 构造参数） */
    @Deprecated
    public static final LivingLoop legacyLoop = new LivingLoop();

    // 声明插件管理器
    public static volatile PluginsManager pluginsManager;

    public static volatile ApcoreConsole consoleCommandSystem;

    public static final WorkSpaceManager workspaceManager = new WorkSpaceManager();

    public static volatile WebServer webServer;

    /** 统一入队入口，满了 log warn 而非静默丢弃 */
    public static void offerInput(DefaultAgentInputUnit input, String source) {
        if (!AgentInputTasksQueue.offer(input)) {
            log.warn("[Queue] AgentInputTasksQueue 已满 ({}/4096)，丢弃来自 [{}] 的输入",
                    AgentInputTasksQueue.size(), source);
        }
    }

    public static void main(String[] args) {

        log.info("╔══════════════════════════════════════════════════════════╗");
        log.info("║          BotMosire V4 Core 启动中...                     ║");
        log.info("║          使用 ActionLoop 认知架构 (LivingLoop 已禁用)     ║");
        log.info("╚══════════════════════════════════════════════════════════╝");

        // ── 阶段 0: 配置初始化 ──
        log.info("[Main] 📋 阶段 0: 配置初始化...");
        ConfigsManager.init();
        com.cna.apcore.config.CoreConfig.init();
        ConfigsLoader.loadAll();
        log.info("[Main] ✅ 配置加载完成 — DB_URL={}", ConfigsManager.DB_URL);

        // ── 阶段 1: 数据库初始化 ──
        log.info("[Main] 🗄️ 阶段 1: 数据库初始化...");
        FeelingDimensionManager.init(new MemoryDB());
        com.cna.db.FeelingHypergraphManager.init(new com.cna.db.MemoryDB());
        com.cna.agent.CuriosityListManager.init(new MemoryDB());
        log.info("[Main] ✅ 数据库层初始化完成");

        // ── 阶段 2: Web 服务启动 ──
        log.info("[Main] 🌐 阶段 2: Web 服务启动...");
        workspaceManager.initWebsite();
        webServer = new WebServer(workspaceManager.getCurrentDir());
        webServer.start(8080);
        log.info("[Main] ✅ Web 服务已在端口 8080 启动");

        // ── 阶段 3: 适配器连接 ──
        log.info("[Main] 🔌 阶段 3: 消息适配器连接...");

        // Napcat (QQ)
        try {
            GlobalNapcatAdapter = new NapcatAdapter();
            GlobalNapcatAdapter.connect();
            log.info("[Main] ✅ Napcat (QQ) 适配器已连接");
        } catch (URISyntaxException e) {
            log.warn("[Main] ⚠️ Napcat 连线失败: {}，继续启动（QQ 将不可用）", e.getMessage());
        }

        // Discord
        String discordToken = ConfigsManager.getConfig("discord.botToken", "");
        boolean discordEnabled = Boolean.parseBoolean(ConfigsManager.getConfig("discord.enabled", "true"));
        if (discordEnabled && discordToken != null && !discordToken.trim().isEmpty()) {
            try {
                GlobalDiscordAdapter = new DiscordAdapter(discordToken);
                GlobalDiscordAdapter.connect();
                log.info("[Main] ✅ Discord 适配器已连接");
            } catch (Exception e) {
                log.warn("[Main] ⚠️ Discord 连线失败: {}，继续启动（Discord 将不可用）", e.getMessage());
            }
        } else if (!discordEnabled) {
            log.info("[Main] discord.enabled=false，Discord 适配器已停用");
        } else {
            log.info("[Main] 未设定 discord.botToken，Discord 适配器已跳過");
        }

        // ── 阶段 4: V4 核心启动 ──
        log.info("[Main] 🧠 阶段 4: V4 认知核心启动...");

        // 初始化 LLManager（传入 null 因为 V4 不需要旧的 taskQueueSummary）
        LLManager.init(null);
        log.info("[Main] ✅ LLManager 已初始化 (V4 模式: 无旧 TaskQueue)");

        // 获取 V4 ActionLoop 单例（自带工具箱，无需从 LivingLoop 导入）
        ActionLoop v4Core = ActionLoop.getInstance();
        log.info("[Main] ✅ ActionLoop 单例已获取 — 工具箱: {} 工具, 配置: tick={}ms, poolSize={}",
                v4Core.getToolboxSize(),
                com.cna.apcore.config.CoreConfig.COGNITIVE_TICK_MS,
                com.cna.apcore.config.CoreConfig.MAX_POOL_SIZE);

        // 设为全局 MosireAPI 桥接（供 ConsoleCommandSystem 等旧组件使用）
        loop = v4Core;
        log.info("[Main] ✅ MosireAPI 桥接已设置 (loop → ActionLoop)");

        // 启动 V4 核心循环
        v4Core.start();
        log.info("[Main] ✅ V4 ActionLoop 已启动");

        // 打印 V4 完整状态（调试用）
        log.info("[Main] 📊 V4 核心状态:");
        log.info("[Main]    Tick间隔: {}ms", com.cna.apcore.config.CoreConfig.COGNITIVE_TICK_MS);
        log.info("[Main]    基础底线: {}", com.cna.apcore.config.CoreConfig.BASELINE_THRESHOLD);
        log.info("[Main]    CW衰减: {}", com.cna.apcore.config.CoreConfig.CONTINUE_WEIGHT_DECAY);
        log.info("[Main]    CW上限: {}", com.cna.apcore.config.CoreConfig.MAX_CONTINUE_WEIGHT);
        log.info("[Main]    池上限: {}", com.cna.apcore.config.CoreConfig.MAX_POOL_SIZE);
        log.info("[Main]    过期tick: {}", com.cna.apcore.config.CoreConfig.MAX_TICKS_WITHOUT_SELECT);
        log.info("[Main]    经验检索TopN: {}", com.cna.apcore.config.CoreConfig.TOP_N_ACTION_PREDICTS);
        log.info("[Main]    厌倦阈值: {}", com.cna.apcore.config.CoreConfig.HABITUATION_LIMIT);
        log.info("[Main]    BFS最大层: {}", com.cna.apcore.config.CoreConfig.BFS_MAX_LAYERS);
        log.info("[Main]    BFS层衰减: {}", com.cna.apcore.config.CoreConfig.BFS_LAYER_DECAY);
        log.info("[Main]    去重阈值: {}", com.cna.apcore.config.CoreConfig.DEDUP_THRESHOLD);

        // 工具箱详情
        if (log.isDebugEnabled()) {
            log.debug("[Main] V4 工具箱工具列表:");
            for (String name : v4Core.getToolboxNames()) {
                log.debug("[Main]   - {}", name);
            }
        }

        // ── 阶段 5: 插件系统 ──
        log.info("[Main] 🔌 阶段 5: 插件系统启动...");
        pluginsManager = new PluginsManager(v4Core); // V4 ActionLoop 作为 MosireAPI 传给插件
        pluginsManager.loadPlugins();
        log.info("[Main] ✅ 插件系统已启动 (MosireAPI: ActionLoop)");

        // ── 阶段 6: 停机钩子 ──
        log.info("[Main] 🪝 阶段 6: 注册停机钩子...");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("[System] ⏹️ 正在关闭系统，准备安全卸载...");

            // 打印运行统计
            log.info("[System] 📊 ActionLoop 最终统计: {}", v4Core.getStats());

            if (pluginsManager != null) {
                pluginsManager.disableAll();
                log.info("[System] ✅ 插件已全部卸载");
            }
            if (GlobalNapcatAdapter != null) {
                GlobalNapcatAdapter.shutdown();
                log.info("[System] ✅ Napcat 适配器已关闭");
            }
            if (GlobalDiscordAdapter != null) {
                GlobalDiscordAdapter.disconnect();
                log.info("[System] ✅ Discord 适配器已断开");
            }
            if (webServer != null) {
                webServer.stop();
                log.info("[System] ✅ Web 服务已停止");
            }
            // LivingLoop 已禁用，不再调用 loop.stop()
            v4Core.stop();
            MemoryManager.getInstance().stop();
            com.cna.mcp.McpManager.getInstance().shutdown();
            com.cna.db.MemoryDB.shutdown();
            log.info("[System] ✅ 所有组件已安全关闭");
        }));
        log.info("[Main] ✅ 停机钩子已注册");

        // ── 阶段 7: Apcore 控制台 ──
        log.info("[Main] 💻 阶段 7: Apcore 认知控制台启动...");
        consoleCommandSystem = new ApcoreConsole();
        consoleCommandSystem.start();
        log.info("[Main] ✅ Apcore 认知控制台已启动");

        // ── 启动完成 ──
        log.info("╔══════════════════════════════════════════════════════════╗");
        log.info("║          BotMosire V4 Core 启动完成!                     ║");
        log.info("║          架构: ActionLoop (LivingLoop 已禁用)             ║");
        log.info("║          QQ: {}            ║",
                GlobalNapcatAdapter != null ? "已连接" : "未连接");
        log.info("║          Discord: {}        ║",
                GlobalDiscordAdapter != null ? "已连接" : "未连接");
        log.info("║          Web: http://localhost:8080                      ║");
        log.info("╚══════════════════════════════════════════════════════════╝");
    }
}
