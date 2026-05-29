package com.cna.agent;

import com.cna.db.FeelingDimensionManager;
import com.cna.db.FeelingHypergraphManager;
import com.cna.db.MemoryDB;
import com.cna.db.MemoryDB.FeelingDimension;
import com.cna.llm.LLManager;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

/**
 * 违和感觉结算器。
 *
 * 在 finish_task 时处理 LLM 对违和感的分析：
 * 1. resolved=true  → 创建新的 resolution 维度 J，将 J 与违和来源建立 resolution 边
 * 2. resolved=false → 更新 llm_notes，保留 dissonant 状态
 * 3. 无论是否解决 → 违和来源之间加权 dissonant_source 边
 * 4. 不违和维度 → 查询超图枢纽，加权关联边
 */
@Slf4j
public class FeelingDissonanceResolver {

    private final FeelingDimensionManager fdm;
    private final FeelingHypergraphManager hgm;
    private final MemoryDB db;
    private final com.cna.llm.LLMAdapter embLLM;

    public FeelingDissonanceResolver(FeelingDimensionManager fdm, FeelingHypergraphManager hgm,
                                      MemoryDB db, com.cna.llm.LLMAdapter embLLM) {
        this.fdm = fdm;
        this.hgm = hgm;
        this.db = db;
        this.embLLM = embLLM;
    }

    // =====================================================
    // 数据类
    // =====================================================

    public static class DissonanceUpdate {
        public final int dimId;
        public final String newNotes;
        public final boolean resolved;
        public final String resolutionConcept; // resolved 时的新概念名
        public final boolean isPositive;       // resolution 概念的极性

        public DissonanceUpdate(int dimId, String newNotes, boolean resolved,
                                 String resolutionConcept, boolean isPositive) {
            this.dimId = dimId;
            this.newNotes = newNotes;
            this.resolved = resolved;
            this.resolutionConcept = resolutionConcept;
            this.isPositive = isPositive;
        }
    }

    // =====================================================
    // 主处理方法
    // =====================================================

    /**
     * 处理 finish_task 中的 dissonance_updates。
     *
     * @param updates          LLM 反馈的违和更新列表
     * @param consonantDimIds  本轮的不违和维度 ID 列表（用于加权关联边）
     * @param allInvolvedDimIds 本轮所有涉及的维度 ID（用于共现边建立）
     * @return 处理统计信息
     */
    public String processDissonanceUpdates(List<DissonanceUpdate> updates,
                                            List<Integer> consonantDimIds,
                                            Set<Integer> allInvolvedDimIds) {
        if (updates == null) updates = List.of();

        int resolvedCount = 0;
        int unresolvedCount = 0;

        for (DissonanceUpdate u : updates) {
            if (u.resolved) {
                processResolved(u);
                resolvedCount++;
            } else {
                processUnresolved(u);
                unresolvedCount++;
            }
        }

        // 不违和维度：查询超图枢纽 + 加权关联边
        if (consonantDimIds != null && consonantDimIds.size() >= 2) {
            strengthenConsonantEdges(consonantDimIds);
        }

        // 所有涉及维度之间建立共现边（低频共现权重低，高频自然累积）
        if (allInvolvedDimIds != null && allInvolvedDimIds.size() >= 2) {
            List<Integer> idList = new ArrayList<>(allInvolvedDimIds);
            hgm.onCoOccurrence(idList);
        }

        log.info("[Dissonance] 结算完成: resolved={}, unresolved={}, consonant强化={}",
                resolvedCount, unresolvedCount,
                consonantDimIds != null ? consonantDimIds.size() : 0);

        return String.format("违和结算: %d 已解决, %d 待定", resolvedCount, unresolvedCount);
    }

    // =====================================================
    // 已解决违和
    // =====================================================

