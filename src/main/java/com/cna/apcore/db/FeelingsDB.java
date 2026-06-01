package com.cna.apcore.db;

import com.cna.apcore.config.CoreConfig;
import com.cna.apcore.model.FeelingEntry;
import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.extern.slf4j.Slf4j;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * V4 感觉维度数据库。
 *
 * 管理 V4_Feelings 表，提供 CRUD、语义去重、准确度传播，以及 novelty 曲线计算。
 *
 * 实际权重公式：actualWeight = noveltyCurve(activationCount) × accuracy
 * - noveltyCurve: 新颖→高，厌倦→低，永熟悉→中等
 * - accuracy: 感觉对应的经验与新实践的匹配程度
 */
@Slf4j
public class FeelingsDB {

    private static volatile FeelingsDB INSTANCE;

    public static FeelingsDB getInstance() {
        if (INSTANCE == null) {
            synchronized (FeelingsDB.class) {
                if (INSTANCE == null) {
                    INSTANCE = new FeelingsDB();
                }
            }
        }
        return INSTANCE;
    }

    /** 显式初始化（确保表已建） */
    public static synchronized void init() {
        getInstance();
    }

    private FeelingsDB() {
        CognitiveDB.initDataSource();
        initTables();
    }

    // ==========================================
    // 表结构
    // ==========================================

    private void initTables() {
        String sql = """
            CREATE TABLE IF NOT EXISTS V4_Feelings (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                concept TEXT NOT NULL,
                embedding_json TEXT NOT NULL,
                activation_count INTEGER DEFAULT 0,
                accuracy REAL DEFAULT 0.5,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
        """;
        try (Connection conn = CognitiveDB.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
            log.info("[FeelingsDB] V4_Feelings 表已就绪");
        } catch (SQLException e) {
            log.error("[FeelingsDB] 初始化 V4_Feelings 表失败", e);
        }

        // 确保索引
        try (Connection conn = CognitiveDB.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_v4_feel_concept ON V4_Feelings(concept)");
        } catch (SQLException e) {
            log.warn("[FeelingsDB] 创建索引失败: {}", e.getMessage());
        }

        // ★ 迁移：注意力态度列
        CognitiveDB.ensureColumn("V4_Feelings", "attention_attitude", "REAL DEFAULT 0.0");
    }

    // ==========================================
    // CRUD
    // ==========================================

