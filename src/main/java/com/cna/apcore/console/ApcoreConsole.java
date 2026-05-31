package com.cna.apcore.console;

import com.cna.apcore.ActionLoop;
import com.cna.apcore.config.CoreConfig;
import com.cna.apcore.model.CognitivePrepareUnit;
import com.cna.apcore.pool.CognitivePreparePool;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;

/**
 * V4 Apcore 专用控制台交互系统。
 *
 * 让控制台消息直接进入 ActionLoop 认知准备池，方便测试和调试。
 * 所有不以 "/" 开头的输入自动作为消息注入认知管线；
 * 以 "/" 开头的输入为管理命令。
 *
 * 用法：
 *   - 直接打字（不以 / 开头）→ 作为 CognitivePrepareUnit 推入准备池
 *   - /send &lt;消息&gt;        → 显式发送，可指定来源
 *   - /from &lt;来源&gt; &lt;消息&gt; → 模拟特定来源的消息
 *   - /pool                 → 查看当前准备池状态
 *   - /pool detail          → 查看准备池详细内容
 *   - /stats                → 查看 ActionLoop 运行统计
 *   - /tools                → 列出所有已注册工具
 *   - /config               → 查看核心配置参数
 *   - /listen               → 切换 LLM 响应实时输出（默认开）
 *   - /help                 → 显示帮助
 *   - /exit, /stop          → 安全退出进程
 */
@Slf4j
public class ApcoreConsole {

    private Thread consoleThread;
    private volatile boolean running = false;
    private volatile boolean verbose = true; // 是否打印 LLM 响应详情
    private final ActionLoop core;

    public ApcoreConsole() {
        this.core = ActionLoop.getInstance();
    }

    public void start() {
        running = true;

        // 注册 LLM 响应监听器
        core.addConsoleListener(this::onActionProcessed);

        consoleThread = new Thread(() -> {
            // 短暂的延迟，让启动日志先打印完
            try { Thread.sleep(300); } catch (InterruptedException ignored) {}

            Scanner scanner = new Scanner(System.in);
            printBanner();

            while (running && !Thread.currentThread().isInterrupted() && scanner.hasNextLine()) {
                String input = scanner.nextLine().trim();

                if (input.isEmpty()) continue;

                try {
                    if (input.startsWith("/")) {
                        handleCommand(input);
                    } else if (input.toLowerCase().startsWith("send ")) {
                        // 兼容旧 ConsoleCommandSystem 的 send 命令（不带斜杠）
                        String payload = input.substring(5).trim();
                        if (payload.isEmpty()) {
                        System.out.println("  usage: send <msg>");
                        } else {
                            handleSend(payload);
                        }
                    } else {
                        handleRawMessage(input);
                    }
                } catch (Exception e) {
                    log.error("[ApcoreConsole] 处理输入时出错: {}", e.getMessage(), e);
                }
            }
        }, "Apcore-Console-Thread");
        consoleThread.setDaemon(true);
        consoleThread.start();
    }

    public void stop() {
        running = false;
        core.removeConsoleListener(this::onActionProcessed);
        if (consoleThread != null) {
            consoleThread.interrupt();
        }
    }

    // ==========================================
    // LLM 响应监听 — 实时打印到控制台
    // ==========================================

    private void onActionProcessed(ActionLoop.ActionNotification n) {
        if (!verbose) return;

        System.out.println();
        System.out.printf("[LLM] %dms | 触发: %s%n", n.llmElapsedMs(), truncateToWidth(n.actionSummary(), 80));

        if (n.llmThoughts() != null && !n.llmThoughts().isBlank()) {
            System.out.println("  thoughts:");
            for (String line : n.llmThoughts().split("\n")) {
                System.out.println("    " + line);
            }
        }

        if (n.toolCallCount() > 0) {
            System.out.printf("  tools: %d call(s)%n", n.toolCallCount());
            for (String tr : n.toolResults()) {
                System.out.println("    " + truncateToWidth(tr, 100));
            }
        }

        System.out.printf("  exp=%d feelings=%d pool=%d%n",
                n.experienceId(), n.stimulatedFeelingCount(), n.poolSizeAfter());
        System.out.println();
    }

