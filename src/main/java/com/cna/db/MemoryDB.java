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

    /** 关闭 HikariCP 连接池，配合 Main shutdown hook 防止 process exit 时 SQLite WAL 残留。 */
    public static synchronized void shutdown() {
        if (dataSource != null && !dataSource.isClosed()) {
            try {
                dataSource.close();
                log.info("[MemoryDB] HikariCP 连接池已优雅关闭。");
            } catch (Exception e) {
                log.error("[MemoryDB] 关闭连接池失败", e);
            } finally {
                dataSource = null;
            }
        }
    }

    private static synchronized void initDataSource() {
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

        // 感觉超图边表
        String createHypergraphSql = "CREATE TABLE IF NOT EXISTS Feeling_Hypergraph (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "source_dim_id INTEGER NOT NULL, " +
                "target_dim_id INTEGER NOT NULL, " +
                "weight REAL DEFAULT 1.0, " +
                "relation_type TEXT DEFAULT 'associated', " +
                "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                "UNIQUE(source_dim_id, target_dim_id))";

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(createCurrentSql);
            stmt.execute(createDeepSql);
            stmt.execute(createFeelingSql);
            stmt.execute(createHypergraphSql);

            // 好奇心列表表
            String createCuriositySql = "CREATE TABLE IF NOT EXISTS Curiosity_List (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "source_dim_id INTEGER NOT NULL, " +
                    "dissonant_dim_ids TEXT NOT NULL, " +
                    "source_concept TEXT NOT NULL, " +
                    "dissonant_concepts TEXT NOT NULL, " +
                    "trigger_count INTEGER DEFAULT 1, " +
                    "is_active INTEGER DEFAULT 1, " +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                    "last_triggered_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                    "resolved_at TIMESTAMP, " +
                    "resolution_note TEXT DEFAULT '')";
            stmt.execute(createCuriositySql);
        } catch (SQLException e) {
            log.error("初始化记忆数据库失败", e);
        }

        // 【保险措施】: 为已有但缺列的表追加列（既往不咎）
        ensureColumn("Current_Memorys", "sources", "TEXT DEFAULT '[]'");
        ensureColumn("Deep_Memorys", "sources", "TEXT DEFAULT '[]'");
        ensureColumn("Feeling_Dimensions", "status", "TEXT DEFAULT 'stable'");
        ensureColumn("Feeling_Dimensions", "llm_notes", "TEXT DEFAULT ''");
        ensureColumn("Curiosity_List", "resolution_note", "TEXT DEFAULT ''");
        ensureColumn("Curiosity_List", "llm_question", "TEXT DEFAULT ''");
    }

    /**
     * 【保险措施】检测表中是否已有某列，没有则 ALTER TABLE ADD COLUMN。
     * 适用于所有表的增量迁移，旧数据自动填充 DEFAULT 值。
     */
    private void ensureColumn(String table, String column, String definition) {
        try (Connection conn = dataSource.getConnection()) {
            java.sql.DatabaseMetaData meta = conn.getMetaData();
            try (java.sql.ResultSet rs = meta.getColumns(null, null, table, column)) {
                if (!rs.next()) {
                    String sql = "ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition;
                    try (Statement stmt = conn.createStatement()) {
                        stmt.execute(sql);
                        log.info("[MemoryDB] 表 {} 已追加列 {} (定义: {})", table, column, definition);
                    }
                }
            }
        } catch (SQLException e) {
            log.warn("[MemoryDB] 检测/追加列 {}.{} 失败（可能已存在或权限不足）: {}", table, column, e.getMessage());
        }
    }

    // ==========================================
    // 丢失的短期记忆与深度记忆 CRUD (帮你找回来了)
    // ==========================================

    public void insertCurrentMemory(String content) {
        insertCurrentMemory(content, java.util.List.of());
    }

    public void insertCurrentMemory(String content, java.util.List<String> sources) {
        String sql = "INSERT INTO Current_Memorys (content, sources) VALUES (?, ?)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, content);
            pstmt.setString(2, mapper.writeValueAsString(sources != null ? sources : java.util.List.of()));
            pstmt.executeUpdate();
        } catch (SQLException | JsonProcessingException e) {
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

    public static class CurrentMemoryEntry {
        public final int id;
        public final String content;
        public final java.util.List<String> sources;

        public CurrentMemoryEntry(int id, String content, java.util.List<String> sources) {
            this.id = id;
            this.content = content;
            this.sources = sources;
        }
    }

    public List<CurrentMemoryEntry> getLatestCurrentMemories(int n) {
        List<CurrentMemoryEntry> result = new ArrayList<>();
        String sql = "SELECT id, content, sources FROM Current_Memorys ORDER BY id DESC LIMIT ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, n);
            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) {
                result.add(0, new CurrentMemoryEntry(
                        rs.getInt("id"),
                        rs.getString("content"),
                        parseSources(rs.getString("sources"))
                ));
            }
        } catch (SQLException e) {
            log.error("获取最新短期记忆失败", e);
        }
        return result;
    }

    /**
     * 兼容旧调用：只返回内容字符串列表
     */
    public List<String> getLatestCurrentMemoryContents(int n) {
        List<String> result = new ArrayList<>();
        for (CurrentMemoryEntry e : getLatestCurrentMemories(n)) {
            result.add(e.content);
        }
        return result;
    }

    public List<CurrentMemoryEntry> popOldestCurrentMemories(int n) {
        List<CurrentMemoryEntry> result = new ArrayList<>();
        List<Integer> idsToDelete = new ArrayList<>();
        String selectSql = "SELECT id, content, sources FROM Current_Memorys ORDER BY id ASC LIMIT ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement selectStmt = conn.prepareStatement(selectSql)) {
            selectStmt.setInt(1, n);
            ResultSet rs = selectStmt.executeQuery();
            while (rs.next()) {
                int id = rs.getInt("id");
                idsToDelete.add(id);
                result.add(new CurrentMemoryEntry(
                        id,
                        rs.getString("content"),
                        parseSources(rs.getString("sources"))
                ));
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
        insertDeepMemory(vector, content, java.util.List.of());
    }

    public void insertDeepMemory(double[] vector, String content, java.util.List<String> sources) {
        String sql = "INSERT INTO Deep_Memorys (vector_json, content, sources) VALUES (?, ?, ?)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, mapper.writeValueAsString(vector));
            pstmt.setString(2, content);
            pstmt.setString(3, mapper.writeValueAsString(sources != null ? sources : java.util.List.of()));
            pstmt.executeUpdate();
        } catch (SQLException | JsonProcessingException e) {
            log.error("插入长期深度记忆失败", e);
        }
    }

    public List<DeepMemoryEntry> getAllDeepMemories() {
        List<DeepMemoryEntry> result = new ArrayList<>();
        String sql = "SELECT id, vector_json, content, sources FROM Deep_Memorys";
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                int id = rs.getInt("id");
                String content = rs.getString("content");
                double[] vector = mapper.readValue(rs.getString("vector_json"), double[].class);
                java.util.List<String> sources = parseSources(rs.getString("sources"));
                result.add(new DeepMemoryEntry(id, vector, content, sources));
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
        public final java.util.List<String> sources;

        public DeepMemoryEntry(int id, double[] vector, String content, java.util.List<String> sources) {
            this.id = id;
            this.vector = vector;
            this.content = content;
            this.sources = sources;
        }
    }

    /**
     * 解析 sources JSON 字符串为 List，容错处理旧数据中的 null / 空串 / 解析异常。
     */
    private java.util.List<String> parseSources(String sourcesJson) {
        if (sourcesJson == null || sourcesJson.isBlank()) {
            return new ArrayList<>();
        }
        try {
            @SuppressWarnings("unchecked")
            java.util.List<String> list = mapper.readValue(sourcesJson, java.util.List.class);
            return list != null ? list : new ArrayList<>();
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /**
     * 将新的来源标识符合并到指定深度记忆的 sources 中（去重追加）。
     * @param id 深度记忆 ID
     * @param newSources 要追加的来源列表
     */
    public void enrichDeepMemorySources(int id, java.util.List<String> newSources) {
        if (newSources == null || newSources.isEmpty()) return;

        // 先读取当前 sources
        String selectSql = "SELECT sources FROM Deep_Memorys WHERE id = ?";
        String updateSql = "UPDATE Deep_Memorys SET sources = ? WHERE id = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement selectStmt = conn.prepareStatement(selectSql)) {
            selectStmt.setInt(1, id);
            ResultSet rs = selectStmt.executeQuery();
            if (rs.next()) {
                java.util.List<String> existing = parseSources(rs.getString("sources"));
                boolean changed = false;
                for (String s : newSources) {
                    if (s != null && !s.isBlank() && !existing.contains(s)) {
                        existing.add(s);
                        changed = true;
                    }
                }
                if (changed) {
                    try (PreparedStatement updateStmt = conn.prepareStatement(updateSql)) {
                        updateStmt.setString(1, mapper.writeValueAsString(existing));
                        updateStmt.setInt(2, id);
                        updateStmt.executeUpdate();
                        log.info("[MemoryDB] 深度记忆 id={} 来源已丰富: {}", id, existing);
                    }
                }
            }
        } catch (SQLException | JsonProcessingException e) {
            log.error("丰富深度记忆来源失败 id={}", id, e);
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
        public final String status;      // stable / dissonant / resolving
        public final String llmNotes;    // LLM 迭代分析记录

        public FeelingDimension(int id, String concept, double[] vector, double hitWeight, int triggerCount) {
            this(id, concept, vector, hitWeight, triggerCount, "stable", "");
        }

        public FeelingDimension(int id, String concept, double[] vector, double hitWeight, int triggerCount,
                                String status, String llmNotes) {
            this.id = id;
            this.concept = concept;
            this.vector = vector;
            this.hitWeight = hitWeight;
            this.triggerCount = triggerCount;
            this.status = status != null ? status : "stable";
            this.llmNotes = llmNotes != null ? llmNotes : "";
        }

        /** 是否为违和感（未解决） */
        public boolean isDissonant() { return "dissonant".equals(status); }
    }

    /**
     * 插入新的感觉维度：hit_weight 默认为 1.0，trigger_count 初始为 1
     */
    /**
     * 【修改】：插入新的感觉维度：hit_weight 不再硬编码为1.0，而是由初次评估的客观极性注入
     */
    /**
     * 插入新的感觉维度，返回生成的 ID（如果已存在同名维度则返回其 ID）。
     */
    public int insertFeelingDimension(String concept, double[] vector, double initialHitWeight) {
        String insertSql = "INSERT OR IGNORE INTO Feeling_Dimensions (concept, vector_json, hit_weight, trigger_count) VALUES (?, ?, ?, 1)";
        String querySql = "SELECT id FROM Feeling_Dimensions WHERE concept = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(insertSql)) {
            pstmt.setString(1, concept);
            pstmt.setString(2, mapper.writeValueAsString(vector));
            pstmt.setDouble(3, initialHitWeight);
            pstmt.executeUpdate();

            // 获取实际 ID（新插入或已存在的）
            try (PreparedStatement qStmt = conn.prepareStatement(querySql)) {
                qStmt.setString(1, concept);
                try (ResultSet rs = qStmt.executeQuery()) {
                    if (rs.next()) {
                        int id = rs.getInt("id");
                        log.info("[MemoryDB] 成功生长出新感觉维度: {} (id={}, trigger_count=1, hit_weight={})", concept, id, initialHitWeight);
                        return id;
                    }
                }
            }
            log.warn("[MemoryDB] 插入感觉维度后无法获取 ID: {}", concept);
            return -1;
        } catch (SQLException | JsonProcessingException e) {
            log.error("插入感觉维度失败: " + concept, e);
            return -1;
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
        String selectSql = "SELECT id, concept, vector_json, hit_weight, trigger_count, status, llm_notes FROM Feeling_Dimensions";
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
                String status = rs.getString("status");
                String llmNotes = rs.getString("llm_notes");
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
                result.add(new FeelingDimension(id, concept, vector, hitWeight, triggerCount, status, llmNotes));
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
        String sql = "SELECT id, concept, vector_json, hit_weight, trigger_count, status, llm_notes FROM Feeling_Dimensions";
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
                        rs.getInt("trigger_count"),
                        rs.getString("status"),
                        rs.getString("llm_notes")
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

    /**
     * 更新感觉维度的 status 和 llm_notes（违和迭代分析入口）。
     */
    public void updateDimensionStatusAndNotes(int id, String status, String llmNotes) {
        String sql = "UPDATE Feeling_Dimensions SET status = ?, llm_notes = ? WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, status);
            pstmt.setString(2, llmNotes);
            pstmt.setInt(3, id);
            pstmt.executeUpdate();
            log.info("[MemoryDB] 感觉维度 id={} 状态更新: status={}", id, status);
        } catch (SQLException e) {
            log.error("[MemoryDB] 更新感觉维度状态失败 id={}", id, e);
        }
    }

    // ==========================================
    // 感觉超图 (Feeling_Hypergraph) CRUD
    // ==========================================

    public static class HypergraphEdge {
        public final int id;
        public final int sourceDimId;
        public final int targetDimId;
        public final double weight;
        public final String relationType;

        public HypergraphEdge(int id, int sourceDimId, int targetDimId, double weight, String relationType) {
            this.id = id;
            this.sourceDimId = sourceDimId;
            this.targetDimId = targetDimId;
            this.weight = weight;
            this.relationType = relationType;
        }
    }

    public static class CuriosityEntry {
        public final int id;
        public final int sourceDimId;
        public final List<Integer> dissonantDimIds;
        public final String sourceConcept;
        public final List<String> dissonantConcepts;
        public final int triggerCount;
        public final boolean isActive;
        public final String createdAt;
        public final String lastTriggeredAt;
        public final String resolvedAt;
        public final String resolutionNote;
        public final String llmQuestion;

        public CuriosityEntry(int id, int sourceDimId, String dissonantDimIdsJson,
                               String sourceConcept, String dissonantConceptsJson,
                               int triggerCount, boolean isActive,
                               String createdAt, String lastTriggeredAt,
                               String resolvedAt, String resolutionNote, String llmQuestion) {
            this.id = id;
            this.sourceDimId = sourceDimId;
            List<Integer> dissIds = new ArrayList<>();
            List<String> dissConcepts = new ArrayList<>();
            try {
                com.fasterxml.jackson.core.type.TypeReference<List<Integer>> intRef =
                        new com.fasterxml.jackson.core.type.TypeReference<List<Integer>>() {};
                dissIds = mapper.readValue(dissonantDimIdsJson, intRef);
                com.fasterxml.jackson.core.type.TypeReference<List<String>> strRef =
                        new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {};
                dissConcepts = mapper.readValue(dissonantConceptsJson, strRef);
            } catch (Exception e) {
                log.warn("[MemoryDB] 解析 CuriosityEntry JSON 失败: id={}", id, e);
            }
            this.dissonantDimIds = dissIds;
            this.sourceConcept = sourceConcept;
            this.dissonantConcepts = dissConcepts;
            this.triggerCount = triggerCount;
            this.isActive = isActive;
            this.createdAt = createdAt;
            this.lastTriggeredAt = lastTriggeredAt;
            this.resolvedAt = resolvedAt;
            this.resolutionNote = resolutionNote;
            this.llmQuestion = llmQuestion != null ? llmQuestion : "";
        }
    }

    /**
     * 插入或加权更新超图边。已存在则 weight += weightInc 且 relationType 更新。
     */
    public void upsertHypergraphEdge(int srcId, int tgtId, String relationType, double weightInc) {
        String sql = "INSERT INTO Feeling_Hypergraph (source_dim_id, target_dim_id, weight, relation_type) " +
                "VALUES (?, ?, ?, ?) " +
                "ON CONFLICT(source_dim_id, target_dim_id) DO UPDATE SET " +
                "weight = weight + ?, relation_type = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, srcId);
            pstmt.setInt(2, tgtId);
            pstmt.setDouble(3, weightInc);
            pstmt.setString(4, relationType);
            pstmt.setDouble(5, weightInc);
            pstmt.setString(6, relationType);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            log.error("[MemoryDB] upsert 超图边失败 src={} tgt={}", srcId, tgtId, e);
        }
    }

    /**
     * 查询所有超图边。
     */
    public List<HypergraphEdge> getAllHypergraphEdges() {
        List<HypergraphEdge> result = new ArrayList<>();
        String sql = "SELECT id, source_dim_id, target_dim_id, weight, relation_type FROM Feeling_Hypergraph";
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                result.add(new HypergraphEdge(
                        rs.getInt("id"), rs.getInt("source_dim_id"), rs.getInt("target_dim_id"),
                        rs.getDouble("weight"), rs.getString("relation_type")));
            }
        } catch (SQLException e) {
            log.error("[MemoryDB] 获取超图边失败", e);
        }
        return result;
    }

    /**
     * 获取从指定维度出发的所有邻接边（出边）。
     */
    public List<HypergraphEdge> getEdgesFrom(int dimId) {
        List<HypergraphEdge> result = new ArrayList<>();
        String sql = "SELECT id, source_dim_id, target_dim_id, weight, relation_type FROM Feeling_Hypergraph " +
                "WHERE source_dim_id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, dimId);
            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) {
                result.add(new HypergraphEdge(
                        rs.getInt("id"), rs.getInt("source_dim_id"), rs.getInt("target_dim_id"),
                        rs.getDouble("weight"), rs.getString("relation_type")));
            }
        } catch (SQLException e) {
            log.error("[MemoryDB] 获取超图出边失败 dimId={}", dimId, e);
        }
        return result;
    }

    /**
     * 获取与 target 相关的所有入边。
     */
    public List<HypergraphEdge> getEdgesTo(int dimId) {
        List<HypergraphEdge> result = new ArrayList<>();
        String sql = "SELECT id, source_dim_id, target_dim_id, weight, relation_type FROM Feeling_Hypergraph " +
                "WHERE target_dim_id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, dimId);
            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) {
                result.add(new HypergraphEdge(
                        rs.getInt("id"), rs.getInt("source_dim_id"), rs.getInt("target_dim_id"),
                        rs.getDouble("weight"), rs.getString("relation_type")));
            }
        } catch (SQLException e) {
            log.error("[MemoryDB] 获取超图入边失败 dimId={}", dimId, e);
        }
        return result;
    }

    /**
     * 对给定的一组维度 ID，找到在超图中同时连接其中多个维度的中间节点（枢纽）。
     * 返回每个候选节点及其连通的输入维度数量。
     */
    public java.util.Map<Integer, Integer> findHubNodes(List<Integer> dimIds) {
        java.util.Map<Integer, Integer> hubScores = new java.util.LinkedHashMap<>();
        if (dimIds == null || dimIds.size() < 2) return hubScores;

        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < dimIds.size(); i++) {
            if (i > 0) placeholders.append(",");
            placeholders.append("?");
        }

        // 枢纽 = 同时被多个输入维度直接连接的目标节点（入度 ≥ 2）
        String sql = "SELECT target_dim_id AS hub, COUNT(DISTINCT source_dim_id) AS connections " +
                "FROM Feeling_Hypergraph " +
                "WHERE source_dim_id IN (" + placeholders + ") " +
                "AND target_dim_id NOT IN (" + placeholders + ") " +
                "GROUP BY target_dim_id " +
                "HAVING connections >= 2 " +
                "ORDER BY connections DESC";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            for (int i = 0; i < dimIds.size(); i++) {
                pstmt.setInt(i + 1, dimIds.get(i));
            }
            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) {
                hubScores.put(rs.getInt("hub"), rs.getInt("connections"));
            }
        } catch (SQLException e) {
            log.warn("[MemoryDB] 查找超图枢纽失败: {}", e.getMessage());
        }
        return hubScores;
    }

    /**
     * 获取两个维度之间的最大边权重（考虑两个方向）。
     */
    public double getMaxEdgeWeightBetween(int dimIdA, int dimIdB) {
        String sql = "SELECT MAX(weight) FROM Feeling_Hypergraph " +
                "WHERE (source_dim_id = ? AND target_dim_id = ?) " +
                "OR (source_dim_id = ? AND target_dim_id = ?)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, dimIdA);
            pstmt.setInt(2, dimIdB);
            pstmt.setInt(3, dimIdB);
            pstmt.setInt(4, dimIdA);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                double w = rs.getDouble(1);
                return rs.wasNull() ? 0.0 : w;
            }
        } catch (SQLException e) {
            log.warn("[MemoryDB] 查询边权重失败: {}↔{} - {}", dimIdA, dimIdB, e.getMessage());
        }
        return 0.0;
    }

    /**
     * 超图边全局衰减：所有权重乘以 decayFactor。
     * 权重低于 minWeight 的边自动删除。
     * @param decayFactor 衰减因子，0.0~1.0（如 0.95 表示保留 95%）
     * @param minWeight   低于此权重的边被清理
     * @return 删除的边数量
     */
    public int decayHypergraphEdges(double decayFactor, double minWeight) {
        int deleted = 0;
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // 衰减所有权重
                try (PreparedStatement pstmt = conn.prepareStatement(
                        "UPDATE Feeling_Hypergraph SET weight = weight * ?")) {
                    pstmt.setDouble(1, decayFactor);
                    pstmt.executeUpdate();
                }
                // 删除弱边
                try (PreparedStatement pstmt = conn.prepareStatement(
                        "DELETE FROM Feeling_Hypergraph WHERE weight < ?")) {
                    pstmt.setDouble(1, minWeight);
                    deleted = pstmt.executeUpdate();
                }
                conn.commit();
                if (deleted > 0) {
                    log.info("[MemoryDB] 超图衰减: decayFactor={}, 清理 {} 条弱边", decayFactor, deleted);
                }
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            log.warn("[MemoryDB] 超图边衰减失败: {}", e.getMessage());
        }
        return deleted;
    }

    /**
     * 降低指定边的权重（负反馈惩罚）。
     */
    public void weakenHypergraphEdge(int srcId, int tgtId, double penalty) {
        String sql = "UPDATE Feeling_Hypergraph SET weight = MAX(0, weight - ?) " +
                "WHERE source_dim_id = ? AND target_dim_id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setDouble(1, penalty);
            pstmt.setInt(2, srcId);
            pstmt.setInt(3, tgtId);
            int rows = pstmt.executeUpdate();
            if (rows > 0) {
                log.info("[MemoryDB] 边 {}→{} 权重减少 {} (负反馈)", srcId, tgtId, penalty);
            }
        } catch (SQLException e) {
            log.warn("[MemoryDB] 削弱超图边失败: {}→{} - {}", srcId, tgtId, e.getMessage());
        }
    }

    // ============================================================
    // Curiosity_List CRUD
    // ============================================================

    public int insertCuriosityEntry(int sourceDimId, String sourceConcept,
                                     String dissonantDimIdsJson, String dissonantConceptsJson,
                                     String llmQuestion) {
        String sql = "INSERT INTO Curiosity_List (source_dim_id, dissonant_dim_ids, source_concept, " +
                "dissonant_concepts, llm_question, trigger_count, is_active) VALUES (?, ?, ?, ?, ?, 1, 1)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, sourceDimId);
            pstmt.setString(2, dissonantDimIdsJson);
            pstmt.setString(3, sourceConcept);
            pstmt.setString(4, dissonantConceptsJson);
            pstmt.setString(5, llmQuestion != null ? llmQuestion : "");
            pstmt.executeUpdate();
            // SQLite JDBC 不支持 RETURN_GENERATED_KEYS，改用 last_insert_rowid()
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT last_insert_rowid()")) {
                if (rs.next()) return rs.getInt(1);
            }
        } catch (SQLException e) {
            log.error("[MemoryDB] 插入好奇心条目失败", e);
        }
        return -1;
    }

    public List<CuriosityEntry> getActiveCuriosityEntries() {
        List<CuriosityEntry> result = new ArrayList<>();
        String sql = "SELECT id, source_dim_id, dissonant_dim_ids, source_concept, dissonant_concepts, " +
                "trigger_count, is_active, created_at, last_triggered_at, resolved_at, resolution_note, " +
                "llm_question FROM Curiosity_List WHERE is_active = 1 ORDER BY last_triggered_at DESC";
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                result.add(new CuriosityEntry(
                        rs.getInt("id"), rs.getInt("source_dim_id"),
                        rs.getString("dissonant_dim_ids"), rs.getString("source_concept"),
                        rs.getString("dissonant_concepts"), rs.getInt("trigger_count"),
                        rs.getBoolean("is_active"), rs.getString("created_at"),
                        rs.getString("last_triggered_at"), rs.getString("resolved_at"),
                        rs.getString("resolution_note"), rs.getString("llm_question")));
            }
        } catch (SQLException e) {
            log.error("[MemoryDB] 查询活跃好奇心条目失败", e);
        }
        return result;
    }

    public List<CuriosityEntry> getAllCuriosityEntries() {
        List<CuriosityEntry> result = new ArrayList<>();
        String sql = "SELECT id, source_dim_id, dissonant_dim_ids, source_concept, dissonant_concepts, " +
                "trigger_count, is_active, created_at, last_triggered_at, resolved_at, resolution_note, " +
                "llm_question FROM Curiosity_List ORDER BY last_triggered_at DESC";
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                result.add(new CuriosityEntry(
                        rs.getInt("id"), rs.getInt("source_dim_id"),
                        rs.getString("dissonant_dim_ids"), rs.getString("source_concept"),
                        rs.getString("dissonant_concepts"), rs.getInt("trigger_count"),
                        rs.getBoolean("is_active"), rs.getString("created_at"),
                        rs.getString("last_triggered_at"), rs.getString("resolved_at"),
                        rs.getString("resolution_note"), rs.getString("llm_question")));
            }
        } catch (SQLException e) {
            log.error("[MemoryDB] 查询所有好奇心条目失败", e);
        }
        return result;
    }

    public void incrementCuriosityTriggerCount(int entryId) {
        String sql = "UPDATE Curiosity_List SET trigger_count = trigger_count + 1, " +
                "last_triggered_at = CURRENT_TIMESTAMP WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, entryId);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            log.error("[MemoryDB] 更新好奇心触发次数失败 id={}", entryId, e);
        }
    }

    public void deactivateCuriosityEntry(int entryId, String resolutionNote) {
        String sql = "UPDATE Curiosity_List SET is_active = 0, resolved_at = CURRENT_TIMESTAMP, " +
                "resolution_note = ? WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, resolutionNote != null ? resolutionNote : "");
            pstmt.setInt(2, entryId);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            log.error("[MemoryDB] 消解好奇心条目失败 id={}", entryId, e);
        }
    }

    public void updateCuriosityQuestion(int entryId, String question) {
        String sql = "UPDATE Curiosity_List SET llm_question = ?, " +
                "last_triggered_at = CURRENT_TIMESTAMP WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, question != null ? question : "");
            pstmt.setInt(2, entryId);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            log.error("[MemoryDB] 更新好奇心疑问失败 id={}", entryId, e);
        }
    }

    public void deleteCuriosityEntry(int entryId) {
        String sql = "DELETE FROM Curiosity_List WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, entryId);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            log.error("[MemoryDB] 删除好奇心条目失败 id={}", entryId, e);
        }
    }

    public int getActiveCuriosityCount() {
        String sql = "SELECT COUNT(*) FROM Curiosity_List WHERE is_active = 1";
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) return rs.getInt(1);
        } catch (SQLException e) {
            log.error("[MemoryDB] 查询好奇心条目数失败", e);
        }
        return 0;
    }
}