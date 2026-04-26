package com.cna.db;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

@Slf4j
public class MemoryDB {
    private final ObjectMapper mapper = new ObjectMapper();
    // 引入HikariCP 连接池
    private static HikariDataSource dataSource;

    public MemoryDB() {
        initDataSource();
        initTables();
    }

    /**
     * 初始化高并发连接池
     */
    private synchronized void initDataSource() {
        if (dataSource != null) return;

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:agent_memory.db");
        // 极度关键：开启 SQLite 的 WAL 模式 (Write-Ahead Logging)
        // 这个魔法参数能让 SQLite 完美支持多线程读写不锁死！
        config.addDataSourceProperty("journal_mode", "WAL");

        config.setMaximumPoolSize(10); // 维持 10 个物理连接
        config.setConnectionTimeout(3000); // 拿不到连接最多等 3 秒
        config.setPoolName("Agent-Memory-Pool");

        dataSource = new HikariDataSource(config);
        log.info("[MemoryDB] HikariCP 高并发连接池挂载完毕，WAL 模式已开启！");
    }

    private void initTables() {
        // ... (这里的 SQL 语句和你之前的一模一样) ...
        String createCurrentSql = "CREATE TABLE IF NOT EXISTS Current_Memorys (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "content TEXT NOT NULL, " +
                "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)";

        String createDeepSql = "CREATE TABLE IF NOT EXISTS Deep_Memorys (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "vector_json TEXT NOT NULL, " +
                "content TEXT NOT NULL)";

        // 【核心改变】：从 dataSource 拿连接，而不是 DriverManager
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(createCurrentSql);
            stmt.execute(createDeepSql);
        } catch (SQLException e) {
            log.error("初始化记忆数据库失败", e);
        }
    }

    // ==========================================
    // 下面所有的增删改查方法，唯一的变化就是：
    // 把 DriverManager.getConnection(DB_URL)
    // 换成 dataSource.getConnection()
    // ==========================================

    public void insertCurrentMemory(String content) {
        String sql = "INSERT INTO Current_Memorys (content) VALUES (?)";
        try (Connection conn = dataSource.getConnection(); // 换成这句！
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, content);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            log.error("插入短期记忆失败", e);
        }
    }

    public int getCurrentMemoryCount() {
        String sql = "SELECT COUNT(*) FROM Current_Memorys";
        try (Connection conn = dataSource.getConnection(); // 换成这句！
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) return rs.getInt(1);
        } catch (SQLException e) {
            log.error("统计短期记忆失败", e);
        }
        return 0;
    }

    public List<String> getLatestCurrentMemories(int n) {
        List<String> result = new ArrayList<>();
        String sql = "SELECT content FROM Current_Memorys ORDER BY id DESC LIMIT ?";
        try (Connection conn = dataSource.getConnection(); // 换成这句！
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, n);
            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) {
                result.add(0, rs.getString("content"));
            }
        } catch (SQLException e) {
            log.error("获取最新短期记忆失败", e);
        }
        return result;
    }

    public List<String> popOldestCurrentMemories(int n) {
        List<String> result = new ArrayList<>();
        List<Integer> idsToDelete = new ArrayList<>();
        String selectSql = "SELECT id, content FROM Current_Memorys ORDER BY id ASC LIMIT ?";

        try (Connection conn = dataSource.getConnection(); // 换成这句！
             PreparedStatement selectStmt = conn.prepareStatement(selectSql)) {
            selectStmt.setInt(1, n);
            ResultSet rs = selectStmt.executeQuery();
            while (rs.next()) {
                idsToDelete.add(rs.getInt("id"));
                result.add(rs.getString("content"));
            }

            if (!idsToDelete.isEmpty()) {
                String deleteSql = "DELETE FROM Current_Memorys WHERE id = ?";
                // 开启事务保证原子性
                conn.setAutoCommit(false);
                try (PreparedStatement deleteStmt = conn.prepareStatement(deleteSql)) {
                    for (int id : idsToDelete) {
                        deleteStmt.setInt(1, id);
                        deleteStmt.addBatch();
                    }
                    deleteStmt.executeBatch();
                    conn.commit(); // 提交事务
                } catch (SQLException e) {
                    conn.rollback(); // 出错回滚
                    throw e;
                } finally {
                    conn.setAutoCommit(true);
                }
            }
        } catch (SQLException e) {
            log.error("提取并删除老旧记忆失败", e);
        }
        return result;
    }

    public void insertDeepMemory(double[] vector, String content) {
        String sql = "INSERT INTO Deep_Memorys (vector_json, content) VALUES (?, ?)";
        try (Connection conn = dataSource.getConnection(); // 换成这句！
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, mapper.writeValueAsString(vector));
            pstmt.setString(2, content);
            pstmt.executeUpdate();
        } catch (SQLException | JsonProcessingException e) {
            log.error("插入长期深度记忆失败", e);
        }
    }

    public List<DeepMemoryEntry> getAllDeepMemories() {
        List<DeepMemoryEntry> result = new ArrayList<>();
        String sql = "SELECT id, vector_json, content FROM Deep_Memorys";
        try (Connection conn = dataSource.getConnection(); // 换成这句！
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                int id = rs.getInt("id");
                String content = rs.getString("content");
                double[] vector = mapper.readValue(rs.getString("vector_json"), double[].class);
                result.add(new DeepMemoryEntry(id, vector, content));
            }
        } catch (Exception e) {
            log.error("获取全量深度记忆失败", e);
        }
        return result;
    }

    // 辅助数据结构
    public static class DeepMemoryEntry {
        public final int id;
        public final double[] vector;
        public final String content;

        public DeepMemoryEntry(int id, double[] vector, String content) {
            this.id = id;
            this.vector = vector;
            this.content = content;
        }
    }
}