package com.cna.apcore.feeling;

import com.cna.agent.CuriosityListManager;
import com.cna.agent.FeelingResonanceAnalyzer;
import com.cna.apcore.MentalStateLogger;
import com.cna.apcore.db.CognitiveDB;
import com.cna.apcore.db.ExperiencesDB;
import com.cna.apcore.db.FeelingsDB;
import com.cna.apcore.model.FeelingEntry;
import com.cna.db.FeelingDimensionManager;
import com.cna.db.FeelingHypergraphManager;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.function.Function;

/**
 * V4 感觉维度管理器 —— 封装所有感觉相关的操作。
 *
 * 职责：
 * 1. 互斥感觉维度检测（语义相斥的已有概念）
 * 2. 刺激感觉处理（LLM 输出的 stimulated_feelings → dedup + 激活）
 * 3. action_feelings 处理（finish_action 中的感觉维度列表）
 * 4. 经验打分 + 准确度传播到关联感觉维度
 * 5. 感觉谐振分析（超图 BFS + 拐点检测，找出违和感觉）
 * 6. 违和感积累到好奇心列表
 *
 * 从 ActionLoop 中提取，减轻主编排器的职责负担。
 */
@Slf4j
public class FeelingsManager {

    // ── 互斥感觉维度检测参数 ──
    /** 互斥相似度上限：低于此值视为"语义相斥" */
    private static final double MUTUAL_EXCLUSION_SIMILARITY_THRESHOLD = 0.25;
    /** 互斥候选最小激活次数：只有"已建立"的概念才纳入互斥考量 */
    private static final int MUTUAL_EXCLUSION_MIN_ACTIVATION = 2;
    /** 互斥候选最大展示数 */
    private static final int MAX_MUTUAL_EXCLUSIONS = 10;

    private final FeelingsDB feelingsDB;
    private final ExperiencesDB experiencesDB;
    private final Function<String, double[]> embedder;

    public FeelingsManager(FeelingsDB feelingsDB, ExperiencesDB experiencesDB,
                           Function<String, double[]> embedder) {
        this.feelingsDB = feelingsDB;
        this.experiencesDB = experiencesDB;
        this.embedder = embedder;
    }

    // ==========================================
    // 互斥感觉维度检测
    // ==========================================

    /**
     * 检测与当前 action text 语义互斥的已有感觉维度。
     *
     * 算法：
     * 1. 从 FeelingsDB 获取所有感觉维度
     * 2. 计算每个维度 embedding 与 actionTextEmb 的余弦相似度
     * 3. 取相似度最低（< THRESHOLD）且激活次数 >= MIN_ACTIVATION 的维度
     * 4. 返回 top-N 作为"互斥候选"——这些是系统中已建立的概念，
     *    但与当前输入高度不匹配，可能代表矛盾或对立的认知方向
     *
     * @param actionTextEmb action 文本的 embedding
     * @return 互斥候选列表，每项含 dim_id, concept, similarity, activation_count
     */
    public List<Map<String, Object>> detectMutualExclusions(double[] actionTextEmb) {
        List<FeelingEntry> allFeelings = feelingsDB.getAll();
        if (allFeelings.isEmpty()) return List.of();

        // 计算每个感觉维度与 action 文本的相似度，按升序排列
        List<FeelingEntry> sorted = new ArrayList<>(allFeelings);
        sorted.sort((a, b) -> Double.compare(
                CognitiveDB.cosineSimilarity(actionTextEmb, a.getEmbedding()),
                CognitiveDB.cosineSimilarity(actionTextEmb, b.getEmbedding())));

        List<Map<String, Object>> result = new ArrayList<>();
        for (FeelingEntry f : sorted) {
            double sim = CognitiveDB.cosineSimilarity(actionTextEmb, f.getEmbedding());
            // 相似度超过阈值 → 不再互斥，停止
            if (sim >= MUTUAL_EXCLUSION_SIMILARITY_THRESHOLD) break;
            // 只纳入"已建立"的概念（激活次数足够）
            if (f.getActivationCount() < MUTUAL_EXCLUSION_MIN_ACTIVATION) continue;

            Map<String, Object> entry = new HashMap<>();
            entry.put("dim_id", f.getId());
            entry.put("concept", f.getConcept());
            entry.put("similarity", Math.round(sim * 1000.0) / 1000.0);
            entry.put("activation_count", f.getActivationCount());
            result.add(entry);

            if (result.size() >= MAX_MUTUAL_EXCLUSIONS) break;
        }
        return result;
    }

    // ==========================================
    // 刺激感觉处理
    // ==========================================