    private void processResolved(DissonanceUpdate u) {
        // 1. 将原有违和维度标记为 stable
        db.updateDimensionStatusAndNotes(u.dimId, "stable", u.newNotes);
        log.info("[Dissonance] 违和维度 id={} 已解决 → stable", u.dimId);

        // 2. 如果 LLM 给出了 resolution 概念名，创建新维度 J
        if (u.resolutionConcept != null && !u.resolutionConcept.isBlank()) {
            double[] vector = LLManager.getTextVector(u.resolutionConcept, embLLM);
            if (vector != null && vector.length > 0) {
                double polarity = u.isPositive ? 1.0 : -1.0;
                db.insertFeelingDimension(u.resolutionConcept, vector, polarity * 0.5);

                // 找到 J 的 ID
                List<FeelingDimension> allDims = fdm.getAllDimensions();
                FeelingDimension jDim = allDims.stream()
                        .filter(d -> d.concept.equals(u.resolutionConcept))
                        .findFirst().orElse(null);

                if (jDim != null) {
                    // J ←→ 违和来源 建立 resolution 边
                    List<MemoryDB.HypergraphEdge> inEdges = db.getEdgesTo(u.dimId);
                    Set<Integer> relatedSourceDims = new LinkedHashSet<>();
                    for (MemoryDB.HypergraphEdge e : inEdges) {
                        if ("dissonant_source".equals(e.relationType)) {
                            relatedSourceDims.add(e.sourceDimId);
                        }
                    }
                    // 也加入出边
                    for (MemoryDB.HypergraphEdge e : db.getEdgesFrom(u.dimId)) {
                        if ("dissonant_source".equals(e.relationType)) {
                            relatedSourceDims.add(e.targetDimId);
                        }
                    }

                    for (int srcId : relatedSourceDims) {
                        hgm.upsertEdge(jDim.id, srcId, "resolution", 1.0);
                        hgm.upsertEdge(srcId, jDim.id, "resolution", 1.0);
                    }
                    log.info("[Dissonance] 创建 resolution 维度 J='{}' id={}, 链接 {} 个来源",
                            u.resolutionConcept, jDim.id, relatedSourceDims.size());
                }
            }
        }
    }

    // =====================================================
    // 未解决违和
    // =====================================================

    private void processUnresolved(DissonanceUpdate u) {
        // 1. 保持 dissonant 状态，追加 llm_notes
        List<FeelingDimension> allDims = fdm.getAllDimensions();
        FeelingDimension dim = allDims.stream().filter(d -> d.id == u.dimId).findFirst().orElse(null);
        if (dim == null) {
            log.warn("[Dissonance] 找不到违和维度 id={}", u.dimId);
            return;
        }

        // 追加式更新：保留旧 notes 并在末尾追加新 notes
        String oldNotes = dim.llmNotes != null ? dim.llmNotes : "";
        String timestamp = java.time.LocalDate.now().toString();
        String appendedNotes = oldNotes + (oldNotes.isEmpty() ? "" : "\n") + timestamp + ": " + u.newNotes;

        db.updateDimensionStatusAndNotes(u.dimId, "dissonant", appendedNotes);
        log.info("[Dissonance] 违和维度 id={} 仍 unresolved，notes 已追加", u.dimId);

        // 2. 与相关来源建立/加权 dissonant_source 边
        List<MemoryDB.HypergraphEdge> edges = db.getEdgesTo(u.dimId);
        for (MemoryDB.HypergraphEdge e : edges) {
            if ("dissonant_source".equals(e.relationType) || "associated".equals(e.relationType)) {
                hgm.upsertEdge(e.sourceDimId, u.dimId, "dissonant_source", 0.3);
            }
        }
        for (MemoryDB.HypergraphEdge e : db.getEdgesFrom(u.dimId)) {
            if ("dissonant_source".equals(e.relationType) || "associated".equals(e.relationType)) {
                hgm.upsertEdge(u.dimId, e.targetDimId, "dissonant_source", 0.3);
            }
        }
    }

    // =====================================================
    // 不违和强化
    // =====================================================

    /**
     * 对不违和维度组：查找超图中的枢纽节点，加权所有关联边。
     */
    private void strengthenConsonantEdges(List<Integer> consonantDimIds) {
        // 查找连接这些维度的枢纽节点
        Map<Integer, Integer> hubs = hgm.findConnectingNodes(consonantDimIds);

        // 对枢纽节点与 consonant 维度之间的边进行加权
        for (Map.Entry<Integer, Integer> hub : hubs.entrySet()) {
            int hubId = hub.getKey();
            for (int dimId : consonantDimIds) {
                if (dimId != hubId) {
                    hgm.upsertEdge(hubId, dimId, "associated", 0.5);
                }
            }
        }
        log.debug("[Dissonance] {} 个枢纽节点，已强化不违和关联边", hubs.size());
    }
}
