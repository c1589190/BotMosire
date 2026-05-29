package com.cna.test;

import java.io.File;
import java.sql.*;

/**
 * 向下兼容迁移测试：手工创建"旧格式"数据库（缺新列），
 * 然后执行 ALTER TABLE ADD COLUMN，模拟 ensureColumn 行为，
 * 验证旧数据不丢失、新列正确添加、新数据可写入新列。
 *
 * 运行：
 *   mvn test-compile exec:java -Dexec.mainClass="com.cna.test.MigrationCompatibilityTest" -Dexec.classpathScope=test -q
 */
public class MigrationCompatibilityTest {

    private static final String TEST_DB = "test/compat_test.db";
    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        System.out.println("\n╔══════════════════════════════════════════╗");
        System.out.println("║  向下兼容迁移测试                          ║");
        System.out.println("╚══════════════════════════════════════════╝\n");

        // 清理
        for (String s : new String[]{"", "-shm", "-wal"}) new File(TEST_DB + s).delete();

        // === 阶段 1: 手工建"旧格式"数据库（模拟升级前状态）===
        System.out.println("─── 阶段 1: 创建旧格式数据库 ───");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + TEST_DB);
             Statement s = c.createStatement()) {

            // 旧格式 Current_Memorys：无 sources 列
            s.execute("CREATE TABLE Current_Memorys (id INTEGER PRIMARY KEY AUTOINCREMENT, content TEXT NOT NULL)");
            // 旧格式 Deep_Memorys：无 sources 列
            s.execute("CREATE TABLE Deep_Memorys (id INTEGER PRIMARY KEY AUTOINCREMENT, vector_json TEXT NOT NULL, content TEXT NOT NULL)");
            // 旧格式 Feeling_Dimensions：无 status, llm_notes 列
            s.execute("CREATE TABLE Feeling_Dimensions (id INTEGER PRIMARY KEY AUTOINCREMENT, concept TEXT NOT NULL UNIQUE, vector_json TEXT NOT NULL, hit_weight REAL DEFAULT 1.0, trigger_count INTEGER DEFAULT 0)");
            // 旧格式：无 Feeling_Hypergraph 表

            // 插入旧数据
            s.execute("INSERT INTO Current_Memorys (content) VALUES ('旧记忆1-群聊记录')");
            s.execute("INSERT INTO Current_Memorys (content) VALUES ('旧记忆2-私聊记录')");
            s.execute("INSERT INTO Deep_Memorys (vector_json, content) VALUES ('[0.1,0.2,0.3]', '旧深度记忆-关于天气')");
            s.execute("INSERT INTO Feeling_Dimensions (concept, vector_json, hit_weight, trigger_count) VALUES ('天气话题', '[0.3,0.4,0.5]', 0.8, 3)");
            s.execute("INSERT INTO Feeling_Dimensions (concept, vector_json, hit_weight, trigger_count) VALUES ('技术讨论', '[0.5,0.1,0.2]', -0.3, 7)");

            System.out.println("  ✅ 旧格式表已创建（缺 sources, status, llm_notes, Feeling_Hypergraph）");
            System.out.println("  ✅ 已插入 2 条 current + 1 条 deep + 2 条 feeling");
        }

        // === 阶段 2: 执行 ensureColumn 等效迁移 ===
        System.out.println("\n─── 阶段 2: 执行迁移（ensureColumn 逻辑）───");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + TEST_DB);
             Statement s = c.createStatement()) {

            // 模拟 ensureColumn：检测列是否存在，不存在则加
            for (String[] col : new String[][]{
                    {"Current_Memorys", "sources", "TEXT DEFAULT '[]'"},
                    {"Deep_Memorys", "sources", "TEXT DEFAULT '[]'"},
                    {"Feeling_Dimensions", "status", "TEXT DEFAULT 'stable'"},
                    {"Feeling_Dimensions", "llm_notes", "TEXT DEFAULT ''"},
            }) {
                DatabaseMetaData meta = c.getMetaData();
                ResultSet rs = meta.getColumns(null, null, col[0], col[1]);
                if (!rs.next()) {
                    String sql = "ALTER TABLE " + col[0] + " ADD COLUMN " + col[1] + " " + col[2];
                    s.execute(sql);
                    System.out.println("  ✅ 已追加: " + col[0] + "." + col[1]);
                } else {
                    System.out.println("  - 已存在: " + col[0] + "." + col[1]);
                }
                rs.close();
            }

            // 创建新表 Feeling_Hypergraph（如果不存在）
            s.execute("CREATE TABLE IF NOT EXISTS Feeling_Hypergraph (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "source_dim_id INTEGER NOT NULL, target_dim_id INTEGER NOT NULL, " +
                    "weight REAL DEFAULT 1.0, relation_type TEXT DEFAULT 'associated', " +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                    "UNIQUE(source_dim_id, target_dim_id))");
            System.out.println("  ✅ 已创建: Feeling_Hypergraph");
        }

        // === 阶段 3: 验证迁移结果 ===
        System.out.println("\n─── 阶段 3: 验证迁移结果 ───");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + TEST_DB);
             Statement s = c.createStatement()) {

            // 3a: 旧数据完整
            ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM Current_Memorys");
            assertThat("旧 Current_Memorys 数据完整 (2条)", rs.next() ? rs.getInt(1) : 0, 2);

            rs = s.executeQuery("SELECT COUNT(*) FROM Deep_Memorys");
            assertThat("旧 Deep_Memorys 数据完整 (1条)", rs.next() ? rs.getInt(1) : 0, 1);

            rs = s.executeQuery("SELECT COUNT(*) FROM Feeling_Dimensions");
            assertThat("旧 Feeling_Dimensions 数据完整 (2条)", rs.next() ? rs.getInt(1) : 0, 2);

            // 3b: 所有新列存在
            for (String[] check : new String[][]{
                    {"Current_Memorys", "sources"},
                    {"Deep_Memorys", "sources"},
                    {"Feeling_Dimensions", "status"},
                    {"Feeling_Dimensions", "llm_notes"},
            }) {
                boolean found = false;
                rs = s.executeQuery("PRAGMA table_info(" + check[0] + ")");
                while (rs.next()) { if (check[1].equalsIgnoreCase(rs.getString(2))) { found = true; break; } }
                assertThat(check[0] + "." + check[1] + " 已添加", found, true);
            }

            // 3c: 新表存在
            rs = s.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='Feeling_Hypergraph'");
            assertThat("Feeling_Hypergraph 表已创建", rs.next(), true);

            // 3d: 旧数据的默认值
            rs = s.executeQuery("SELECT sources FROM Current_Memorys WHERE content LIKE '%群聊%'");
            String sources = rs.next() ? rs.getString(1) : "MISSING";
            assertThat("旧数据 sources 默认 '[]'", "[]".equals(sources), true);

            rs = s.executeQuery("SELECT status FROM Feeling_Dimensions WHERE concept='天气话题'");
            String status = rs.next() ? rs.getString(1) : "MISSING";
            assertThat("旧感觉 status 默认 'stable'", "stable".equals(status), true);

            rs = s.executeQuery("SELECT llm_notes FROM Feeling_Dimensions WHERE concept='技术讨论'");
            String notes = rs.next() ? rs.getString(1) : "MISSING";
            assertThat("旧感觉 llm_notes 默认 ''", "".equals(notes), true);

            // 3e: 新数据可用新列
            s.execute("INSERT INTO Current_Memorys (content, sources) VALUES ('新记忆-网页事件', '[\"webaddress_10.0.0.1\"]')");
            rs = s.executeQuery("SELECT sources FROM Current_Memorys WHERE content LIKE '%网页%'");
            assertThat("新数据可写入 sources", rs.next() && rs.getString(1).contains("webaddress"), true);

            s.execute("UPDATE Feeling_Dimensions SET status='dissonant', llm_notes='测试违和' WHERE concept='天气话题'");
            rs = s.executeQuery("SELECT status, llm_notes FROM Feeling_Dimensions WHERE concept='天气话题'");
            assertThat("可更新 status 和 llm_notes", rs.next() && "dissonant".equals(rs.getString(1)) && rs.getString(2).contains("测试违和"), true);

            // 写入超图边
            s.execute("INSERT INTO Feeling_Hypergraph (source_dim_id, target_dim_id, weight, relation_type) VALUES (1, 2, 1.0, 'associated')");
            rs = s.executeQuery("SELECT COUNT(*) FROM Feeling_Hypergraph");
            assertThat("可写入超图边", rs.next() && rs.getInt(1) == 1, true);
        }

        // 报告
        System.out.println("\n╔══════════════════════════════════════════╗");
        System.out.printf ("║  向下兼容: 通过 %d / 失败 %d                  ║%n", passed, failed);
        System.out.println(failed == 0 ? "║  ✅ 旧数据库升级后正常工作！                 ║" : "║  ❌ 有失败                                  ║");
        System.out.println("╚══════════════════════════════════════════╝\n");

        for (String s : new String[]{"", "-shm", "-wal"}) new File(TEST_DB + s).delete();
        System.exit(failed > 0 ? 1 : 0);
    }

    private static void assertThat(String desc, Object actual, Object expected) {
        boolean ok = (expected == null) ? (actual == null) : expected.equals(actual);
        if (ok) { passed++; System.out.println("  ✅ " + desc); }
        else    { failed++; System.out.println("  ❌ " + desc + " | 期望: " + expected + " | 实际: " + actual); }
    }
}