    // ==========================================
    // 消息处理
    // ==========================================

    /** 原始文本直接作为消息注入认知准备池 */
    private void handleRawMessage(String text) {
        CognitivePrepareUnit cpu = CognitivePrepareUnit.create(
                text,
                List.of("console:apcore"),
                0.7
        );
        core.pushPrepareUnit(cpu);
        System.out.println("  -> pool (SE=0.70): " + truncate(text, 80));
    }

    // ==========================================
    // 命令处理
    // ==========================================

    private void handleCommand(String input) {
        String[] parts = input.split("\\s+", 2);
        String cmd = parts[0].toLowerCase();
        String arg = parts.length > 1 ? parts[1] : "";

        switch (cmd) {
            case "/send" -> handleSend(arg);
            case "/from" -> handleFrom(arg);
            case "/pool" -> handlePool(arg);
            case "/stats" -> handleStats();
            case "/tools" -> handleTools();
            case "/config" -> handleConfig();
            case "/listen" -> handleListen();
            case "/help" -> printHelp();
            case "/exit", "/stop" -> handleExit();
            default -> {
            System.out.println("  ? 未知命令: " + cmd + " (输入 /help 查看帮助)");
                // 如果不认识的命令，也当作消息处理
                handleRawMessage(input);
            }
        }
    }

    private void handleSend(String message) {
        if (message.isBlank()) {
            System.out.println("  usage: /send <msg>");
            return;
        }
        CognitivePrepareUnit cpu = CognitivePrepareUnit.create(
                message,
                List.of("console:apcore:send"),
                0.75
        );
        core.pushPrepareUnit(cpu);
        System.out.println("  -> pool (SE=0.75): " + truncate(message, 80));
    }

    private void handleFrom(String arg) {
        String[] parts = arg.split("\\s+", 2);
        if (parts.length < 2) {
            System.out.println("  usage: /from <source> <msg>");
            return;
        }
        String source = parts[0];
        String message = parts[1];

        CognitivePrepareUnit cpu = CognitivePrepareUnit.create(
                message,
                List.of("console:" + source),
                0.65
        );
        core.pushPrepareUnit(cpu);
        System.out.println("  -> pool [" + source + "] (SE=0.65): " + truncate(message, 80));
    }

    private void handlePool(String arg) {
        CognitivePreparePool pool = core.getPool();
        int size = pool.size();
        List<CognitivePrepareUnit> units = pool.getAllUnits();

        System.out.println("  pool: " + size + " units (max " + CoreConfig.MAX_POOL_SIZE + ")");
        System.out.println("  --");

        if (units.isEmpty()) {
            System.out.println("  (empty)");
            return;
        }

        boolean detail = "detail".equalsIgnoreCase(arg);
        int limit = detail ? units.size() : Math.min(10, units.size());

        for (int i = 0; i < limit; i++) {
            CognitivePrepareUnit u = units.get(i);
            String uuid = u.getUuid().toString().substring(0, 8);
            String text = u.getText();
            if (text.length() > 60) text = text.substring(0, 60) + "...";
            System.out.printf("  [%s] SE=%.2f UE=%.2f tick=%d cw=%.2f src=%s%n",
                    uuid,
                    u.getStimulateEnergy(),
                    u.getUnderstandEnergy(),
                    u.getTick(),
                    u.getContinueWeight(),
                    u.getSourceIds());
            if (detail) {
                System.out.println("       text: " + u.getText());
                if (u.getUeUnits() != null && !u.getUeUnits().isEmpty()) {
                    System.out.println("       ueUnits: " + u.getUeUnits().size() + " 个感觉节点");
                    for (var ue : u.getUeUnits()) {
                        System.out.printf("         - [dim=%d] %s (layer=%d, novelty=%.3f)%n",
                                ue.getDimId(), ue.getConcept(), ue.getBfsLayer(), ue.getNoveltyWeight());
                    }
                }
            }
        }

        if (!detail && units.size() > 10) {
            System.out.println("  ... 还有 " + (units.size() - 10) + " 个单元 (用 /pool detail 查看全部)");
        }
    }

