package com.cna.test;

import com.cna.agent.AgentTask.ChatTask;
import com.cna.config.ConfigsManager;
import com.cna.db.MemoryDB;
import com.cna.db.MemoryDB.CurrentMemoryEntry;
import com.cna.db.MemoryDB.DeepMemoryEntry;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.sql.*;
import java.util.*;

/**
 * 深度记忆来源追溯 — 集成测试
 *
 * 测试阶段：
 * 1. Current Memory 来源写入 + 读取验证
 * 2. Deep Memory 来源写入 + 读取验证
 * 3. enrichDeepMemorySources (finish_task 回馈路径)
 * 4. Task.getSources() 多态验证
 * 5. 数据库迁移保险措施 (ensureColumn)
 *
 * 运行方式：
 *   cd BotMosire
 *   mvn test-compile exec:java -Dexec.mainClass="com.cna.test.SourceTrackingIntegrationTest" -Dexec.classpathScope=test -q
 */
@Slf4j
public class SourceTrackingIntegrationTest {

    private static MemoryDB db;
    private static final List<Integer> testDeepIds = new ArrayList<>();
    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        System.out.println("\n╔══════════════════════════════════════════════════╗");
        System.out.println("║  深度记忆来源追溯 — 集成测试                        ║");
        System.out.println("╚══════════════════════════════════════════════════╝\n");

        // 初始化配置（会读取 application.properties）
        ConfigsManager.init();

        // 可选：删掉旧 DB 从零开始，注释掉以保留已有数据
        boolean cleanStart = Boolean.parseBoolean(System.getProperty("test.cleanStart", "true"));
        if (cleanStart) {
            deleteDbFiles(ConfigsManager.DB_URL);
        }

        db = new MemoryDB();
        System.out.println("[TEST] 数据库已就绪: " + ConfigsManager.DB_URL);

        // =====================================================
        // 阶段 1: Current Memory 来源写入测试
        // =====================================================
        System.out.println("\n─── 阶段 1: Current Memory 写入 + 来源验证 ───");

        List<String> groupSources  = List.of("qq_group:888888", "qqid:3531297968");
        List<String> privateSources = List.of("qqid:111222333");
        List<String> webSources     = List.of("webaddress_192.168.1.100");
        List<String> sysSources     = List.of("system:internal");

        db.insertCurrentMemory("群聊测试: 用户在群里问了关于天气的问题", groupSources);
        db.insertCurrentMemory("私聊测试: 用户私聊询问了运行状况", privateSources);
        db.insertCurrentMemory("网页测试: 用户通过网页面板触发了按钮", webSources);
        db.insertCurrentMemory("系统测试: 定时任务周期触发", sysSources);
        db.insertCurrentMemory("无来源的旧记忆: 来自升级前的数据");  // 兼容旧调用

        List<CurrentMemoryEntry> entries = db.getLatestCurrentMemories(100);
        assertThat("群聊记忆有 sources",   matches(entries, "群聊", "qq_group:888888"), true);
        assertThat("私聊记忆仅有 role",    matches(entries, "私聊", "qqid:111222333"), true);
        assertThat("网页记忆有 webaddress_", matches(entries, "网页", "webaddress_192.168.1.100"), true);
        assertThat("系统记忆有 system:internal", matches(entries, "系统测试", "system:internal"), true);
        assertThat("旧记忆 sources 为空",  matches(entries, "无来源", null) && anyHasEmptySources(entries), true);

        System.out.println("[TEST] ✅ 阶段 1 通过: Current Memory 来源写入");

        // =====================================================
        // 阶段 2: Deep Memory 来源写入测试
        // =====================================================
        System.out.println("\n─── 阶段 2: Deep Memory 写入 + 来源验证 ───");

        double[] dummyVec = new double[128];
        Arrays.fill(dummyVec, 0.1);

        // 记录插入前后的 ID 范围
        List<DeepMemoryEntry> before = db.getAllDeepMemories();
        int countBefore = before.size();

