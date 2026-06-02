package com.cna.apcore.association;

import com.cna.apcore.db.CognitiveDB;
import com.cna.apcore.db.ExperiencesDB;
import com.cna.apcore.db.FeelingsDB;
import com.cna.apcore.model.ActionPredict;
import com.cna.apcore.model.CognitiveAction;
import com.cna.apcore.model.FeelingEntry;
import lombok.extern.slf4j.Slf4j;

import java.sql.*;
import java.util.*;

/**
 * 联想引擎 — 纯经验检索，不维护独立的方法论表。
 *
 * <h3>设计原则</h3>
 * 不另建 V4_Methods 表来存储"方法论"。经验本身就是最好的老师：
 * 每一条经验（V4_Experiences）包含 feeling_dim_ids + exp_texts + helpful_degree，
 * 通过感觉维度交集找到相似场景的过往经验，直接展示原始经验文本给 LLM，
 * 由 LLM 自行判断如何借鉴——而不是由引擎预先抽取为"方法"。
 *
 * <h3>核心方法</h3>
 * {@link #queryRelatedExperiences(CognitiveAction, ExperiencesDB, FeelingsDB)}
 * ——按感觉维度重叠度 + helpful_degree 检索最相关的过往经验。
 *
 * @see CognitiveAction
 * @see ExperiencesDB
 * @see FeelingsDB
 */
@Slf4j
public class AssociationEngine {

    private static volatile AssociationEngine INSTANCE;

    /** 最多返回的相关经验条数 */
    private static final int MAX_RELATED_EXPERIENCES = 8;
    /** score_count 置信度上限（达到此值即满分） */
    private static final int SCORE_COUNT_CAP = 10;

    private AssociationEngine() {
        log.info("[Association] 🧠 联想引擎初始化完成（纯经验模式）");
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
    // 核心：经验检索 — 按感觉维度交集 + helpful 排序
    // ==========================================

    /**
     * 检索与当前 action 最相关的过往经验。
     *
     * <p>算法：
     * <ol>
     *   <li>从 action 的 UE 维度和触发经验（actionPredicts）中收集所有感觉维度 ID</li>
     *   <li>对全量经验计算感觉维度交集/并集比（Jaccard）</li>
     *   <li>按 Jaccard × helpful_degree 加权排序</li>
     *   <li>返回 Top-N 经验文本的格式化字符串</li>
     * </ol>
     *
     * @param action        当前认知动作
     * @param experiencesDB 经验数据库
     * @param feelingsDB    感觉数据库（用于查找维度概念名）
     * @return 格式化的相关经验文本，无匹配时返回空字符串
     */
    public String queryRelatedExperiences(CognitiveAction action,
                                           ExperiencesDB experiencesDB,
                                           FeelingsDB feelingsDB) {
        // 1. 收集当前场景的感觉维度
        Set<Integer> currentDims = new LinkedHashSet<>(action.getUEDimIds());

        List<ActionPredict> predicts = action.getActionPredicts();
        if (predicts != null) {
            for (ActionPredict p : predicts) {
                List<Integer> dims = p.getFeelingDimIds();
                if (dims != null) currentDims.addAll(dims);
            }
        }

        if (currentDims.isEmpty()) return "";

        // 2. 获取全量经验
        List<ExperiencesDB.ExperienceEntry> allExps = experiencesDB.getAll();
        if (allExps.isEmpty()) return "";

        // 3. 计算每条经验与当前场景的 Jaccard 相似度
        record ScoredExp(ExperiencesDB.ExperienceEntry exp, double score) {}
        List<ScoredExp> scored = new ArrayList<>();

        for (ExperiencesDB.ExperienceEntry e : allExps) {
            if (e.feelingDimIds.isEmpty()) continue;

            Set<Integer> expDims = new HashSet<>(e.feelingDimIds);
            int intersection = 0;
            for (int d : currentDims) {
                if (expDims.contains(d)) intersection++;
            }
            if (intersection == 0) continue;

            int union = currentDims.size() + expDims.size() - intersection;
            double jaccard = (double) intersection / union;

            // helpful_degree 归一化到 [0, 1]，中性值 0.5
            double helpfulNorm = Math.max(0.0, (e.helpfulDegree + 1.0) / 2.0);
            // score_count 置信度：被正面评分次数越多越可信，上限 SCORE_COUNT_CAP
            double confidence = Math.min(e.scoreCount, SCORE_COUNT_CAP) / (double) SCORE_COUNT_CAP;

            // 综合分数 = Jaccard 50% + helpful 25% + confidence 25%
            double score = jaccard * 0.5 + helpfulNorm * 0.25 + confidence * 0.25;
            scored.add(new ScoredExp(e, score));
        }

        if (scored.isEmpty()) return "";

        // 4. 按综合分数降序
        scored.sort((a, b) -> Double.compare(b.score, a.score));

        // 5. 格式化输出
        int limit = Math.min(MAX_RELATED_EXPERIENCES, scored.size());
        StringBuilder sb = new StringBuilder();
        sb.append("【相关过往经验 — 类似感觉维度场景】\n");

        for (int i = 0; i < limit; i++) {
            ScoredExp se = scored.get(i);
            ExperiencesDB.ExperienceEntry e = se.exp;

            // 查找概念名
            List<String> dimConcepts = lookupConcepts(new LinkedHashSet<>(e.feelingDimIds), feelingsDB);
            String dimInfo = dimConcepts.isEmpty() ? "(无)" : String.join("、", dimConcepts);

            String tag;
            if (e.helpfulDegree > 0.3) {
                tag = "🟢 有帮助";
            } else if (e.helpfulDegree < -0.1) {
                tag = "🔴 负面";
            } else {
                tag = "⚪ 中性";
            }

            sb.append(String.format("  [经验#%d] %s (匹配度: %.0f%%, helpful: %+.1f, 验证×%d)\n",
                    e.id, tag, se.score * 100, e.helpfulDegree, e.scoreCount));
            sb.append(String.format("    感觉维度: %s\n", dimInfo));
            for (String text : e.expTexts) {
                if (text != null && !text.isBlank()) {
                    sb.append(String.format("    · %s\n", text));
                }
            }
        }

        return sb.toString();
    }

    // ==========================================
    // 一键构建 Prompt 区块（供 ActionLoop 调用）
    // ==========================================

    /**
     * 为当前 action 构建联想 Prompt 区块。
     * 只包含按感觉维度检索的相关过往经验。
     */
    public Map<String, String> buildPromptBlock(CognitiveAction action,
                                                 ExperiencesDB experiencesDB,
                                                 FeelingsDB feelingsDB) {
        Map<String, String> result = new LinkedHashMap<>();
        String related = queryRelatedExperiences(action, experiencesDB, feelingsDB);
        if (!related.isEmpty()) result.put("related_experiences", related);
        return result;
    }

    // ==========================================
    // 辅助方法
    // ==========================================

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
}