    private void handleStats() {
        Map<String, Integer> stats = core.getStats();
        System.out.println("  stats:");
        System.out.printf("    ticks=%-16d inputs=%-14d actions=%d%n",
                stats.getOrDefault("ticks", 0),
                stats.getOrDefault("inputsProcessed", 0),
                stats.getOrDefault("actionsProcessed", 0));
        System.out.printf("    tools=%-16d experiences=%-11d feelings=%d%n",
                stats.getOrDefault("toolsExecuted", 0),
                stats.getOrDefault("experiencesStored", 0),
                stats.getOrDefault("feelingsStimulated", 0));
        System.out.printf("    pool=%-16d toolbox=%d%n",
                stats.getOrDefault("poolSize", 0),
                stats.getOrDefault("toolboxSize", 0));
    }

    private void handleTools() {
        Set<String> tools = core.getToolboxNames();
        System.out.println("  tools: " + tools.size() + " registered");
        for (String name : tools) {
            System.out.println("    " + name);
        }
    }

    private void handleConfig() {
        System.out.println("  config:");
        System.out.printf("    tick=%dms  baseline=%.3f  cw_decay=%.3f  cw_max=%.1f  boost_cap=%.1f%n",
                CoreConfig.COGNITIVE_TICK_MS, CoreConfig.BASELINE_THRESHOLD,
                CoreConfig.CONTINUE_WEIGHT_DECAY, CoreConfig.MAX_CONTINUE_WEIGHT, CoreConfig.SINGLE_BOOST_CAP);
        System.out.printf("    pool_max=%d  tick_max=%d  top_n=%d  habituate=%.0f  bfs_layers=%d  bfs_decay=%.2f  dedup=%.2f%n",
                CoreConfig.MAX_POOL_SIZE, CoreConfig.MAX_TICKS_WITHOUT_SELECT,
                CoreConfig.TOP_N_ACTION_PREDICTS, CoreConfig.HABITUATION_LIMIT,
                CoreConfig.BFS_MAX_LAYERS, CoreConfig.BFS_LAYER_DECAY, CoreConfig.DEDUP_THRESHOLD);
    }

    private void handleListen() {
        verbose = !verbose;
        System.out.println("  LLM output: " + (verbose ? "ON" : "OFF"));
    }

    private void handleExit() {
        System.out.println("  exiting...");
        running = false;
        System.exit(0);
    }

    // ==========================================
    // 辅助方法
    // ==========================================

    private void printBanner() {
        System.out.println();
        System.out.println("  V4 Apcore console - type /help for commands");
        System.out.println();
    }

    private void printHelp() {
        System.out.println();
        System.out.println("  commands:");
        System.out.println("    <text>               push message to pool (SE=0.70)");
        System.out.println("    send <msg>           push with SE=0.75");
        System.out.println("    /send <msg>           same");
        System.out.println("    /from <src> <msg>     push with specified source");
        System.out.println("    /pool                 show pool (top 10)");
        System.out.println("    /pool detail          show pool (all)");
        System.out.println("    /stats                show ActionLoop stats");
        System.out.println("    /tools                list all tools");
        System.out.println("    /config               show core config");
        System.out.println("    /listen               toggle LLM output (currently: " + (verbose ? "ON" : "OFF") + ")");
        System.out.println("    /help                 show this help");
        System.out.println("    /exit, /stop          shutdown");
        System.out.println();
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "null";
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }

    private static String truncateToWidth(String s, int maxLen) {
        if (s == null) return "";
        String cleaned = s.replace("\n", " ").replace("\r", " ");
        return cleaned.length() > maxLen ? cleaned.substring(0, maxLen) + "..." : cleaned;
    }
}
