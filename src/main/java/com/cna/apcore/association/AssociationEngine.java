package com.cna.apcore.association;

import com.cna.apcore.db.CognitiveDB;
import com.cna.apcore.db.ExperiencesDB;
import com.cna.apcore.db.FeelingsDB;
import com.cna.apcore.model.ActionPredict;
import com.cna.apcore.model.CognitiveAction;
import com.cna.apcore.model.FeelingEntry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;

import java.sql.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 联想引擎 — 利用感觉维度超图从经验中抽象出预期和方法论。
 *
 * <h3>核心思想</h3>
 * 每一条经验都关联了一组感觉维度（feeling_dim_ids）。当一次 action 触发多条经验时，
 * 这些经验的感觉维度的并集/差集天然形成"联想"——揭示"这类场景通常还会涉及什么"。
 * 不需要另起炉灶建 PatternDB，已有的感觉维度图就是经验的索引系统。
 *
 * <h3>预期 (Expectation)</h3>
 * 纯感觉维度集合运算（每 tick，不需要 LLM）：
 * <ol>
 *   <li>收集所有触发经验的感觉维度 → allDims</li>
 *   <li>高频维度（≥50%经验共有）= 核心条件</li>
 *   <li>低频但被部分经验携带的维度 = 联想/预期</li>
 *   <li>按 helpful_degree 加权判断预期的正面/负面方向</li>
 * </ol>
 *
 * <h3>方法论 (Methodology)</h3>
 * 三层分工：
 * <ol>
 *   <li><b>DB 存储</b> — 方法条目持久化到 V4_Methods，成功率由经验打分自动维护</li>
 *   <li><b>周期性挖掘</b> — 累积足够新经验后，从高 helpful 经验中提取方法片段</li>
 *   <li><b>每 tick 检索</b> — 按当前 action 的感觉维度查询匹配的方法论</li>
 * </ol>
 *
 * <h3>打分闭环</h3>
 * LLM 打分 → 经验 helpful_degree 变化 → 关联方法的 success_rate 自动更新
 *
 * @see CognitiveAction
 * @see ExperiencesDB
 * @see FeelingsDB
 */
@Slf4j
public class AssociationEngine {

    private static volatile AssociationEngine INSTANCE;

    // ── 方法挖掘阈值 ──
    private int newExperienceCount = 0;
    private static final int MINE_TRIGGER_COUNT = 10;
    private static final int MIN_METHOD_EXPERIENCES = 3;
    private static final double MIN_METHOD_HELPFUL = 0.3;

    // ── 预期生成阈值 ──
    /** 出现在此比例以上的维度视为"核心条件"而非"联想" */
    private static final double CORE_DIM_RATIO = 0.5;
    /** 最多展示的预期条数 */
    private static final int MAX_EXPECTATIONS = 5;
    /** 最多展示的方法论条数 */
    private static final int MAX_METHODS_IN_PROMPT = 5;

    private AssociationEngine() {
        initTables();
        log.info("[Association] 🧠 联想引擎初始化完成");
    }

    public static AssociationEngine getInstance() {
        if (INSTANCE == null) {
            synchronized (AssociationEngine.class) {
                if (INSTANCE == null) {
                    INSTANCE = new AssociationEngine();
                }
            }
        }
        return INSTANCE;
    }

    // ==========================================
    // 表结构
    // ==========================================

    private void initTables() {
        String sql = """
            CREATE TABLE IF NOT EXISTS V4_Methods (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                condition_dim_ids TEXT NOT NULL,
                condition_summary TEXT NOT NULL,
                method_text TEXT NOT NULL,
                result_dim_ids TEXT DEFAULT '[]',
                success_rate REAL DEFAULT 0.5,
                total_evaluations INTEGER DEFAULT 0,
                source_exp_ids TEXT DEFAULT '[]',
                embedding_json TEXT NOT NULL,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
        """;
        try (Connection conn = CognitiveDB.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
            log.info("[Association] V4_Methods 表已就绪");
        } catch (SQLException e) {
            log.error("[Association] 初始化 V4_Methods 表失败", e);
        }

        try (Connection conn = CognitiveDB.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_v4_methods_success ON V4_Methods(success_rate)");
        } catch (SQLException e) {
            log.warn("[Association] 创建索引失败: {}", e.getMessage());
        }
    }

