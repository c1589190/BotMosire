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
                            System.out.println("  ⚠️ 用法: send <消息内容>");
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
        System.out.println("  ╔══════════════════════════════════════════════════════╗");
        System.out.printf("  ║  🧠 LLM 响应 #%d (耗时 %dms)%n", n.actionNum(), n.llmElapsedMs());
        System.out.println("  ╠══════════════════════════════════════════════════════╣");
        System.out.println("  ║  触发: " + truncateToWidth(n.actionSummary(), 46));
        System.out.println("  ╠══════════════════════════════════════════════════════╣");

        // LLM 想法
        if (n.llmThoughts() != null && !n.llmThoughts().isBlank()) {
            System.out.println("  ║  💭 想法:");
            for (String line : n.llmThoughts().split("\n")) {
                System.out.println("  ║    " + truncateToWidth(line, 48));
            }
            System.out.println("  ╠══════════════════════════════════════════════════════╣");
        }

        // 工具调用
        if (n.toolCallCount() > 0) {
            System.out.printf("  ║  🔨 工具调用: %d 次%n", n.toolCallCount());
            for (String tr : n.toolResults()) {
                System.out.println("  ║    " + truncateToWidth(tr, 48));
            }
            System.out.println("  ╠══════════════════════════════════════════════════════╣");
        }

        // 统计
        System.out.printf("  ║  📊 经验ID:%d | 刺激维度:%d | 池剩余:%d%n",
                n.experienceId(), n.stimulatedFeelingCount(), n.poolSizeAfter());
        System.out.println("  ╚══════════════════════════════════════════════════════╝");
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
                0.7  // 控制台消息给予较高 SE，更容易被选中
        );
        core.pushPrepareUnit(cpu);
        System.out.println("  ✅ 已推入准备池 (SE=0.70): " + truncate(text, 80));
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
                System.out.println("  ❓ 未知命令: " + cmd + " (输入 /help 查看帮助)");
                // 如果不认识的命令，也当作消息处理
                handleRawMessage(input);
            }
        }
    }

    private void handleSend(String message) {
        if (message.isBlank()) {
            System.out.println("  ⚠️ 用法: /send <消息内容>");
            return;
        }
        CognitivePrepareUnit cpu = CognitivePrepareUnit.create(
                message,
                List.of("console:apcore:send"),
                0.75  // 显式 send 给予更高 SE
        );
        core.pushPrepareUnit(cpu);
        System.out.println("  ✅ 已发送 (SE=0.75): " + truncate(message, 80));
    }

    private void handleFrom(String arg) {
        String[] parts = arg.split("\\s+", 2);
        if (parts.length < 2) {
            System.out.println("  ⚠️ 用法: /from <来源> <消息内容>");
            System.out.println("  示例: /from qq_group:12345 你好");
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
        System.out.println("  ✅ 已从 [" + source + "] 注入 (SE=0.65): " + truncate(message, 80));
    }

    private void handlePool(String arg) {
        CognitivePreparePool pool = core.getPool();
        int size = pool.size();
        List<CognitivePrepareUnit> units = pool.getAllUnits();

        System.out.println("  📊 认知准备池: " + size + " 个单元 (上限: " + CoreConfig.MAX_POOL_SIZE + ")");
        System.out.println("  ─────────────────────────────────────────────");

        if (units.isEmpty()) {
            System.out.println("  (空)");
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
        System.out.println("  📈 ActionLoop 运行统计");
        System.out.println("  ─────────────────────────────────────────────");
        System.out.printf("  Ticks:            %d%n", stats.getOrDefault("ticks", 0));
        System.out.printf("  Inputs 已处理:    %d%n", stats.getOrDefault("inputsProcessed", 0));
        System.out.printf("  Actions 已处理:   %d%n", stats.getOrDefault("actionsProcessed", 0));
        System.out.printf("  工具执行次数:     %d%n", stats.getOrDefault("toolsExecuted", 0));
        System.out.printf("  经验存储条数:     %d%n", stats.getOrDefault("experiencesStored", 0));
        System.out.printf("  感觉刺激次数:     %d%n", stats.getOrDefault("feelingsStimulated", 0));
        System.out.printf("  当前池大小:       %d%n", stats.getOrDefault("poolSize", 0));
        System.out.printf("  工具箱工具数:     %d%n", stats.getOrDefault("toolboxSize", 0));
    }

    private void handleTools() {
        Set<String> tools = core.getToolboxNames();
        System.out.println("  🔧 工具箱: " + tools.size() + " 个工具");
        System.out.println("  ─────────────────────────────────────────────");
        for (String name : tools) {
            System.out.println("  - " + name);
        }
    }

    private void handleConfig() {
        System.out.println("  ⚙️  V4 核心配置");
        System.out.println("  ─────────────────────────────────────────────");
        System.out.printf("  COGNITIVE_TICK_MS:        %d ms%n", CoreConfig.COGNITIVE_TICK_MS);
        System.out.printf("  BASELINE_THRESHOLD:       %.3f%n", CoreConfig.BASELINE_THRESHOLD);
        System.out.printf("  CONTINUE_WEIGHT_DECAY:    %.3f%n", CoreConfig.CONTINUE_WEIGHT_DECAY);
        System.out.printf("  MAX_CONTINUE_WEIGHT:      %.2f%n", CoreConfig.MAX_CONTINUE_WEIGHT);
        System.out.printf("  SINGLE_BOOST_CAP:         %.2f%n", CoreConfig.SINGLE_BOOST_CAP);
        System.out.printf("  MAX_POOL_SIZE:            %d%n", CoreConfig.MAX_POOL_SIZE);
        System.out.printf("  MAX_TICKS_WITHOUT_SELECT: %d%n", CoreConfig.MAX_TICKS_WITHOUT_SELECT);
        System.out.printf("  TOP_N_ACTION_PREDICTS:    %d%n", CoreConfig.TOP_N_ACTION_PREDICTS);
        System.out.printf("  HABITUATION_LIMIT:        %.0f%n", CoreConfig.HABITUATION_LIMIT);
        System.out.printf("  BFS_MAX_LAYERS:           %d%n", CoreConfig.BFS_MAX_LAYERS);
        System.out.printf("  BFS_LAYER_DECAY:          %.3f%n", CoreConfig.BFS_LAYER_DECAY);
        System.out.printf("  DEDUP_THRESHOLD:          %.3f%n", CoreConfig.DEDUP_THRESHOLD);
    }

    private void handleListen() {
        verbose = !verbose;
        System.out.println("  🔊 LLM 响应实时输出: " + (verbose ? "开启" : "关闭"));
        if (verbose) {
            System.out.println("     (每当 ActionLoop 完成 LLM 调用，将在此打印想法和工具结果)");
        }
    }

    private void handleExit() {
        System.out.println("  🛑 正在退出...");
        running = false;
        System.exit(0);
    }

    // ==========================================
    // 辅助方法
    // ==========================================

    private void printBanner() {
        System.out.println();
        System.out.println("  ╔══════════════════════════════════════════════════════╗");
        System.out.println("  ║       V4 Apcore 认知控制台                            ║");
        System.out.println("  ║       直接输入消息即可注入认知准备池                    ║");
        System.out.println("  ║       输入 /help 查看可用命令                         ║");
        System.out.println("  ╚══════════════════════════════════════════════════════╝");
        System.out.println();
    }

    private void printHelp() {
        System.out.println();
        System.out.println("  📖 V4 Apcore 控制台命令:");
        System.out.println("  ─────────────────────────────────────────────────────");
        System.out.println("  直接打字              → 作为消息推入认知准备池");
        System.out.println("  send <消息>           → 显式发送消息 (兼容旧格式, SE=0.75)");
        System.out.println("  /send <消息>          → 同上 (/命令格式)");
        System.out.println("  /from <来源> <消息>    → 模拟特定来源的消息");
        System.out.println("  /pool                  → 查看准备池状态 (前10条)");
        System.out.println("  /pool detail           → 查看准备池全部详情");
        System.out.println("  /stats                 → 查看 ActionLoop 运行统计");
        System.out.println("  /tools                 → 列出所有已注册工具");
        System.out.println("  /config                → 查看核心配置参数");
        System.out.println("  /listen                → 切换 LLM 响应实时输出 (当前:" + (verbose ? "开" : "关") + ")");
        System.out.println("  /help                  → 显示此帮助");
        System.out.println("  /exit, /stop           → 安全退出进程");
        System.out.println();
        System.out.println("  💡 提示: 发送消息后，LLM 的响应会自动打印到控制台 (用 /listen 切换)");
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
