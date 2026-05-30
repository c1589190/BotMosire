package com.cna.apcore.db;

import com.cna.apcore.config.CoreConfig;
import com.cna.apcore.model.ActionPredict;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.extern.slf4j.Slf4j;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * V4 经验数据库。
 *
 * 管理 V4_Experiences 表，提供 CRUD、embedding 去重和相似度检索。
 *
 * 核心存储：
 * - Feelings[]: 可以触发这一条经验的所有感觉维度 ID
 * - ExpTexts[]: 相关经验文本列表（LLM 想法、行动、结果），可回填反馈
 * - HelpfulDegree: -1（没帮助）到 1（有帮助），LLM 填 1/0/-1
 */
@Slf4j
public class ExperiencesDB {

    private static volatile ExperiencesDB INSTANCE;

    public static ExperiencesDB getInstance() {
        if (INSTANCE == null) {
            synchronized (ExperiencesDB.class) {
                if (INSTANCE == null) {
                    INSTANCE = new ExperiencesDB();
                }
            }
        }
        return INSTANCE;
    }

    public static synchronized void init() {
        getInstance();
    }

    private ExperiencesDB() {
        CognitiveDB.initDataSource();
        initTables();
    }

    // ==========================================
    // 表结构
    // ==========================================

    private void initTables() {
        String sql = """
            CREATE TABLE IF NOT EXISTS V4_Experiences (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                feeling_dim_ids TEXT NOT NULL,
                exp_texts TEXT NOT NULL,
                helpful_degree REAL DEFAULT 0.0,
                embedding_json TEXT NOT NULL,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
        """;
        try (Connection conn = CognitiveDB.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
            log.info("[ExperiencesDB] V4_Experiences 表已就绪");
        } catch (SQLException e) {
            log.error("[ExperiencesDB] 初始化 V4_Experiences 表失败", e);
        }

        try (Connection conn = CognitiveDB.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_v4_exp_helpful ON V4_Experiences(helpful_degree)");
        } catch (SQLException e) {
            log.warn("[ExperiencesDB] 创建索引失败: {}", e.getMessage());
        }
    }

    // ==========================================
    // 数据模型
    // ==========================================

    public static class ExperienceEntry {
        public final int id;
        public final List<Integer> feelingDimIds;
        public final List<String> expTexts;
        public final double helpfulDegree;
        public final double[] embedding;

        public ExperienceEntry(int id, List<Integer> feelingDimIds, List<String> expTexts,
                               double helpfulDegree, double[] embedding) {
            this.id = id;
            this.feelingDimIds = feelingDimIds != null ? feelingDimIds : List.of();
            this.expTexts = expTexts != null ? expTexts : List.of();
            this.helpfulDegree = helpfulDegree;
            this.embedding = embedding;
        }
    }

    // ==========================================
    // CRUD
    // ==========================================