    /**
     * 处理 LLM 返回的 stimulated_feelings。
     * 对每个感觉维度：先 dedup（embedding），再插入或增加激活次数。
     *
     * @param feelingsArray stimulated_feelings JSON 数组
     * @return 所有关联的感觉维度 ID 列表
     */
    public List<Integer> processStimulatedFeelings(JsonNode feelingsArray) {
        List<Integer> dimIds = new ArrayList<>();
        if (feelingsArray == null || !feelingsArray.isArray()) {
            log.debug("[FeelingsManager] 无 stimulated_feelings");
            return dimIds;
        }

        int count = feelingsArray.size();
        log.info("[FeelingsManager] 🎯 处理 {} 个刺激感觉维度", count);

        for (JsonNode f : feelingsArray) {
            String concept = f.path("concept").asText();
            String embText = f.path("embedding_text").asText(concept);

            if (concept.isBlank()) continue;

            double[] emb = embedder.apply(embText);
            int id = feelingsDB.insertFeeling(concept, emb);
            if (id > 0) {
                feelingsDB.incrementActivation(id);
                dimIds.add(id);
                log.debug("[FeelingsManager]   刺激感觉: '{}' id={}", concept, id);

                // ★ 心智日志：感觉刺激
                FeelingEntry entry = feelingsDB.getById(id);
                MentalStateLogger.getInstance().feelingStimulated(
                        id, concept, true,
                        entry != null ? FeelingsDB.noveltyCurve(entry.getActivationCount()) : 1.0,
                        entry != null ? entry.getActivationCount() : 1);
            }
        }

        if (!dimIds.isEmpty()) {
            log.info("[FeelingsManager] ✅ 本轮刺激 {} 个感觉维度", dimIds.size());
        }
        return dimIds;
    }

    // ==========================================
    // action_feelings 处理（来自 finish_action）
    // ==========================================

    /**
     * 处理 finish_action 中的 action_feelings 字段。
     * 对已有维度验证存在后 incrementActivation，
     * 对新维度生成 embedding → insertFeeling (dedup) → 得到真实 ID。
     *
     * @param actionFeelingsArray finish_action 的 action_feelings JSON 数组
     * @return 解析出的所有维度 ID 列表
     */
    public List<Integer> processActionFeelings(JsonNode actionFeelingsArray) {
        List<Integer> resolvedDimIds = new ArrayList<>();
        if (actionFeelingsArray == null || !actionFeelingsArray.isArray()
                || actionFeelingsArray.size() == 0) {
            return resolvedDimIds;
        }

        int existingCount = 0;
        int newCount = 0;

        for (JsonNode af : actionFeelingsArray) {
            int dimId = af.path("dim_id").asInt(-1);
            String concept = af.path("concept").asText("");
            String embText = af.path("embedding_text").asText("");
            String relation = af.path("relation").asText("");

            if (dimId > 0) {
                // 已有维度：验证存在 → incrementActivation
                FeelingEntry existing = feelingsDB.getById(dimId);
                if (existing != null) {
                    feelingsDB.incrementActivation(dimId);
                    resolvedDimIds.add(dimId);
                    existingCount++;
                    MentalStateLogger.getInstance().feelingAction(dimId, existing.getConcept(), relation);
                } else {
                    log.warn("[FeelingsManager] action_feelings 引用不存在的 dim_id={}，跳过", dimId);
                }
            } else if (!concept.isBlank()) {
                // 新维度：生成 embedding → insertFeeling (dedup) → 得到真实 ID
                String textForEmb = !embText.isBlank() ? embText : concept;
                double[] emb = embedder.apply(textForEmb);
                if (emb != null) {
                    int realId = feelingsDB.insertFeeling(concept, emb);
                    if (realId > 0) {
                        resolvedDimIds.add(realId);
                        newCount++;
                        log.info("[FeelingsManager]   🆕 action_feeling 新维度: '{}' → id={}, relation={}",
                                concept, realId, relation);
                        MentalStateLogger.getInstance().feelingAction(realId, concept, relation);
                    }
                }
            }
        }

        log.info("[FeelingsManager] 📋 action_feelings 处理完成: {} 已有 + {} 新增 = {} 个感觉维度",
                existingCount, newCount, resolvedDimIds.size());
        return resolvedDimIds;
    }

    // ==========================================
    // 经验打分 + 准确度传播
    // ==========================================

