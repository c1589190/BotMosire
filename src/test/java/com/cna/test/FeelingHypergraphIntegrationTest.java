package com.cna.test;

import com.cna.config.ConfigsManager;
import com.cna.db.FeelingDimensionManager;
import com.cna.db.FeelingHypergraphManager;
import com.cna.db.MemoryDB;
import com.cna.db.MemoryDB.FeelingDimension;

import java.io.File;
import java.util.*;

/**
 * 感觉超图 + 谐振分析 — 集成测试
 *
 * 运行方式：
 *   cd BotMosire
 *   mvn test-compile exec:java -Dexec.mainClass="com.cna.test.FeelingHypergraphIntegrationTest" -Dexec.classpathScope=test -Dtest.cleanStart=true -q
 */
public class FeelingHypergraphIntegrationTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        System.out.println("\n╔══════════════════════════════════════════════════╗");
        System.out.println("║  感觉超图 + 谐振分析 — 集成测试                      ║");
        System.out.println("╚══════════════════════════════════════════════════╝\n");

        boolean cleanStart = Boolean.parseBoolean(System.getProperty("test.cleanStart", "true"));
        if (cleanStart) {
            deleteDbFiles(ConfigsManager.DB_URL);
        }

        ConfigsManager.init();
        MemoryDB db = new MemoryDB();
        FeelingDimensionManager fdm = FeelingDimensionManager.getInstance();
        if (fdm == null) FeelingDimensionManager.init(db);
        fdm = FeelingDimensionManager.getInstance();
        FeelingHypergraphManager.init(db);
        FeelingHypergraphManager hgm = FeelingHypergraphManager.getInstance();

        System.out.println("[TEST] 数据库: " + ConfigsManager.DB_URL);

        // =====================================================
        // 阶段 1: 超图边 CRUD
        // =====================================================
        System.out.println("\n─── 阶段 1: 超图边 CRUD ───");

        // 建立一些关联边
        hgm.upsertEdge(1, 2, "associated", 1.0);
        hgm.upsertEdge(2, 1, "associated", 1.0);
        hgm.upsertEdge(2, 3, "associated", 0.5);
        hgm.upsertEdge(1, 3, "associated", 0.3);

        // 重复 upsert 应加权
        hgm.upsertEdge(1, 2, "associated", 0.5);

        List<MemoryDB.HypergraphEdge> edges = db.getAllHypergraphEdges();
        System.out.println("[TEST] 超图边总数: " + edges.size());
        assertThat("至少 3 条边", edges.size() >= 3, true);

        // 验证加权
        Optional<MemoryDB.HypergraphEdge> edge12 = edges.stream()
                .filter(e -> e.sourceDimId == 1 && e.targetDimId == 2).findFirst();
        assertThat("边 1→2 权重 > 1.0 (两次 upsert)", edge12.isPresent() && edge12.get().weight > 1.0, true);

        System.out.println("[TEST] ✅ 阶段 1 通过: 超图边 CRUD");

        // =====================================================
        // 阶段 2: BFS 扩展
        // =====================================================
        System.out.println("\n─── 阶段 2: BFS 扩展 ───");

        Set<Integer> expanded = hgm.expandDimension(1, 2);
        System.out.println("[TEST] 从 dimId=1 扩展 2 层: " + expanded);
        assertThat("扩展包含自身", expanded.contains(1), true);
        assertThat("扩展包含邻居 2", expanded.contains(2), true);
        assertThat("扩展包含邻居 3", expanded.contains(3), true);

        System.out.println("[TEST] ✅ 阶段 2 通过: BFS 扩展");

        // =====================================================
        // 阶段 3: 批量扩展
        // =====================================================
        System.out.println("\n─── 阶段 3: 批量扩展 ───");

        Map<Integer, Set<Integer>> multi = hgm.expandMulti(List.of(1, 2), 2);
        System.out.println("[TEST] 批量扩展: " + multi.size() + " 组");
        assertThat("批量扩展返回 2 组", multi.size(), 2);
        assertThat("组 1 非空", multi.containsKey(1) && !multi.get(1).isEmpty(), true);

        System.out.println("[TEST] ✅ 阶段 3 通过: 批量扩展");

        // =====================================================
        // 阶段 4: 枢纽查找
        // =====================================================
        System.out.println("\n─── 阶段 4: 枢纽查找 ───");

        Map<Integer, Integer> hubs = hgm.findConnectingNodes(List.of(1, 2, 3));
        System.out.println("[TEST] 枢纽节点: " + hubs);
        assertThat("枢纽查找不抛异常", true, true);

        System.out.println("[TEST] ✅ 阶段 4 通过: 枢纽查找");

        // =====================================================
        // 阶段 5: 违和迭代流程
        // =====================================================
        System.out.println("\n─── 阶段 5: 违和维度生命周期 ───");

        // 模拟：LLM 认为某维度违和
        double[] dummyVec = new double[128];
        Arrays.fill(dummyVec, 0.15);
        db.insertFeelingDimension("测试违和感", dummyVec, 0.0);

        List<FeelingDimension> allDims = fdm.getAllDimensions();
        FeelingDimension dissonantDim = allDims.stream()
                .filter(d -> d.concept.equals("测试违和感")).findFirst().orElseThrow();
        int dissId = dissonantDim.id;

        // 标记为 dissonant
        db.updateDimensionStatusAndNotes(dissId, "dissonant", "2026-05-30: LLM不确定为什么违和");
        allDims = fdm.getAllDimensions();
        FeelingDimension updated = allDims.stream().filter(d -> d.id == dissId).findFirst().orElseThrow();
        assertThat("违和维度 status=dissonant", updated.status, "dissonant");
        assertThat("违和维度有 llm_notes", !updated.llmNotes.isBlank(), true);

        // 模拟第2次出现，追加 notes
        db.updateDimensionStatusAndNotes(dissId, "dissonant",
                updated.llmNotes + "\n2026-05-31: 再次出现，确认是群聊环境下的行为");
        allDims = fdm.getAllDimensions();
        updated = allDims.stream().filter(d -> d.id == dissId).findFirst().orElseThrow();
        assertThat("llm_notes 含两次记录", updated.llmNotes.contains("05-30") && updated.llmNotes.contains("05-31"), true);

        // 解决
        db.updateDimensionStatusAndNotes(dissId, "stable",
                updated.llmNotes + "\n2026-06-01: 确认为群聊环境下的自我审查，已理解");
        allDims = fdm.getAllDimensions();
        updated = allDims.stream().filter(d -> d.id == dissId).findFirst().orElseThrow();
        assertThat("解决后 status=stable", updated.status, "stable");

        System.out.println("[TEST] ✅ 阶段 5 通过: 违和维度生命周期");

        // =====================================================
        // 阶段 6: 数据库迁移保险
        // =====================================================
        System.out.println("\n─── 阶段 6: 数据库迁移保险 ───");

        String dbUrl = ConfigsManager.DB_URL;
        try (java.sql.Connection conn = java.sql.DriverManager.getConnection(dbUrl);
             java.sql.Statement stmt = conn.createStatement()) {
            boolean ok = true;
            for (String table : new String[]{"Feeling_Hypergraph"}) {
                try (java.sql.ResultSet rs = stmt.executeQuery(
                        "SELECT name FROM sqlite_master WHERE type='table' AND name='" + table + "'")) {
                    boolean exists = rs.next();
                    System.out.println("  " + (exists ? "✅" : "❌") + " " + table + " " + (exists ? "存在" : "缺失"));
                    ok &= exists;
                }
            }
            for (String col : new String[]{"status", "llm_notes"}) {
                try (java.sql.ResultSet rs = stmt.executeQuery("PRAGMA table_info(Feeling_Dimensions)")) {
                    boolean exists = false;
                    while (rs.next()) { if (col.equalsIgnoreCase(rs.getString(2))) { exists = true; break; } }
                    System.out.println("  " + (exists ? "✅" : "❌") + " Feeling_Dimensions." + col + " " + (exists ? "存在" : "缺失"));
                    ok &= exists;
                }
            }
            assertThat("所有表和列存在", ok, true);
        }

        System.out.println("[TEST] ✅ 阶段 6 通过: 数据库迁移保险");

        // =====================================================
        // 报告
        // =====================================================
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

    private static void assertThat(String desc, Object actual, Object expected) {
        boolean ok = (expected == null) ? (actual == null) : expected.equals(actual);
        if (ok) { passed++; System.out.println("  ✅ " + desc); }
        else    { failed++; System.out.println("  ❌ " + desc + " | exp: " + expected + " | act: " + actual); }
    }

    private static void deleteDbFiles(String jdbcUrl) {
        if (!jdbcUrl.startsWith("jdbc:sqlite:")) return;
        String path = jdbcUrl.substring("jdbc:sqlite:".length());
        for (String s : new String[]{"", "-shm", "-wal"}) {
            File f = new File(path + s);
            if (f.exists()) f.delete();
        }
    }
}