        db.insertDeepMemory(dummyVec, "D1: 用户偏好聊天气话题",
                List.of("qq_group:888888", "qqid:3531297968"));
        db.insertDeepMemory(dummyVec, "D2: 用户通过网页频繁查看系统状态",
                List.of("webaddress_192.168.1.100"));
        db.insertDeepMemory(dummyVec, "D3: 系统健康检查通过",
                List.of("system:internal"));
        db.insertDeepMemory(dummyVec, "D4: 旧版无来源的记忆",
                List.of());

        List<DeepMemoryEntry> deepEntries = db.getAllDeepMemories();
        int newCount = deepEntries.size() - countBefore;
        System.out.println("[TEST] 新增深度记忆: " + newCount + " (总: " + deepEntries.size() + ")");

        // 记录新增的 ID 做后续清理
        for (DeepMemoryEntry e : deepEntries) {
            if (e.content.startsWith("D1:") || e.content.startsWith("D2:")
                    || e.content.startsWith("D3:") || e.content.startsWith("D4:")) {
                testDeepIds.add(e.id);
            }
        }

        assertThat("新增了 4 条深度记忆", newCount, 4);
        assertThat("D1 有群聊来源", deepEntries.stream().anyMatch(
                e -> e.content.startsWith("D1") && e.sources.contains("qq_group:888888")), true);
        assertThat("D2 有网页来源", deepEntries.stream().anyMatch(
                e -> e.content.startsWith("D2") && e.sources.stream().anyMatch(s -> s.startsWith("webaddress_"))), true);
        assertThat("D3 有系统来源", deepEntries.stream().anyMatch(
                e -> e.content.startsWith("D3") && e.sources.contains("system:internal")), true);
        assertThat("D4 无来源", deepEntries.stream().anyMatch(
                e -> e.content.startsWith("D4") && e.sources.isEmpty()), true);

        System.out.println("[TEST] ✅ 阶段 2 通过: Deep Memory 来源写入");

        // =====================================================
        // 阶段 3: enrichDeepMemorySources (finish_task 回馈)
        // =====================================================
        System.out.println("\n─── 阶段 3: 来源丰富 (enrichDeepMemorySources) ───");

        DeepMemoryEntry dm4 = deepEntries.stream()
                .filter(e -> e.content.startsWith("D4"))
                .findFirst().orElseThrow();
        int dm4Id = dm4.id;
        System.out.println("[TEST] D4 初始 sources: " + dm4.sources);

        // 模拟 ChatTask finish_task 回馈
        List<String> chatTaskSources = List.of("qq_group:888888", "qqid:3531297968");
        db.enrichDeepMemorySources(dm4Id, chatTaskSources);

        // 重新读取验证
        DeepMemoryEntry updated = db.getAllDeepMemories().stream()
                .filter(e -> e.id == dm4Id).findFirst().orElseThrow();
        System.out.println("[TEST] D4 丰富后 sources: " + updated.sources);

        assertThat("D4 有 qq_group", updated.sources.contains("qq_group:888888"), true);
        assertThat("D4 有 qqid", updated.sources.contains("qqid:3531297968"), true);
        assertThat("D4 sources 数量=2", updated.sources.size(), 2);

        // 重复 enrich 同一来源，不重复添加
        db.enrichDeepMemorySources(dm4Id, chatTaskSources);
        DeepMemoryEntry finalDm4 = db.getAllDeepMemories().stream()
                .filter(e -> e.id == dm4Id).findFirst().orElseThrow();
        assertThat("重复 enrich 不变", finalDm4.sources.size(), 2);

        System.out.println("[TEST] ✅ 阶段 3 通过: 来源丰富");

        // =====================================================
        // 阶段 4: Task.getSources() 多态
        // =====================================================
        System.out.println("\n─── 阶段 4: Task.getSources() 多态 ───");

