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
    private static final ObjectMapper mapper = new ObjectMapper();
    private static HikariDataSource dataSource;

    public MemoryDB() {
        initDataSource();
        initTables();
    }

    private synchronized void initDataSource() {
        if (dataSource != null) return;

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(ConfigsManager.DB_URL);
        config.setConnectionInitSql("PRAGMA journal_mode=WAL; PRAGMA synchronous=NORMAL;");

        config.setMaximumPoolSize(10);
        config.setConnectionTimeout(3000);
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

        // 【兼容保留】：不动表结构，保留 hit_weight 和 trigger_count
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

    // ==========================================
    // 丢失的短期记忆与深度记忆 CRUD (帮你找回来了)
    // ==========================================

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
        public final double hitWeight;   // 留作他用的额外乘区/标记
        public final int triggerCount;   // 现在的绝对主力：充当 active_count

        public FeelingDimension(int id, String concept, double[] vector, double hitWeight, int triggerCount) {
            this.id = id;
            this.concept = concept;
            this.vector = vector;
            this.hitWeight = hitWeight;
            this.triggerCount = triggerCount;
        }
    }

    /**
     * 插入新的感觉维度：hit_weight 默认为 1.0，trigger_count 初始为 1
     */
    /**
     * 【修改】：插入新的感觉维度：hit_weight 不再硬编码为1.0，而是由初次评估的客观极性注入
     */
    public void insertFeelingDimension(String concept, double[] vector, double initialHitWeight) {
        String sql = "INSERT OR IGNORE INTO Feeling_Dimensions (concept, vector_json, hit_weight, trigger_count) VALUES (?, ?, ?, 1)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, concept);
            pstmt.setString(2, mapper.writeValueAsString(vector));
            pstmt.setDouble(3, initialHitWeight);
            pstmt.executeUpdate();
            log.info("[MemoryDB] 成功生长出新感觉维度: {} (初始化 trigger_count = 1, hit_weight = {})", concept, initialHitWeight);
        } catch (SQLException | JsonProcessingException e) {
            log.error("插入感觉维度失败: " + concept, e);
        }
    }

    /**
     * 【新增】：暴露一个绝对客观的物理接口，用于直接覆写指定维度的效价权重 (hit_weight)
     */
    public void updateDimensionHitWeight(int id, double newHitWeight) {
        String sql = "UPDATE Feeling_Dimensions SET hit_weight = ? WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setDouble(1, newHitWeight);
            pstmt.setInt(2, id);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            log.error("更新感觉维度极性权重失败 ID: " + id, e);
        }
    }

    /**
     * 高相似度替换：用新概念名和新向量完全覆写旧记录，同时重置 trigger_count 为 1。
     * 适用于新概念与旧概念语义足够接近、可直接视为同一概念进化的情况。
     */
    public void replaceFeelingDimension(int id, String newConcept, double[] newVector, double newHitWeight) {
        String sql = "UPDATE Feeling_Dimensions SET concept = ?, vector_json = ?, hit_weight = ?, trigger_count = 1 WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, newConcept);
            pstmt.setString(2, mapper.writeValueAsString(newVector));
            pstmt.setDouble(3, newHitWeight);
            pstmt.setInt(4, id);
            pstmt.executeUpdate();
        } catch (SQLException | JsonProcessingException e) {
            log.error("替换感觉维度失败 ID: " + id, e);
        }
    }

    /**
     * 【保险修复版】获取全量感觉维度。
     * 遍历每条记录时，实时检测 vector_json 的合法性，
     * 若出现 null、空串、解析失败或维度长度与有效记录不一致，
     * 则立刻根据 concept 重新计算向量并 UPDATE 回数据库；
     * 若重计算也失败（如 embedder 异常），则写入全零向量。
     *
     * @param embedder  文本 → 向量的函数，如 concept -> embeddingService.embed(concept)
     * @return 修复后的感觉维度列表（所有记录的向量均保证非 null 且维度一致）
     */
    public List<FeelingDimension> getAllFeelingDimensionsSafe(java.util.function.Function<String, double[]> embedder) {
        List<FeelingDimension> result = new ArrayList<>();
        String selectSql = "SELECT id, concept, vector_json, hit_weight, trigger_count FROM Feeling_Dimensions";
        int expectedDim = -1;

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(selectSql)) {

            while (rs.next()) {
                int id = rs.getInt("id");
                String concept = rs.getString("concept");
                String vectorJson = rs.getString("vector_json");
                double hitWeight = rs.getDouble("hit_weight");
                int triggerCount = rs.getInt("trigger_count");
                double[] vector = null;

                // 1. 尝试解析已有向量
                boolean needRepair = false;
                if (vectorJson != null && !vectorJson.isEmpty()) {
                    try {
                        vector = mapper.readValue(vectorJson, double[].class);
                        if (vector == null || vector.length == 0) {
                            needRepair = true;
                        } else {
                            if (expectedDim == -1) {
                                expectedDim = vector.length;   // 第一条有效记录确定标准维度
                            } else if (vector.length != expectedDim) {
                                needRepair = true;
                            }
                        }
                    } catch (JsonProcessingException e) {
                        needRepair = true;
                    }
                } else {
                    needRepair = true;
                }

                // 2. 需要修复：重新生成向量并写回数据库
                if (needRepair) {
                    log.warn("[MemoryDB] 感觉维度 id={} concept='{}' 向量异常，尝试重计算...", id, concept);
                    try {
                        vector = embedder.apply(concept);
                        if (vector == null || vector.length == 0) {
                            throw new RuntimeException("embedder 返回空向量");
                        }
                        // 更新标准维度（如果之前全是坏数据，现在第一次得到有效向量）
                        if (expectedDim == -1) {
                            expectedDim = vector.length;
                        } else if (vector.length != expectedDim) {
                            // 嵌入模型返回的维度不一致，强制截断/对齐？这里仍视为失败，用零向量保底
                            throw new RuntimeException("嵌入维度不一致: " + vector.length + " vs " + expectedDim);
                        }
                        // 写回数据库
                        updateVectorOnly(conn, id, vector);
                        log.info("[MemoryDB] 感觉维度 id={} 向量已修复", id);
                    } catch (Exception ex) {
                        log.error("[MemoryDB] 感觉维度 id={} 重计算失败，将使用全零向量。错误: {}", id, ex.getMessage());
                        // 生成全零向量
                        if (expectedDim > 0) {
                            vector = new double[expectedDim];
                        } else {
                            // 还不知道维度，给一个默认长度（如 128，根据你的模型调整）
                            vector = new double[128];   // 可根据实际情况调整
                            expectedDim = vector.length;
                        }
                        // 将全零向量写回数据库
                        try {
                            updateVectorOnly(conn, id, vector);
                        } catch (Exception updateEx) {
                            log.error("[MemoryDB] 更新全零向量也失败 id={}", id, updateEx);
                        }
                    }
                }

                // 3. 现在 vector 一定非 null，加入结果集
                result.add(new FeelingDimension(id, concept, vector, hitWeight, triggerCount));
            }
        } catch (SQLException e) {
            log.error("[MemoryDB] 获取感觉维度时发生数据库错误", e);
        }
        return result;
    }

    /**
     * 仅更新某行的 vector_json 字段（不修改 hit_weight 和 trigger_count）
     */
    private void updateVectorOnly(Connection conn, int id, double[] vector) throws SQLException, JsonProcessingException {
        String updateSql = "UPDATE Feeling_Dimensions SET vector_json = ? WHERE id = ?";
        try (PreparedStatement pstmt = conn.prepareStatement(updateSql)) {
            pstmt.setString(1, mapper.writeValueAsString(vector));
            pstmt.setInt(2, id);
            pstmt.executeUpdate();
        }
    }

    /**
     * 保留原有的无参方法（不修复，直接返回，可能含有坏数据）
     * 建议逐步替换为 Safe 版本
     */
    @Deprecated
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
                        rs.getDouble("hit_weight"),
                        rs.getInt("trigger_count")
                ));
            }
        } catch (Exception e) {
            log.error("获取全量感觉维度失败", e);
        }
        return result;
    }

    /**
     * 【重构核心】：触发并打击某个维度 (借用 trigger_count 当 active_count)
     * 规则：如果当前 trigger_count < 0，直接归零；否则 + 1。不对 hit_weight 做任何操作。
     */
    public int hitDimension(int id) {
        String updateSql = "UPDATE Feeling_Dimensions SET trigger_count = CASE WHEN trigger_count < 0 THEN 0 ELSE trigger_count + 1 END WHERE id = ?";
        String querySql = "SELECT trigger_count FROM Feeling_Dimensions WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(updateSql)) {
            pstmt.setInt(1, id);
            pstmt.executeUpdate();

            // 返回最新值
            try (PreparedStatement queryPstmt = conn.prepareStatement(querySql)) {
                queryPstmt.setInt(1, id);
                try (ResultSet rs = queryPstmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getInt("trigger_count");
                    }
                }
            }
        } catch (SQLException e) {
            log.error("触发感觉维度状态机失败 ID: " + id, e);
        }
        return 0;
    }

    /**
     * 【重构核心】：全局记忆衰减 (Tick)
     * 规则：每 tick 所有维度的 trigger_count 统一 -1，允许掉入负数深水区。hit_weight 不动。
     */
    public void applyGlobalTick() {
        String sql = "UPDATE Feeling_Dimensions SET trigger_count = trigger_count - 1";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            int affected = stmt.executeUpdate();
            log.debug("[MemoryDB] Tick 执行完毕，全局 {} 个维度的 trigger_count 统一 -1", affected);
        } catch (SQLException e) {
            log.error("[MemoryDB] 执行全局 trigger_count 衰减失败", e);
        }
    }
}