    /**
     * 应用 LLM 返回的经验打分，并传播准确度增量到关联的感觉维度。
     */
    public void applyExperienceScoring(JsonNode scoringArray) {
        if (scoringArray == null || !scoringArray.isArray()) {
            log.debug("[FeelingsManager] 无 experience_scoring");
            return;
        }

        int count = scoringArray.size();
        log.info("[FeelingsManager] 📝 应用 {} 条经验打分", count);

        for (JsonNode s : scoringArray) {
            int expId = s.path("experience_id").asInt(-1);
            double score = s.path("score").asDouble(0.0);

            if (expId < 0) continue;

            // 限制 LLM 只用 1/0/-1
            double clamped = score > 0 ? 1.0 : (score < 0 ? -1.0 : 0.0);

            // 更新经验 HelpfulDegree
            experiencesDB.updateHelpfulDegree(expId, clamped);

            // 传播准确度到关联的感觉维度
            ExperiencesDB.ExperienceEntry exp = experiencesDB.getById(expId);
            if (exp != null && !exp.feelingDimIds.isEmpty()) {
                double delta = clamped / exp.feelingDimIds.size();
                for (int dimId : exp.feelingDimIds) {
                    feelingsDB.propagateAccuracy(dimId, delta);
                }
                log.info("[FeelingsManager]   经验 id=" + expId + " 打分=" + clamped
                        + ", 准确度传播到 " + exp.feelingDimIds.size() + " 个感觉维度 (delta=" + String.format("%.4f", delta) + ")");

                MentalStateLogger.getInstance().feelingExperienceScored(
                        expId, clamped, exp.feelingDimIds.size());
            }
        }
    }

    // ==========================================
    // 感觉谐振分析
    // ==========================================

    /**
     * 对 action text 做超图 BFS + 拐点检测，找出违和感觉维度。
     *
     * @param actionText 动作文本
     * @return 谐振分析结果（含 llmPromptBlock），失败返回 null
     */
    public FeelingResonanceAnalyzer.ResonanceAnalysisResult analyzeResonance(String actionText) {
        try {
            FeelingDimensionManager fdm = FeelingDimensionManager.getInstance();
            FeelingHypergraphManager hgm = FeelingHypergraphManager.getInstance();
            if (fdm != null && hgm != null) {
                FeelingResonanceAnalyzer analyzer = new FeelingResonanceAnalyzer(fdm, hgm, null);
                FeelingResonanceAnalyzer.ResonanceAnalysisResult resonance = analyzer.analyze(actionText);
                if (resonance != null) {
                    log.info("[FeelingsManager] 🔍 感觉谐振分析完成: {} 组, 有违和={}",
                            resonance.groups.size(), resonance.hasDissonance());

                    // ★ 心智日志：谐振分析
                    int dissonantCount = (int) resonance.groups.stream()
                            .mapToLong(g -> g.getDissonant().size()).sum();
                    int consonantCount = (int) resonance.groups.stream()
                            .mapToLong(g -> g.getConsonant().size()).sum();
                    double maxDissonanceScore = resonance.groups.stream()
                            .flatMap(g -> g.getDissonant().stream())
                            .mapToDouble(ds -> ds.similarity)
                            .max().orElse(0.0);
                    MentalStateLogger.getInstance().feelingResonance(
                            dissonantCount, consonantCount, maxDissonanceScore);
                }
                return resonance;
            }
        } catch (Exception e) {
            log.warn("[FeelingsManager] 感觉谐振分析失败，跳过: {}", e.getMessage());
        }
        return null;
    }

    // ==========================================
    // 违和感积累
    // ==========================================

    /**
     * 将有违和的谐振分析结果持久化到好奇心列表。
     */
    public void accumulateDissonance(
            FeelingResonanceAnalyzer.ResonanceAnalysisResult resonance,
            String actionText) {
        if (resonance == null || !resonance.hasDissonance()) return;

        try {
            CuriosityListManager clm = CuriosityListManager.getInstance();
            if (clm != null) {
                clm.accumulateFromResonance(resonance, actionText);
                log.info("[FeelingsManager] 📝 违和感已积累到好奇心列表");

                // ★ 心智日志：违和感积累
                int dissonantGroupCount = (int) resonance.groups.stream()
                        .filter(g -> g.hasDissonance()).count();
                double peakDissonance = resonance.groups.stream()
                        .flatMap(g -> g.getDissonant().stream())
                        .mapToDouble(ds -> ds.similarity)
                        .max().orElse(0.0);
                MentalStateLogger.getInstance().feelingDissonanceAccumulated(
                        dissonantGroupCount, peakDissonance);
            }
        } catch (Exception e) {
            log.warn("[FeelingsManager] 违和感积累失败: {}", e.getMessage());
        }
    }
}
