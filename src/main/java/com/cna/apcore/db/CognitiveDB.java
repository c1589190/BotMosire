package com.cna.apcore.db;

import com.cna.config.ConfigsManager;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * V4 认知核心共享数据库基类。
 *
 * 提供独立的 HikariCP 连接池（指向同一 SQLite 文件），
 * 以及表结构迁移辅助方法 ensureColumn() 和 cosineSimilarity()。
 */
@Slf4j
public class CognitiveDB {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static volatile HikariDataSource dataSource;

    /**
     * 初始化 HikariCP 连接池（与 MemoryDB 指向同一文件，但独立连接池，2 连接即可）。
     */
    static synchronized void initDataSource() {
        if (dataSource != null && !dataSource.isClosed()) return;

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(ConfigsManager.DB_URL);
        config.setConnectionInitSql("PRAGMA journal_mode=WAL; PRAGMA synchronous=NORMAL;");
        config.setMaximumPoolSize(3);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(3000);
        config.setPoolName("V4-Core-Pool");

        dataSource = new HikariDataSource(config);
        log.info("[CognitiveDB] V4 HikariCP 连接池已挂载 (poolSize=3, WAL)");
    }

    /** 关闭连接池 */
    public static synchronized void shutdown() {
        if (dataSource != null && !dataSource.isClosed()) {
            try {
                dataSource.close();
                log.info("[CognitiveDB] HikariCP 连接池已关闭");
            } catch (Exception e) {
                log.error("[CognitiveDB] 关闭连接池失败", e);
            } finally {
                dataSource = null;
            }
        }
    }

    /** 获取连接（供子类 FeelingsDB/ExperiencesDB 使用） */
    static Connection getConnection() throws SQLException {
        if (dataSource == null || dataSource.isClosed()) {
            initDataSource();
        }
        return dataSource.getConnection();
    }

    /** 获取 ObjectMapper 实例（供子类序列化 JSON 字段使用） */
    static ObjectMapper getMapper() {
        return mapper;
    }

    // ==========================================
    // 辅助方法
    // ==========================================

    /** 检测表中是否已有某列，没有则 ALTER TABLE ADD COLUMN */
    static void ensureColumn(String table, String column, String definition) {
        try (Connection conn = getConnection()) {
            java.sql.DatabaseMetaData meta = conn.getMetaData();
            try (ResultSet rs = meta.getColumns(null, null, table, column)) {
                if (!rs.next()) {
                    String sql = "ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition;
                    try (Statement stmt = conn.createStatement()) {
                        stmt.execute(sql);
                        log.info("[CognitiveDB] 表 {} 已追加列 {} (定义: {})", table, column, definition);
                    }
                }
            }
        } catch (SQLException e) {
            log.warn("[CognitiveDB] 检测/追加列 {}.{} 失败: {}", table, column, e.getMessage());
        }
    }

    /** 余弦相似度 */
    public static double cosineSimilarity(double[] a, double[] b) {
        if (a == null || b == null || a.length != b.length) return 0.0;
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return (na == 0 || nb == 0) ? 0.0 : dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}