    // ==========================================
    // 1. 预期生成 — 感觉维度集合运算（纯图算法，不需要 LLM）
    // ==========================================

    /**
     * 从当前 action 触发的多条经验的 feeling_dim_ids 中，
     * 通过集合运算找出"联想维度"——核心条件之外但被部分经验携带的维度，
     * 作为对 LLM 的预期提示。
     *
     * <p>算法：
     * <ol>
     *   <li>统计所有触发经验的 feeling_dim_ids 频率和 helpful_degree</li>
     *   <li>频率 ≥ CORE_DIM_RATIO 的维度 = 核心条件</li>
     *   <li>其余维度（且不在 action 自身 UE 中）= 联想维度</li>
     *   <li>按 avgHelpful 排序，正面预期在前，负面警告在后</li>
     * </ol>
     *
     * @param action     当前认知动作（含 actionPredicts 和 UE 维度）
     * @param feelingsDB 感觉数据库（查找维度概念名）
     * @return 格式化的预期文本，无预期时返回空字符串
     */
    public String computeExpectations(CognitiveAction action, FeelingsDB feelingsDB) {
        List<ActionPredict> predicts = action.getActionPredicts();
        if (predicts == null || predicts.isEmpty()) {
            return "";
        }

        // 1. 收集所有经验的 feeling_dim_ids，统计频率和 helpful
        Map<Integer, DimAccumulator> dimStats = new LinkedHashMap<>();
        for (ActionPredict p : predicts) {
            List<Integer> dims = p.getFeelingDimIds();
            if (dims == null || dims.isEmpty()) continue;
            for (int dimId : dims) {
                dimStats.computeIfAbsent(dimId, k -> new DimAccumulator())
                        .add(p.getHelpfulDegree());
            }
        }

        if (dimStats.isEmpty()) return "";

        // 2. 获取 action 自身的 UE 维度
        Set<Integer> actionDims = new HashSet<>(action.getUEDimIds());

        // 3. 分类维度
        int coreThreshold = Math.max(1, (int) Math.ceil(predicts.size() * CORE_DIM_RATIO));
        Set<Integer> coreDims = new LinkedHashSet<>();
        List<Map.Entry<Integer, DimAccumulator>> fringeEntries = new ArrayList<>();

        for (Map.Entry<Integer, DimAccumulator> e : dimStats.entrySet()) {
            if (e.getValue().frequency >= coreThreshold) {
                coreDims.add(e.getKey());
            } else if (!actionDims.contains(e.getKey())) {
                // 联想维度 = 低频 + 不在 action 自身 UE 中
                fringeEntries.add(e);
            }
        }

        // 如果没有联想维度，从核心维度形成"确认性预期"
        if (fringeEntries.isEmpty()) {
            if (!coreDims.isEmpty()) {
                return buildConfirmatoryExpectation(coreDims, feelingsDB);
            }
            return "";
        }

        // 4. 按 avgHelpful 排序：正面预期在前
        fringeEntries.sort((a, b) -> Double.compare(
                b.getValue().avgHelpful(), a.getValue().avgHelpful()));

        // 5. 生成预期文本
        StringBuilder sb = new StringBuilder();
        sb.append("【预期 — 从关联经验的感觉联想】\n");

        // 核心条件
        if (!coreDims.isEmpty()) {
            List<String> coreConcepts = lookupConcepts(coreDims, feelingsDB);
            if (!coreConcepts.isEmpty()) {
                sb.append("  核心条件: ").append(String.join("、", coreConcepts)).append("\n");
            }
        }

        int count = 0;
        for (Map.Entry<Integer, DimAccumulator> e : fringeEntries) {
            if (count >= MAX_EXPECTATIONS) break;
            int dimId = e.getKey();
            DimAccumulator acc = e.getValue();
            String concept = lookupConcept(dimId, feelingsDB);
            double avgH = acc.avgHelpful();

            if (avgH > 0.3) {
                sb.append(String.format("  ✅ 可能涉及【%s】— 相关经验中正面反馈 %.0f%%，建议主动关注\n",
                        concept, avgH * 100));
            } else if (avgH < -0.1) {
                sb.append(String.format("  ⚠️ 可能涉及【%s】— 相关经验反馈偏负面(%.0f%%)，建议调整策略\n",
                        concept, -avgH * 100));
            } else {
                sb.append(String.format("  💡 可能涉及【%s】— 部分经验关联此方向，验证尚不充分\n",
                        concept));
            }
            count++;
        }

        return sb.toString();
    }