    /**
     * 插入新感觉维度（先做 concept 名去重，再做 embedding 去重）。
     *
     * @return 新插入的 ID，如果与已有感觉高度相似或同名则返回已有 ID
     */
    public int insertFeeling(String concept, double[] embedding) {
        // 1. 先按 concept 名称去重（避免 UNIQUE 约束冲突）
        int existingId = findByConcept(concept);
        if (existingId > 0) {
            log.debug("[FeelingsDB] 感觉 '{}' 已存在 (同名 id={})，跳过插入", concept, existingId);
            return existingId;
        }

        // 2. 再按 embedding 语义去重
        int duplicateId = findDuplicate(embedding);
        if (duplicateId > 0) {
            log.debug("[FeelingsDB] 感觉 '{}' 与已有 id={} 高度相似，跳过插入", concept, duplicateId);
            return duplicateId;
        }

        String sql = "INSERT INTO V4_Feelings (concept, embedding_json, activation_count, accuracy) VALUES (?, ?, 1, 0.5)";
        try (Connection conn = CognitiveDB.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, concept);
            pstmt.setString(2, CognitiveDB.getMapper().writeValueAsString(embedding));
            pstmt.executeUpdate();

            // 获取自增 ID
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT last_insert_rowid()")) {
                if (rs.next()) {
                    int id = rs.getInt(1);
                    log.info("[FeelingsDB] 新增感觉维度: '{}' id={}", concept, id);
                    return id;
                }
            }
        } catch (SQLException | JsonProcessingException e) {
            // 3. 兜底：如果发生 UNIQUE 冲突（并发竞争），回退查询已有 ID
            if (e instanceof SQLException se && se.getMessage().contains("UNIQUE constraint failed")) {
                log.debug("[FeelingsDB] 并发冲突，回退查询 concept='{}'", concept);
                int fallbackId = findByConcept(concept);
                if (fallbackId > 0) return fallbackId;
            }
            log.error("[FeelingsDB] 插入感觉维度失败: {}", concept, e);
        }
        return -1;
    }

    /** 获取全量感觉维度 */
    public List<FeelingEntry> getAll() {
        List<FeelingEntry> result = new ArrayList<>();
        String sql = "SELECT id, concept, embedding_json, activation_count, accuracy, attention_attitude FROM V4_Feelings";
        try (Connection conn = CognitiveDB.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                try {
                    double[] emb = CognitiveDB.getMapper().readValue(rs.getString("embedding_json"), double[].class);
                    result.add(FeelingEntry.builder()
                            .id(rs.getInt("id"))
                            .concept(rs.getString("concept"))
                            .embedding(emb)
                            .activationCount(rs.getInt("activation_count"))
                            .accuracy(rs.getDouble("accuracy"))
                            .attentionAttitude(rs.getDouble("attention_attitude"))
                            .build());
                } catch (Exception e) {
                    log.warn("[FeelingsDB] 解析 id={} 的向量失败，跳过", rs.getInt("id"));
                }
            }
        } catch (SQLException e) {
            log.error("[FeelingsDB] 获取全量感觉维度失败", e);
        }
        return result;
    }

    /** 按 ID 获取 */
    public FeelingEntry getById(int id) {
        String sql = "SELECT id, concept, embedding_json, activation_count, accuracy, attention_attitude FROM V4_Feelings WHERE id = ?";
        try (Connection conn = CognitiveDB.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, id);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    double[] emb = CognitiveDB.getMapper().readValue(rs.getString("embedding_json"), double[].class);
                    return FeelingEntry.builder()
                            .id(rs.getInt("id"))
                            .concept(rs.getString("concept"))
                            .embedding(emb)
                            .activationCount(rs.getInt("activation_count"))
                            .accuracy(rs.getDouble("accuracy"))
                            .attentionAttitude(rs.getDouble("attention_attitude"))
                            .build();
                }
            }
        } catch (SQLException | JsonProcessingException e) {
            log.error("[FeelingsDB] 获取感觉维度 id={} 失败", id, e);
        }
        return null;
    }

    /** 激活次数 +1 */
    public void incrementActivation(int id) {
        String sql = "UPDATE V4_Feelings SET activation_count = activation_count + 1 WHERE id = ?";
        try (Connection conn = CognitiveDB.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, id);
            pstmt.executeUpdate();
            log.debug("[FeelingsDB] id={} activation_count +1", id);
        } catch (SQLException e) {
            log.error("[FeelingsDB] 增加激活次数失败 id={}", id, e);
        }
    }

    /**
     * 准确度传播。
     *
     * 当 LLM 对某个经验打分后，该经验关联的所有感觉维度的 accuracy
     * 都按 delta = score / feelings.size 进行传播调整。
     * accuracy 范围限制在 [0, 1]。
     *
     * @param id    感觉维度 ID
     * @param delta 调整量（正值提高准确度，负值降低）
     */
    public void propagateAccuracy(int id, double delta) {
        FeelingEntry entry = getById(id);
        if (entry == null) return;

        double newAccuracy = Math.max(0.0, Math.min(1.0, entry.getAccuracy() + delta));

        String sql = "UPDATE V4_Feelings SET accuracy = ? WHERE id = ?";
        try (Connection conn = CognitiveDB.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setDouble(1, newAccuracy);
            pstmt.setInt(2, id);
            pstmt.executeUpdate();
            log.debug("[FeelingsDB] id=" + id + " accuracy: " + String.format("%.3f", entry.getAccuracy())
                    + " -> " + String.format("%.3f", newAccuracy) + " (delta=" + String.format("%+.3f", delta) + ")");
        } catch (SQLException e) {
            log.error("[FeelingsDB] 传播准确度失败 id={}", id, e);
        }
    }

    /**
     * Novelly 曲线：
     * - activationCount <= 1: 最新鲜，权重 = 1.0
     * - activationCount >= HABITUATION_LIMIT: 已经非常熟悉，权重 = 1/HABITUATION_LIMIT
     * - 中间：线性下降从 1.0 到 1/HABITUATION_LIMIT
     *
     * 注意：曲线走势是 高→低→中（永不归零），体现"新颖→厌倦→永熟悉"。
     * 熟悉之后虽然不再新鲜，但因为经过了大量验证，仍保有一定的可靠性权重。
     */
    public static double noveltyCurve(int activationCount) {
        int limit = CoreConfig.HABITUATION_LIMIT;
        if (activationCount <= 1) {
            return 1.0;
        }
        if (activationCount >= limit) {
            // "永熟悉"状态，保持最低但非零的权重
            return 1.0 / limit;
        }
        // 线性衰减
        double progress = (double) (activationCount - 1) / (limit - 1);
        return 1.0 - progress * (1.0 - 1.0 / limit);
    }

    // ==========================================
    // 注意力态度 CRUD
    // ==========================================

    /**
     * 获取感觉维度的注意力态度。
     * @return 态度值 [-1.0, 1.0]，不存在时返回 0.0
     */
    public double getAttentionAttitude(int dimId) {
        String sql = "SELECT attention_attitude FROM V4_Feelings WHERE id = ?";
        try (Connection conn = CognitiveDB.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, dimId);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getDouble("attention_attitude");
                }
            }
        } catch (SQLException e) {
            log.warn("[FeelingsDB] 获取注意力态度失败 dimId={}", dimId, e);
        }
        return 0.0;
    }

    /**
     * 累加调节注意力态度（自动 clamp 到 [ATTITUDE_MIN, ATTITUDE_MAX]）。
     * 正值 = 该感觉维度更值得关注，负值 = 更不值得关注。
     */
    public void adjustAttentionAttitude(int dimId, double delta) {
        if (delta == 0.0) return;
        double current = getAttentionAttitude(dimId);
        double clamped = Math.max(CoreConfig.ATTENTION_ATTITUDE_MIN,
                           Math.min(CoreConfig.ATTENTION_ATTITUDE_MAX, current + delta));
        if (Math.abs(clamped - current) < 0.0001) return; // 未变化

        String sql = "UPDATE V4_Feelings SET attention_attitude = ? WHERE id = ?";
        try (Connection conn = CognitiveDB.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setDouble(1, clamped);
            pstmt.setInt(2, dimId);
            pstmt.executeUpdate();
            if (Math.abs(delta) > 0.005) {
                log.debug("[FeelingsDB] dimId={} attention_attitude: {:.3f} -> {:.3f} (delta={:+.3f})",
                        dimId, current, clamped, delta);
            }
        } catch (SQLException e) {
            log.error("[FeelingsDB] 调节注意力态度失败 dimId={}", dimId, e);
        }
    }

    /**
     * 对全量感觉维度做注意力态度自然衰减。
     * 每 tick 调用一次，不活跃的维度缓慢回归中性。
     */
    public void decayAttentionAttitudes(double decayRate) {
        if (decayRate <= 0.0) return;
        String sql = "UPDATE V4_Feelings SET attention_attitude = attention_attitude * (1.0 - ?)";
        try (Connection conn = CognitiveDB.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setDouble(1, decayRate);
            int updated = pstmt.executeUpdate();
            if (updated > 0) {
                log.debug("[FeelingsDB] 注意力态度衰减: {} 个维度, rate={}", updated, decayRate);
            }
        } catch (SQLException e) {
            log.warn("[FeelingsDB] 注意力态度衰减失败", e);
        }
    }

    // ==========================================
    // 语义去重
    // ==========================================

    /**
     * 按 concept 名称精确查找。
     * @return 已有记录 ID，-1 表示不存在
     */
    private int findByConcept(String concept) {
        String sql = "SELECT id FROM V4_Feelings WHERE concept = ?";
        try (Connection conn = CognitiveDB.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, concept);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("id");
                }
            }
        } catch (SQLException e) {
            log.warn("[FeelingsDB] 按概念查询失败: {}", concept, e);
        }
        return -1;
    }

    /**
     * 在已有感觉中搜索与给定向量余弦相似度 > DEDUP_THRESHOLD 的记录。
     * @return 重复记录 ID，-1 表示无重复
     */
    private int findDuplicate(double[] embedding) {
        double threshold = CoreConfig.DEDUP_THRESHOLD;
        List<FeelingEntry> all = getAll();
        double bestSim = 0;
        int bestId = -1;
        for (FeelingEntry f : all) {
            double sim = CognitiveDB.cosineSimilarity(embedding, f.getEmbedding());
            if (sim > threshold && sim > bestSim) {
                bestSim = sim;
                bestId = f.getId();
            }
        }
        return bestId;
    }
}