    /**
     * 插入经验（先做 embedding 去重）。
     *
     * @param feelingDimIds 关联的感觉维度 ID 列表
     * @param expTexts      经验文本列表（LLM 想法 + 行动 + 结果）
     * @param embedding     拼接文本的 embedding
     * @return 新插入的 ID，如果重复则返回已有 ID
     */
    public int insertExperience(List<Integer> feelingDimIds, List<String> expTexts, double[] embedding) {
        // 先去重
        int duplicateId = findDuplicate(embedding);
        if (duplicateId > 0) {
            log.debug("[ExperiencesDB] 新经验与已有 id={} 高度相似，跳过插入", duplicateId);
            // 可选：丰富已有经验的 expTexts
            enrichExpTexts(duplicateId, expTexts);
            return duplicateId;
        }

        String sql = "INSERT INTO V4_Experiences (feeling_dim_ids, exp_texts, helpful_degree, embedding_json) VALUES (?, ?, 0.0, ?)";
        try (Connection conn = CognitiveDB.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, CognitiveDB.getMapper().writeValueAsString(feelingDimIds));
            pstmt.setString(2, CognitiveDB.getMapper().writeValueAsString(expTexts));
            pstmt.setString(3, CognitiveDB.getMapper().writeValueAsString(embedding));
            pstmt.executeUpdate();

            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT last_insert_rowid()")) {
                if (rs.next()) {
                    int id = rs.getInt(1);
                    log.info("[ExperiencesDB] 新增经验 id={}, feelings={}, texts={}",
                            id, feelingDimIds != null ? feelingDimIds.size() : 0,
                            expTexts != null ? expTexts.size() : 0);
                    return id;
                }
            }
        } catch (SQLException | JsonProcessingException e) {
            log.error("[ExperiencesDB] 插入经验失败", e);
        }
        return -1;
    }

    /** 向已有经验追加新的 expText（去重追加） */
    private void enrichExpTexts(int id, List<String> newTexts) {
        if (newTexts == null || newTexts.isEmpty()) return;

        ExperienceEntry existing = getById(id);
        if (existing == null) return;

        List<String> merged = new ArrayList<>(existing.expTexts);
        boolean changed = false;
        for (String t : newTexts) {
            if (t != null && !t.isBlank() && !merged.contains(t)) {
                merged.add(t);
                changed = true;
            }
        }
        if (!changed) return;

        String sql = "UPDATE V4_Experiences SET exp_texts = ? WHERE id = ?";
        try (Connection conn = CognitiveDB.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, CognitiveDB.getMapper().writeValueAsString(merged));
            pstmt.setInt(2, id);
            pstmt.executeUpdate();
            log.debug("[ExperiencesDB] id={} expTexts 已丰富: {} -> {} 条", id, existing.expTexts.size(), merged.size());
        } catch (SQLException | JsonProcessingException e) {
            log.error("[ExperiencesDB] 丰富经验文本失败 id={}", id, e);
        }
    }

    /** 按 ID 获取 */
    public ExperienceEntry getById(int id) {
        String sql = "SELECT id, feeling_dim_ids, exp_texts, helpful_degree, embedding_json FROM V4_Experiences WHERE id = ?";
        try (Connection conn = CognitiveDB.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, id);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return parseEntry(rs);
                }
            }
        } catch (SQLException e) {
            log.error("[ExperiencesDB] 获取经验 id={} 失败", id, e);
        }
        return null;
    }

    /**
     * 按 embedding 相似度检索 top-N 经验。
     *
     * @param queryVector 查询文本的 embedding
     * @param n           返回数量
     * @return 按相似度降序排列的经验列表
     */
    public List<ExperienceEntry> queryByEmbedding(double[] queryVector, int n) {
        List<ExperienceEntry> all = getAll();
        if (all.isEmpty()) return List.of();

        // 计算所有相似度并排序
        record ScoredEntry(ExperienceEntry entry, double similarity) {}
        List<ScoredEntry> scored = new ArrayList<>();
        for (ExperienceEntry e : all) {
            double sim = CognitiveDB.cosineSimilarity(queryVector, e.embedding);
            if (sim > 0.1) { // 最低门限
                scored.add(new ScoredEntry(e, sim));
            }
        }
        scored.sort((a, b) -> Double.compare(b.similarity, a.similarity));

        int limit = Math.min(n, scored.size());
        List<ExperienceEntry> result = new ArrayList<>();
        for (int i = 0; i < limit; i++) {
            result.add(scored.get(i).entry);
        }
        return result;
    }

    /**
     * 按感觉维度 ID 查询 top-N 经验（按相似度排序）。
     * 用于根据 UEUnit 的 top-N 维度去检索相关经验。
     */
    public List<ActionPredict> queryByFeelings(List<Integer> dimIds, double[] queryVector, int n) {
        if (dimIds == null || dimIds.isEmpty()) return List.of();

        List<ExperienceEntry> all = getAll();
        if (all.isEmpty()) return List.of();

        // 将 dimIds 转为 Set 以加速查找
        java.util.Set<Integer> dimSet = new java.util.HashSet<>(dimIds);

        record ScoredPredict(ActionPredict predict, double score) {}
        List<ScoredPredict> scored = new ArrayList<>();

        for (ExperienceEntry e : all) {
            // 检查该经验是否与指定的感觉维度有关联
            boolean related = false;
            for (int fid : e.feelingDimIds) {
                if (dimSet.contains(fid)) {
                    related = true;
                    break;
                }
            }
            if (!related) continue;

            double sim = CognitiveDB.cosineSimilarity(queryVector, e.embedding);
            if (sim < 0.1) continue;

            // 综合得分 = 相似度 × (1 + helpfulDegree) 偏向有用经验
            double score = sim * (1.0 + e.helpfulDegree * 0.5);

            String expText = String.join(" | ", e.expTexts);
            ActionPredict predict = ActionPredict.builder()
                    .experienceId(e.id)
                    .expText(expText)
                    .similarity(sim)
                    .helpfulDegree(e.helpfulDegree)
                    .feelingDimIds(e.feelingDimIds)
                    .build();
            scored.add(new ScoredPredict(predict, score));
        }

        scored.sort((a, b) -> Double.compare(b.score, a.score));

        int limit = Math.min(n, scored.size());
        List<ActionPredict> result = new ArrayList<>();
        for (int i = 0; i < limit; i++) {
            result.add(scored.get(i).predict);
        }
        return result;
    }

    /** 更新 HelpfulDegree */
    public void updateHelpfulDegree(int id, double score) {
        double clamped = Math.max(-1.0, Math.min(1.0, score));
        String sql = "UPDATE V4_Experiences SET helpful_degree = ? WHERE id = ?";
        try (Connection conn = CognitiveDB.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setDouble(1, clamped);
            pstmt.setInt(2, id);
            pstmt.executeUpdate();
            log.info("[ExperiencesDB] id={} HelpfulDegree 更新为 {}", id, clamped);
        } catch (SQLException e) {
            log.error("[ExperiencesDB] 更新 HelpfulDegree 失败 id={}", id, e);
        }
    }

    /** 获取全量经验 */
    public List<ExperienceEntry> getAll() {
        List<ExperienceEntry> result = new ArrayList<>();
        String sql = "SELECT id, feeling_dim_ids, exp_texts, helpful_degree, embedding_json FROM V4_Experiences";
        try (Connection conn = CognitiveDB.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                try {
                    result.add(parseEntry(rs));
                } catch (Exception e) {
                    log.warn("[ExperiencesDB] 解析 id={} 的经验失败，跳过", rs.getInt("id"));
                }
            }
        } catch (SQLException e) {
            log.error("[ExperiencesDB] 获取全量经验失败", e);
        }
        return result;
    }

    // ==========================================
    // 内部方法
    // ==========================================

    private ExperienceEntry parseEntry(ResultSet rs) throws SQLException {
        try {
            List<Integer> dimIds = CognitiveDB.getMapper().readValue(
                    rs.getString("feeling_dim_ids"), new TypeReference<List<Integer>>() {});
            List<String> texts = CognitiveDB.getMapper().readValue(
                    rs.getString("exp_texts"), new TypeReference<List<String>>() {});
            double[] emb = CognitiveDB.getMapper().readValue(
                    rs.getString("embedding_json"), double[].class);
            return new ExperienceEntry(
                    rs.getInt("id"), dimIds, texts,
                    rs.getDouble("helpful_degree"), emb);
        } catch (JsonProcessingException e) {
            throw new SQLException("JSON 解析失败", e);
        }
    }

    /** embedding 去重检查 */
    private int findDuplicate(double[] embedding) {
        double threshold = CoreConfig.DEDUP_THRESHOLD;
        List<ExperienceEntry> all = getAll();
        double bestSim = 0;
        int bestId = -1;
        for (ExperienceEntry e : all) {
            double sim = CognitiveDB.cosineSimilarity(embedding, e.embedding);
            if (sim > threshold && sim > bestSim) {
                bestSim = sim;
                bestId = e.id;
            }
        }
        return bestId;
    }
}