    /** 当没有联想维度时，从核心维度形成确认性预期 */
    private String buildConfirmatoryExpectation(Set<Integer> coreDims, FeelingsDB feelingsDB) {
        List<String> concepts = lookupConcepts(coreDims, feelingsDB);
        if (concepts.isEmpty()) return "";

        return "【预期】当前场景与以下核心感觉维度强相关: "
                + String.join("、", concepts)
                + "。此类场景的经验验证较充分，可依赖先验判断。\n";
    }

    // ==========================================
    // 2. 方法论检索 — DB 查询（纯检索，不需要 LLM）
    // ==========================================

    /**
     * 查询与当前 action 的感觉维度匹配的方法论条目。
     * 按"感觉维度重叠度 × 成功率"排序。
     *
     * @param action     当前认知动作
     * @param feelingsDB 感觉数据库
     * @return 格式化的方法论文本，无匹配时返回空字符串
     */
    public String queryMethods(CognitiveAction action, FeelingsDB feelingsDB) {
        List<Integer> actionDims = action.getUEDimIds();
        if (actionDims.isEmpty()) return "";

        List<MethodEntry> allMethods = getAllMethods();
        if (allMethods.isEmpty()) return "";

        Set<Integer> actionDimSet = new HashSet<>(actionDims);

        // 计算每个方法的匹配度
        record ScoredMethod(MethodEntry method, double overlapRatio) {}
        List<ScoredMethod> scored = new ArrayList<>();

        for (MethodEntry m : allMethods) {
            List<Integer> condDims = m.conditionDimIds;
            if (condDims.isEmpty()) continue;

            int overlap = 0;
            for (int cd : condDims) {
                if (actionDimSet.contains(cd)) overlap++;
            }
            double overlapRatio = (double) overlap / condDims.size();

            if (overlapRatio > 0) {
                scored.add(new ScoredMethod(m, overlapRatio));
            }
        }

        if (scored.isEmpty()) return "";

        // 按 overlapRatio × successRate 排序
        scored.sort((a, b) -> Double.compare(
                b.overlapRatio * b.method.successRate,
                a.overlapRatio * a.method.successRate));

        int limit = Math.min(MAX_METHODS_IN_PROMPT, scored.size());

        StringBuilder sb = new StringBuilder();
        sb.append("【方法论 — 此场景下验证过的有效做法】\n");

        for (int i = 0; i < limit; i++) {
            MethodEntry m = scored.get(i).method;
            double overlapRatio = scored.get(i).overlapRatio;

            // 查找条件维度的概念名
            List<String> condConcepts = lookupConcepts(
                    new LinkedHashSet<>(m.conditionDimIds), feelingsDB);

            String matchTag = overlapRatio >= 0.8 ? "高度匹配"
                    : overlapRatio >= 0.5 ? "中度匹配" : "低度匹配";

            sb.append(String.format("  [方法%d] (%s, 成功率: %.0f%%, %d次验证)\n",
                    i + 1, matchTag, m.successRate * 100, m.totalEvaluations));
            sb.append(String.format("    条件: %s\n",
                    condConcepts.isEmpty() ? "通用" : String.join("、", condConcepts)));
            sb.append(String.format("    做法: %s\n", m.methodText));
        }

        return sb.toString();
    }

    // ==========================================
    // 3. 方法挖掘 — 周期性自动抽象
    // ==========================================

    private volatile boolean pendingMine = false;

    /** 标记有新经验被存储。累积到阈值后设置 pendingMine 标志。 */
    public void markNewExperience() {
        newExperienceCount++;
        if (newExperienceCount >= MINE_TRIGGER_COUNT) {
            log.info("[Association] 🔍 累积 {} 条新经验，标记待挖掘", newExperienceCount);
            newExperienceCount = 0;
            pendingMine = true;
        }
    }

    /** 是否有待处理的方法挖掘任务 */
    public boolean hasPendingMine() {
        return pendingMine;
    }

