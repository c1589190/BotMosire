package com.cna.db;

import com.cna.config.ConfigsManager;
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
    // ObjectMapper 是线程安全的，建议作为 static final 共用
    private static final ObjectMapper mapper = new ObjectMapper();
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
        config.setJdbcUrl(ConfigsManager.DB_URL);
        // 开启 SQLite WAL 模式，通过 connectionInitSql 执行 PRAGMA 才是对 SQLite JDBC 正确的做法
        config.setConnectionInitSql("PRAGMA journal_mode=WAL; PRAGMA synchronous=NORMAL;");

        config.setMaximumPoolSize(10); // 维持 10 个物理连接
        config.setConnectionTimeout(3000); // 拿不到连接最多等 3 秒
        config.setPoolName("Agent-Memory-Pool");

        dataSource = new HikariDataSource(config);
        log.info("[MemoryDB] HikariCP 高并发连接池挂载完毕，WAL 模式已开启！");
    }

    private void initTables() {
        String createCurrentSql = "CREATE TABLE IF NOT EXISTS Current_Memorys (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "content TEXT NOT NULL, " +
                "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)";

        String createDeepSql = "CREATE TABLE IF NOT EXISTS Deep_Memorys (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "vector_json TEXT NOT NULL, " +
                "content TEXT NOT NULL)";

        String createFeelingSql = "CREATE TABLE IF NOT EXISTS Feeling_Dimensions (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "concept TEXT NOT NULL UNIQUE, " +
                "vector_json TEXT NOT NULL, " +
                "hit_weight REAL DEFAULT 1.0, " +
                "trigger_count INTEGER DEFAULT 0)";

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(createCurrentSql);
            stmt.execute(createDeepSql);
            stmt.execute(createFeelingSql);
        } catch (SQLException e) {
            log.error("初始化记忆数据库失败", e);
        }
    }

    public void insertCurrentMemory(String content) {
        String sql = "INSERT INTO Current_Memorys (content) VALUES (?)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, content);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            log.error("插入短期记忆失败", e);
        }
    }

    public int getCurrentMemoryCount() {
        String sql = "SELECT COUNT(*) FROM Current_Memorys";
        try (Connection conn = dataSource.getConnection();
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
        try (Connection conn = dataSource.getConnection();
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

        try (Connection conn = dataSource.getConnection();
             PreparedStatement selectStmt = conn.prepareStatement(selectSql)) {
            selectStmt.setInt(1, n);
            ResultSet rs = selectStmt.executeQuery();
            while (rs.next()) {
                idsToDelete.add(rs.getInt("id"));
                result.add(rs.getString("content"));
            }

            if (!idsToDelete.isEmpty()) {
                String deleteSql = "DELETE FROM Current_Memorys WHERE id = ?";
                conn.setAutoCommit(false);
                try (PreparedStatement deleteStmt = conn.prepareStatement(deleteSql)) {
                    for (int id : idsToDelete) {
                        deleteStmt.setInt(1, id);
                        deleteStmt.addBatch();
                    }
                    deleteStmt.executeBatch();
                    conn.commit();
                } catch (SQLException e) {
                    conn.rollback();
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
        try (Connection conn = dataSource.getConnection();
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
        try (Connection conn = dataSource.getConnection();
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

    // ==========================================
    // Feeling_Dimensions 感觉维度支持库
    // ==========================================

    public static class FeelingDimension {
        public final int id;
        public final String concept;
        public final double[] vector;
        public final double weight; // 保持为 double
        public final int triggerCount;

        public FeelingDimension(int id, String concept, double[] vector, double weight, int triggerCount) {
            this.id = id;
            this.concept = concept;
            this.vector = vector;
            this.weight = weight;
            this.triggerCount = triggerCount;
        }
    }

    /**
     * 插入新的感觉维度
     * 修改：initialWeight 从 float 改为 double
     */
    public void insertFeelingDimension(String concept, double[] vector, double initialWeight) {
        String sql = "INSERT OR IGNORE INTO Feeling_Dimensions (concept, vector_json, hit_weight, trigger_count) VALUES (?, ?, ?, 1)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, concept);
            pstmt.setString(2, mapper.writeValueAsString(vector));
            pstmt.setDouble(3, initialWeight); // 统一使用 setDouble
            pstmt.executeUpdate();
            log.info("[MemoryDB] 成功写入新感觉维度: {}", concept);
        } catch (SQLException | JsonProcessingException e) {
            log.error("插入感觉维度失败: " + concept, e);
        }
    }

    /**
     * 获取全量感觉维度加载到内存 (映射最新的 trigger_count)
     */
    public List<FeelingDimension> getAllFeelingDimensions() {
        List<FeelingDimension> result = new ArrayList<>();
        String sql = "SELECT id, concept, vector_json, hit_weight, trigger_count FROM Feeling_Dimensions";
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                double[] vector = mapper.readValue(rs.getString("vector_json"), double[].class);
                result.add(new FeelingDimension(
                        rs.getInt("id"),
                        rs.getString("concept"),
                        vector,
                        rs.getDouble("hit_weight"), // 修改：从 getFloat 改为 getDouble
                        rs.getInt("trigger_count")
                ));
            }
        } catch (Exception e) {
            log.error("获取全量感觉维度失败", e);
        }
        return result;
    }

    /**
     * 更新特定维度权重，并累加触发频次计数。
     * 修改：addedWeight 从 float 改为 double
     */
    public int addWeightToDimension(int id, double addedWeight) {
        String updateSql = "UPDATE Feeling_Dimensions SET hit_weight = hit_weight + ?, trigger_count = trigger_count + 1 WHERE id = ?";
        String querySql = "SELECT trigger_count FROM Feeling_Dimensions WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(updateSql)) {
            pstmt.setDouble(1, addedWeight); // 统一使用 setDouble
            pstmt.setInt(2, id);
            pstmt.executeUpdate();

            try (PreparedStatement queryPstmt = conn.prepareStatement(querySql)) {
                queryPstmt.setInt(1, id);
                try (ResultSet rs = queryPstmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getInt("trigger_count");
                    }
                }
            }
        } catch (SQLException e) {
            log.error("更新感觉维度权重与频次失败 ID: " + id, e);
        }
        return 0;
    }

    /**
     * 感觉习惯化机制。
     * 保持不变：入参本就是 double
     */
    public void bluntDimension(int id, double bluntWeight) {
        String sql = "UPDATE Feeling_Dimensions SET hit_weight = ?, trigger_count = 0 WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setDouble(1, bluntWeight); // 使用 setDouble
            pstmt.setInt(2, id);
            pstmt.executeUpdate();
            log.info("[MemoryDB] ⚙️ 该感觉突触极度疲劳饱和，执行生物学钝化：权重强行挂钩跌停值 {}, 计数清零。", bluntWeight);
        } catch (SQLException e) {
            log.error("执行生物学钝化重置失败 ID: " + id, e);
        }
    }

    /**
     * 记忆衰减机制 (Tick 底层支持)
     * 保持不变：入参本就是 double
     */
    public int applyGlobalDecay(double decayAmount) {
        int deletedCount = 0;
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                String updateSql = "UPDATE Feeling_Dimensions SET " +
                        "hit_weight = hit_weight - ?, " +
                        "trigger_count = CASE WHEN trigger_count > 0 THEN trigger_count - 1 ELSE 0 END";

                try (PreparedStatement updateStmt = conn.prepareStatement(updateSql)) {
                    updateStmt.setDouble(1, decayAmount); // 使用 setDouble
                    updateStmt.executeUpdate();
                }

                String deleteSql = "DELETE FROM Feeling_Dimensions WHERE hit_weight <= 0";
                try (PreparedStatement deleteStmt = conn.prepareStatement(deleteSql)) {
                    deletedCount = deleteStmt.executeUpdate();
                }

                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            log.error("[MemoryDB] 执行全局记忆衰减与疲劳度代谢失败", e);
        }
        return deletedCount;
    }
}