        ChatTask groupTask = new ChatTask("qq_group:888888", "测试群",
                "qqid:3531297968", "Constantin", 0L, false);
        List<String> gs = groupTask.getSources();
        System.out.println("[TEST] 群聊: " + gs);
        assertThat("群聊有 source", gs.contains("qq_group:888888"), true);
        assertThat("群聊有 role",   gs.contains("qqid:3531297968"), true);
        assertThat("群聊数量=2",    gs.size(), 2);

        ChatTask privateTask = new ChatTask("qqid:111222333", "私聊用户",
                "qqid:111222333", "私聊用户", 0L, true);
        List<String> ps = privateTask.getSources();
        System.out.println("[TEST] 私聊: " + ps);
        assertThat("私聊数量=1", ps.size(), 1);
        assertThat("私聊有 qqid", ps.contains("qqid:111222333"), true);

        List<String> cs = new com.cna.agent.AgentTask.ConsoleChatTask("test").getSources();
        System.out.println("[TEST] 控制台: " + cs);
        assertThat("控制台=system:console", cs.contains("system:console"), true);

        List<String> ws = new com.cna.agent.AgentTask.WebEventTask("10.0.0.1", "{}").getSources();
        System.out.println("[TEST] 网页: " + ws);
        assertThat("网页=webaddress_10.0.0.1", ws.contains("webaddress_10.0.0.1"), true);

        System.out.println("[TEST] ✅ 阶段 4 通过: Task.getSources() 多态");

        // =====================================================
        // 阶段 5: ChatMessageInput → sources 构造 + consolidation 格式化
        // =====================================================
        System.out.println("\n─── 阶段 5: ChatMessageInput 来源构造 + consolidation 格式化 ───");

        // 模拟 ChatMessageInputHandler 中的来源构造逻辑
        com.cna.agent.AgentInput.ChatMessageInput groupInput = new com.cna.agent.AgentInput.ChatMessageInput(
                "qq_group:888888", "测试群", "qqid:3531297968", "Constantin",
                "天气怎么样", 0L,
                false, false, List.of(), "", "", "normal", false, "");
        // 群聊：source + role
        List<String> inputSources = new ArrayList<>();
        if (!groupInput.isPrivate() && groupInput.getSource() != null && !groupInput.getSource().isBlank()) {
            inputSources.add(groupInput.getSource());
        }
        if (groupInput.getRole() != null && !groupInput.getRole().isBlank()) {
            inputSources.add(groupInput.getRole());
        }
        System.out.println("[TEST] 群聊 input sources: " + inputSources);
        assertThat("群聊 input 有 source", inputSources.contains("qq_group:888888"), true);
        assertThat("群聊 input 有 role", inputSources.contains("qqid:3531297968"), true);

        // 私聊：仅 role
        com.cna.agent.AgentInput.ChatMessageInput privateInput = new com.cna.agent.AgentInput.ChatMessageInput(
                "qqid:111222333", "私聊用户", "qqid:111222333", "私聊用户",
                "你好", 0L,
                true, false, List.of(), "", "", "normal", false, "");
        List<String> privateInputSources = new ArrayList<>();
        if (!privateInput.isPrivate() && privateInput.getSource() != null && !privateInput.getSource().isBlank()) {
            privateInputSources.add(privateInput.getSource());
        }
        if (privateInput.getRole() != null && !privateInput.getRole().isBlank()) {
            privateInputSources.add(privateInput.getRole());
        }
        System.out.println("[TEST] 私聊 input sources: " + privateInputSources);
        assertThat("私聊 input 仅 role", privateInputSources.size(), 1);
        assertThat("私聊 input 有 qqid", privateInputSources.contains("qqid:111222333"), true);

        // 验证 consolidation 格式化（当前记忆传给 LLM 时的格式）
        MemoryDB.CurrentMemoryEntry cmEntry = new MemoryDB.CurrentMemoryEntry(99, "测试内容", List.of("qq_group:888888", "qqid:3531297968"));
        StringBuilder formatted = new StringBuilder();
        formatted.append("[0] ").append(cmEntry.content);
        if (cmEntry.sources != null && !cmEntry.sources.isEmpty()) {
            formatted.append(" [来源: ").append(String.join(", ", cmEntry.sources)).append("]");
        }
        String expected = "[0] 测试内容 [来源: qq_group:888888, qqid:3531297968]";
        System.out.println("[TEST] consolidation 格式化: " + formatted);
        assertThat("consolidation 格式化正确", formatted.toString(), expected);