    /**
     * 执行方法挖掘。
     * 由 ActionLoop.onTick 在空闲（池较小）时调用，避免与 LLM action 处理竞争。
     *
     * @param experiencesDB 经验数据库
     * @param feelingsDB    感觉数据库
     * @param embedder      文本→embedding 函数
     * @return 新挖掘的方法数量
     */
    public int mineMethods(ExperiencesDB experiencesDB, FeelingsDB feelingsDB,
                           Function<String, double[]> embedder) {
        pendingMine = false;

        List<ExperiencesDB.ExperienceEntry> allExps = experiencesDB.getAll();
        if (allExps.isEmpty()) {
            log.debug("[Association] 经验库为空，跳过方法挖掘");
            return 0;
        }

        // 1. 按感觉维度粗粒度聚类
        Map<String, List<ExperiencesDB.ExperienceEntry>> clusters = clusterByCoarseDims(allExps);

        int mined = 0;
        for (Map.Entry<String, List<ExperiencesDB.ExperienceEntry>> cluster : clusters.entrySet()) {
            List<ExperiencesDB.ExperienceEntry> exps = cluster.getValue();

            // 过滤条件：至少 MIN_METHOD_EXPERIENCES 条，avg helpful > threshold
            if (exps.size() < MIN_METHOD_EXPERIENCES) continue;

            double avgHelpful = exps.stream()
                    .mapToDouble(e -> e.helpfulDegree)
                    .average().orElse(0.0);
            if (avgHelpful < MIN_METHOD_HELPFUL) continue;

            // 去重：检查是否已有覆盖此聚类的方法
            List<Integer> clusterDimIds = parseDimIds(cluster.getKey());
            if (hasOverlappingMethod(clusterDimIds, 0.7)) continue;

            // 2. 从高 helpful 经验中提取方法片段
            String methodText = extractMethodFromExperiences(exps);

            if (methodText != null && !methodText.isBlank()) {
                double[] emb = embedder.apply(methodText);
                if (emb != null) {
                    insertMethod(clusterDimIds, methodText, exps, avgHelpful, emb);
                    mined++;
                    log.info("[Association] 🎯 挖掘到新方法: 条件dims={}, 来源经验={}条, 初始成功率={:.0f}%",
                            cluster.getKey(), exps.size(), avgHelpful * 100);
                }
            }
        }

        if (mined > 0) {
            log.info("[Association] ✅ 本轮方法挖掘: {} 个新方法入库", mined);
        } else {
            log.debug("[Association] 本轮无新方法产出（经验尚不足以形成稳定方法论）");
        }
        return mined;
    }

    /**
     * 从经验文本中提取方法描述。
     * 当前实现：提取高 helpful 经验的 "想法:" 片段并拼接。
     * 未来可升级为 LLM 调用做语义融合抽象。
     */
    private String extractMethodFromExperiences(List<ExperiencesDB.ExperienceEntry> exps) {
        List<ExperiencesDB.ExperienceEntry> sorted = new ArrayList<>(exps);
        sorted.sort((a, b) -> Double.compare(b.helpfulDegree, a.helpfulDegree));

        List<String> insights = new ArrayList<>();
        for (ExperiencesDB.ExperienceEntry e : sorted) {
            if (e.helpfulDegree < MIN_METHOD_HELPFUL) continue;
            for (String text : e.expTexts) {
                if (text == null || text.isBlank()) continue;
                if (text.startsWith("想法:")) {
                    String thought = text.substring(3).trim();
                    if (thought.length() > 10 && thought.length() < 200) {
                        insights.add(thought);
                    }
                }
            }
            if (insights.size() >= 3) break;
        }

        if (insights.isEmpty()) return null;
        return String.join("；", insights);
    }

    // ==========================================
    // 4. 打分闭环 — 经验打分 → 方法成功率更新
    // ==========================================

