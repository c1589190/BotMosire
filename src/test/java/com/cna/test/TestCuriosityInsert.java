package com.cna.test;

import com.cna.config.ConfigsManager;
import com.cna.config.ConfigsLoader;
import com.cna.db.MemoryDB;

/**
 * 直接验证 insertCuriosityEntry 修复：确保 SQLite last_insert_rowid() 方案可行。
 * 用法: mvn exec:java -Dexec.mainClass="com.cna.test.TestCuriosityInsert" -Dexec.classpathScope=test -q
 */
public class TestCuriosityInsert {
    public static void main(String[] args) {
        boolean passed = true;

        // 需要先初始化 ConfigsManager（包括 DB_URL）
        ConfigsManager.init();
        ConfigsLoader.loadAll();

        MemoryDB db = new MemoryDB();

        // 1. 先插入一个测试 feeling dimension，以便 curiosity 引用
        double[] testVec = new double[256];
        for (int i = 0; i < 256; i++) testVec[i] = Math.random() * 2 - 1;
        int dimId = db.insertFeelingDimension("test_curiosity_concept", testVec, 0.5);
        System.out.println("1. 插入测试维度: dimId=" + dimId + (dimId > 0 ? " ✅" : " ❌"));
        if (dimId <= 0) passed = false;

        // 2. 调用修复后的 insertCuriosityEntry
        int entryId = db.insertCuriosityEntry(dimId, "test_curiosity_concept",
                "[1,2,3]", "[\"concept_a\",\"concept_b\",\"concept_c\"]",
                "What is the relationship between these concepts?");
        System.out.println("2. 插入好奇心条目: entryId=" + entryId + (entryId > 0 ? " ✅" : " ❌"));
        if (entryId <= 0) passed = false;

        // 3. 验证可以读回
        var active = db.getActiveCuriosityEntries();
        boolean found = active.stream().anyMatch(e -> e.id == entryId);
        System.out.println("3. 读回验证: found=" + found + (found ? " ✅" : " ❌"));
        if (!found) passed = false;

        System.out.println("\n" + (passed ? "✅ 全部测试通过" : "❌ 有测试失败"));
        System.exit(passed ? 0 : 1);
    }
}