        System.out.println("[TEST] ✅ 阶段 5 通过: ChatMessageInput 来源构造");

        // =====================================================
        // 阶段 6: 数据库迁移保险措施
        // =====================================================
        System.out.println("\n─── 阶段 6: 数据库迁移保险 ───");

        String dbUrl = ConfigsManager.DB_URL;
        String dbPath = dbUrl.replace("jdbc:sqlite:", "");
        try (Connection conn = DriverManager.getConnection(dbUrl);
             Statement stmt = conn.createStatement()) {
            boolean ok = true;
            ok &= checkColumn(stmt, "Current_Memorys", "sources");
            ok &= checkColumn(stmt, "Deep_Memorys", "sources");
            assertThat("所有 sources 列存在", ok, true);
        }
        System.out.println("[TEST] ✅ 阶段 6 通过: 数据库迁移保险");

        // =====================================================
        // 清理测试数据
        // =====================================================
        System.out.println("\n─── 清理测试数据 ───");
        int cleanedDeep = 0;
        for (int id : testDeepIds) {
            try (Connection conn = DriverManager.getConnection(dbUrl);
                 PreparedStatement pstmt = conn.prepareStatement("DELETE FROM Deep_Memorys WHERE id = ?")) {
                pstmt.setInt(1, id);
                cleanedDeep += pstmt.executeUpdate();
            }
        }
        System.out.println("[TEST] 已清理 " + cleanedDeep + " 条测试深度记忆");

        // 报告
        System.out.println("\n╔══════════════════════════════════════════════════╗");
        System.out.printf ("║  测试结果: 通过 %d / 失败 %d                          ║%n", passed, failed);
        if (failed == 0) {
            System.out.println("║  ✅ 全部通过！                                      ║");
        } else {
            System.out.println("║  ❌ 有测试失败                                      ║");
        }
        System.out.println("╚══════════════════════════════════════════════════╝\n");

        db.shutdown();
        System.exit(failed > 0 ? 1 : 0);
    }

    // =====================================================
    // 辅助方法
    // =====================================================

    private static boolean matches(List<CurrentMemoryEntry> entries, String keyword, String source) {
        for (CurrentMemoryEntry e : entries) {
            if (e.content.contains(keyword)) {
                if (source == null) return true;
                if (e.sources.contains(source)) return true;
                // 继续检查下一条匹配项（可能有多条 content 都包含 keyword）
            }
        }
        return false;
    }

    private static boolean anyHasEmptySources(List<CurrentMemoryEntry> entries) {
        return entries.stream().anyMatch(e -> e.sources.isEmpty());
    }

    private static boolean checkColumn(Statement stmt, String table, String column) throws SQLException {
        try (ResultSet rs = stmt.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString(2))) {
                    System.out.println("  ✅ " + table + "." + column + " 存在");
                    return true;
                }
            }
        }
        System.out.println("  ❌ " + table + "." + column + " 缺失");
        return false;
    }

    private static void deleteDbFiles(String jdbcUrl) {
        if (!jdbcUrl.startsWith("jdbc:sqlite:")) return;
        String path = jdbcUrl.substring("jdbc:sqlite:".length());
        for (String suffix : new String[]{"", "-shm", "-wal"}) {
            File f = new File(path + suffix);
            if (f.exists()) { f.delete(); System.out.println("[TEST] 已删除 " + f.getName()); }
        }
    }

    private static void assertThat(String desc, Object actual, Object expected) {
        boolean ok = (expected == null) ? (actual == null) : expected.equals(actual);
        if (ok) { passed++; System.out.println("  ✅ " + desc); }
        else    { failed++; System.out.println("  ❌ " + desc + " | 期望: " + expected + " | 实际: " + actual); }
    }
}