    /**
     * 当 LLM 对经验打分后，将打分传播到关联的方法条目。
     * 方法通过 condition_dim_ids 与经验的 feelingDimIds 的重叠来关联。
     *
     * @param scoringArray  LLM 的 experience_scoring JSON 数组
     * @param experiencesDB 经验数据库
     */
    public void updateMethodSuccessFromScoring(JsonNode scoringArray, ExperiencesDB experiencesDB) {
        if (scoringArray == null || !scoringArray.isArray()) return;

        List<MethodEntry> allMethods = getAllMethods();
        if (allMethods.isEmpty()) return;

        int updated = 0;
        for (JsonNode s : scoringArray) {
            int expId = s.path("experience_id").asInt(-1);
            double score = s.path("score").asDouble(0.0);
            if (expId < 0 || score == 0.0) continue;

            ExperiencesDB.ExperienceEntry exp = experiencesDB.getById(expId);
            if (exp == null || exp.feelingDimIds.isEmpty()) continue;

            for (MethodEntry method : allMethods) {
                // 方法条件维度与经验感觉维度有交集 → 关联
                Set<Integer> methodDimSet = new HashSet<>(method.conditionDimIds);
                boolean related = false;
                for (int expDim : exp.feelingDimIds) {
                    if (methodDimSet.contains(expDim)) {
                        related = true;
                        break;
                    }
                }
                if (!related) continue;

                double clamped = score > 0 ? 1.0 : (score < 0 ? -1.0 : 0.0);
                updateMethodEvaluation(method.id, clamped);
                updated++;
            }
        }

        if (updated > 0) {
            log.info("[Association] 🔄 打分闭环: {} 条经验打分传播到关联方法", updated);
        }
    }

    // ==========================================
    // 5. 一键构建 Prompt 区块
    // ==========================================

    /**
     * 为当前 action 构建完整的联想 Prompt 区块。
     *
     * @param action     当前认知动作
     * @param feelingsDB 感觉数据库
     * @return 包含 "expectations" 和 "methodology" 的 Map
     */
    public Map<String, String> buildPromptBlock(CognitiveAction action, FeelingsDB feelingsDB) {
        Map<String, String> result = new LinkedHashMap<>();
        String expectations = computeExpectations(action, feelingsDB);
        String methodology = queryMethods(action, feelingsDB);
        if (!expectations.isEmpty()) result.put("expectations", expectations);
        if (!methodology.isEmpty()) result.put("methodology", methodology);
        return result;
    }

    // ==========================================
    // DB CRUD
    // ==========================================

    /** 内部方法条目 */
    private static class MethodEntry {
        final int id;
        final List<Integer> conditionDimIds;
        final String conditionSummary;
        final String methodText;
        final double successRate;
        final int totalEvaluations;
        final List<Integer> sourceExpIds;

        MethodEntry(int id, List<Integer> conditionDimIds, String conditionSummary,
                    String methodText, double successRate, int totalEvaluations,
                    List<Integer> sourceExpIds) {
            this.id = id;
            this.conditionDimIds = conditionDimIds != null ? conditionDimIds : List.of();
            this.conditionSummary = conditionSummary != null ? conditionSummary : "";
            this.methodText = methodText;
            this.successRate = successRate;
            this.totalEvaluations = totalEvaluations;
            this.sourceExpIds = sourceExpIds != null ? sourceExpIds : List.of();
        }
    }

    List<MethodEntry> getAllMethods() {
        List<MethodEntry> result = new ArrayList<>();
        String sql = "SELECT id, condition_dim_ids, condition_summary, method_text, " +
                "success_rate, total_evaluations, source_exp_ids FROM V4_Methods";
        try (Connection conn = CognitiveDB.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                try {
                    List<Integer> dimIds = CognitiveDB.getMapper().readValue(
                            rs.getString("condition_dim_ids"),
                            new TypeReference<List<Integer>>() {});
                    List<Integer> srcIds = CognitiveDB.getMapper().readValue(
                            rs.getString("source_exp_ids"),
                            new TypeReference<List<Integer>>() {});
                    result.add(new MethodEntry(
                            rs.getInt("id"), dimIds,
                            rs.getString("condition_summary"),
                            rs.getString("method_text"),
                            rs.getDouble("success_rate"),
                            rs.getInt("total_evaluations"),
                            srcIds));
                } catch (Exception e) {
                    log.warn("[Association] 解析方法 id={} 失败，跳过", rs.getInt("id"));
                }
            }
        } catch (SQLException e) {
            log.error("[Association] 获取全量方法失败", e);
        }
        return result;
    }

    private void insertMethod(List<Integer> dimIds, String methodText,
                              List<ExperiencesDB.ExperienceEntry> sources,
                              double initialSuccessRate, double[] embedding) {
        String conditionSummary = methodText.length() > 100
                ? methodText.substring(0, 100) + "..."
                : methodText;

        List<Integer> sourceIds = sources.stream().map(e -> e.id).collect(Collectors.toList());

        String sql = "INSERT INTO V4_Methods (condition_dim_ids, condition_summary, method_text, " +
                "success_rate, total_evaluations, source_exp_ids, embedding_json) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = CognitiveDB.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, CognitiveDB.getMapper().writeValueAsString(dimIds));
            pstmt.setString(2, conditionSummary);
            pstmt.setString(3, methodText);
            pstmt.setDouble(4, initialSuccessRate);
            pstmt.setInt(5, sources.size());
            pstmt.setString(6, CognitiveDB.getMapper().writeValueAsString(sourceIds));
            pstmt.setString(7, CognitiveDB.getMapper().writeValueAsString(embedding));
            pstmt.executeUpdate();
        } catch (SQLException | JsonProcessingException e) {
            log.error("[Association] 插入方法失败", e);
        }
    }

    private void updateMethodEvaluation(int methodId, double score) {
        // score: +1.0 = 正面验证, -1.0 = 负面验证
        List<MethodEntry> all = getAllMethods();
        MethodEntry target = null;
        for (MethodEntry m : all) {
            if (m.id == methodId) { target = m; break; }
        }
        if (target == null) return;

        int newTotal = target.totalEvaluations + 1;
        double scoreMapped = score > 0 ? 1.0 : 0.0;
        double newRate = (target.successRate * target.totalEvaluations + scoreMapped) / newTotal;

        String sql = "UPDATE V4_Methods SET success_rate = ?, total_evaluations = ? WHERE id = ?";
        try (Connection conn = CognitiveDB.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setDouble(1, newRate);
            pstmt.setInt(2, newTotal);
            pstmt.setInt(3, methodId);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            log.error("[Association] 更新方法评估失败 id={}", methodId, e);
        }
    }

    // ==========================================
    // 辅助方法
    // ==========================================

    /** 按 feeling_dim_ids 前 2 个维度做粗粒度聚类 */
    private Map<String, List<ExperiencesDB.ExperienceEntry>> clusterByCoarseDims(
            List<ExperiencesDB.ExperienceEntry> allExps) {
        Map<String, List<ExperiencesDB.ExperienceEntry>> clusters = new LinkedHashMap<>();

        for (ExperiencesDB.ExperienceEntry e : allExps) {
            if (e.feelingDimIds.isEmpty()) continue;
            List<Integer> sorted = new ArrayList<>(e.feelingDimIds);
            Collections.sort(sorted);
            // 粗粒度：取前 2 个维度
            String coarseKey = sorted.size() >= 2
                    ? sorted.get(0) + "," + sorted.get(1)
                    : sorted.get(0).toString();
            clusters.computeIfAbsent(coarseKey, k -> new ArrayList<>()).add(e);
        }

        return clusters;
    }

    private List<Integer> parseDimIds(String key) {
        return Arrays.stream(key.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Integer::parseInt)
                .collect(Collectors.toList());
    }

    private boolean hasOverlappingMethod(List<Integer> dimIds, double threshold) {
        List<MethodEntry> all = getAllMethods();
        for (MethodEntry m : all) {
            Set<Integer> mSet = new HashSet<>(m.conditionDimIds);
            int overlap = 0;
            for (int d : dimIds) {
                if (mSet.contains(d)) overlap++;
            }
            double ratio = (double) overlap / Math.max(dimIds.size(), m.conditionDimIds.size());
            if (ratio >= threshold) return true;
        }
        return false;
    }

    private String lookupConcept(int dimId, FeelingsDB feelingsDB) {
        FeelingEntry fe = feelingsDB.getById(dimId);
        return fe != null ? fe.getConcept() : "(维度#" + dimId + ")";
    }

    private List<String> lookupConcepts(Set<Integer> dimIds, FeelingsDB feelingsDB) {
        List<String> concepts = new ArrayList<>();
        for (int dimId : dimIds) {
            FeelingEntry fe = feelingsDB.getById(dimId);
            if (fe != null) concepts.add(fe.getConcept());
        }
        return concepts;
    }

    // ==========================================
    // 内部类：维度累加器
    // ==========================================

    private static class DimAccumulator {
        int frequency = 0;
        double helpfulSum = 0.0;
        int helpfulCount = 0;

        void add(double helpful) {
            frequency++;
            if (!Double.isNaN(helpful)) {
                helpfulSum += helpful;
                helpfulCount++;
            }
        }

        double avgHelpful() {
            return helpfulCount > 0 ? helpfulSum / helpfulCount : 0.0;
        }
    }
}
