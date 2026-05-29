package com.cna.agent;

import com.cna.config.ConfigsManager;
import com.cna.db.FeelingDimensionManager;
import com.cna.db.FeelingDimensionManager.DimensionScore;
import com.cna.db.FeelingHypergraphManager;
import com.cna.db.MemoryDB.FeelingDimension;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 感觉谐振分析器。
 *
 * 工作流程：
 * 1. 从 TaskText 提取 I1（top-K 感觉维度）
 * 2. 每个 I1 维度在超图中做 n 层扩展 → Gi
 * 3. 对每个 Gi 按与触发维度的向量相似度排序
 * 4. 动态拐点检测 → 分裂为 "不违和" / "违和"
 * 5. 为违和感觉查询深层记忆上下文
 * 6. 生成 LLM 可读的 Prompt 注入块
 */
@Slf4j
public class FeelingResonanceAnalyzer {

    private final FeelingDimensionManager fdm;
    private final FeelingHypergraphManager hgm;
    private final MemoryManager mm;

    private final int i1Size;
    private final int expandLayers;
    private final double inflectionSigma;
    private final double dissonanceMinGap;
    private final int deepMemoryCount;

    public FeelingResonanceAnalyzer(FeelingDimensionManager fdm, FeelingHypergraphManager hgm, MemoryManager mm) {
        this.fdm = fdm;
        this.hgm = hgm;
        this.mm = mm;
        this.i1Size = ConfigsManager.FEELING_RESONANCE_I1_SIZE;
        this.expandLayers = ConfigsManager.FEELING_HYPERGRAPH_EXPAND_LAYERS;
        this.inflectionSigma = ConfigsManager.FEELING_RESONANCE_INFLECTION_SIGMA;
        this.dissonanceMinGap = ConfigsManager.FEELING_RESONANCE_DISSONANCE_MIN_GAP;
        this.deepMemoryCount = ConfigsManager.FEELING_RESONANCE_DEEP_MEMORY_COUNT;
    }

    // =====================================================
    // 结果数据结构
    // =====================================================

    public static class ResonanceGroup {
        public final String sourceConcept;      // 触发维度概念名
        public final int sourceDimId;
        public final double sourceSimilarity;
        public final List<DimensionSimilarity> members; // Gi 内的所有成员（已排序）
        public final int inflectionIndex;       // 拐点位置（前 inflectionIndex 个是不违和）

        public ResonanceGroup(String sourceConcept, int sourceDimId, double sourceSimilarity,
                              List<DimensionSimilarity> members, int inflectionIndex) {
            this.sourceConcept = sourceConcept;
            this.sourceDimId = sourceDimId;
            this.sourceSimilarity = sourceSimilarity;
            this.members = members;
            this.inflectionIndex = inflectionIndex;
        }

        public List<DimensionSimilarity> getConsonant() {
            return members.subList(0, inflectionIndex);
        }

        public List<DimensionSimilarity> getDissonant() {
            return members.subList(inflectionIndex, members.size());
        }

        public boolean hasDissonance() {
            return inflectionIndex < members.size();
        }
    }

    public static class DimensionSimilarity {
        public final int dimId;
        public final String concept;
        public final String status;       // stable / dissonant
        public final String llmNotes;     // 之前的 LLM 分析
        public final double similarity;   // 与触发维度的向量相似度

        public DimensionSimilarity(int dimId, String concept, String status, String llmNotes, double similarity) {
            this.dimId = dimId;
            this.concept = concept;
            this.status = status;
            this.llmNotes = llmNotes;
            this.similarity = similarity;
        }
    }

    public static class ResonanceAnalysisResult {
        public final List<ResonanceGroup> groups;
        public final String llmPromptBlock;           // 可直接注入 prompt 的文本
        public final Set<Integer> allInvolvedDimIds;  // 所有涉及的维度 ID（供结算用）

        public ResonanceAnalysisResult(List<ResonanceGroup> groups, String llmPromptBlock, Set<Integer> allDimIds) {
            this.groups = groups;
            this.llmPromptBlock = llmPromptBlock;
            this.allInvolvedDimIds = allDimIds;
        }

        public boolean hasDissonance() {
            return groups.stream().anyMatch(ResonanceGroup::hasDissonance);
        }
    }

    // =====================================================
    // 主分析方法
    // =====================================================

    /**
     * 对任务文本进行感觉谐振分析。
     * @return 分析结果，无感觉维度时返回 null
     */
    public ResonanceAnalysisResult analyze(String taskText) {
        if (fdm == null || hgm == null) return null;

        // 1. 提取 I1: TaskText 的 top-K 感觉维度
        List<DimensionScore> i1 = fdm.getTargetDimensions(taskText, true, i1Size);
        if (i1.isEmpty()) {
            log.debug("[Resonance] 无感觉维度触发，跳过谐振分析");
            return null;
        }

        log.info("[Resonance] I1 提取: {} 个维度 → {}", i1.size(),
                i1.stream().map(s -> s.concept).collect(Collectors.joining(", ")));

        // 2. 获取完整的 FeelingDimension 列表（用于 id 映射和向量获取）
        List<FeelingDimension> allDims = fdm.getAllDimensions();

        // 3. 为每个 I1 维度做超图扩展 → Gi
        List<Integer> i1DimIds = new ArrayList<>();
        for (DimensionScore ds : i1) {
            // 找到对应的 dimension id
            allDims.stream()
                    .filter(d -> d.concept.equals(ds.concept))
                    .findFirst()
                    .ifPresent(d -> i1DimIds.add(d.id));
        }
        if (i1DimIds.isEmpty()) return null;

        Map<Integer, Set<Integer>> expanded = hgm.expandMulti(i1DimIds, expandLayers);

        // 4. 对每个 Gi 做拐点分析
        List<ResonanceGroup> groups = new ArrayList<>();
        Set<Integer> allInvolved = new LinkedHashSet<>();

        for (int idx = 0; idx < i1.size(); idx++) {
            DimensionScore i1Score = i1.get(idx);
            int srcDimId = i1DimIds.get(idx);
            Set<Integer> giIds = expanded.getOrDefault(srcDimId, new LinkedHashSet<>());
            allInvolved.addAll(giIds);

            // 构建 DimensionSimilarity 列表（用向量相似度排序）
            double[] srcVector = getVector(allDims, srcDimId);
            List<DimensionSimilarity> similarities = new ArrayList<>();

            for (int giId : giIds) {
                FeelingDimension dim = findDim(allDims, giId);
                if (dim == null) continue;
                double sim = srcVector != null ? cosineSimilarity(srcVector, dim.vector) : 0.0;
                similarities.add(new DimensionSimilarity(
                        dim.id, dim.concept, dim.status, dim.llmNotes, sim));
            }

            // 按相似度降序
            similarities.sort((a, b) -> Double.compare(b.similarity, a.similarity));

            // 动态拐点检测
            int inflection = detectInflection(similarities);
            groups.add(new ResonanceGroup(i1Score.concept, srcDimId, i1Score.similarity, similarities, inflection));
        }

        // 5. 生成 LLM Prompt 注入块
        String promptBlock = buildPromptBlock(groups);

        Set<Integer> allDimIds = allInvolved;
        ResonanceAnalysisResult result = new ResonanceAnalysisResult(groups, promptBlock, allDimIds);

        log.info("[Resonance] 分析完成: {} 组, {} 个涉及维度, 有违和: {}",
                groups.size(), allDimIds.size(), result.hasDissonance());

        return result;
    }

    // =====================================================
    // 拐点检测
    // =====================================================

    /**
     * 动态拐点检测算法。
     * 输入已按相似度降序排列的列表，找第一个"陡降"位置。
     *
     * 算法：
     * - 小样本（<4）：用绝对阈值 0.25，避免统计方法在小样本下失效
     * - 大样本（≥4）：计算相邻差值的均值+sigma*std 作为动态阈值
     * - 若所有差值都平稳或低于 minGap，拐点设在末尾（全部不违和）
     */
    int detectInflection(List<DimensionSimilarity> sorted) {
        if (sorted.size() < 2) return sorted.size();

        // 小样本 fallback：统计阈值不可靠，直接用绝对阈值
        if (sorted.size() < 4) {
            for (int i = 0; i < sorted.size(); i++) {
                if (sorted.get(i).similarity < 0.25) {
                    log.debug("[Inflection] 小样本拐点位于 index={}, sim={:.4f} < 0.25",
                            i, sorted.get(i).similarity);
                    return i;
                }
            }
            return sorted.size();
        }

        // 计算相邻差值
        double[] gaps = new double[sorted.size() - 1];
        for (int i = 0; i < gaps.length; i++) {
            gaps[i] = sorted.get(i).similarity - sorted.get(i + 1).similarity;
        }

        // 均值和标准差
        double mean = Arrays.stream(gaps).average().orElse(0);
        double variance = Arrays.stream(gaps).map(g -> (g - mean) * (g - mean)).average().orElse(0);
        double std = Math.sqrt(variance);

        double threshold = mean + inflectionSigma * std;

        // 找第一个超过阈值且不低于 minGap 的位置
        for (int i = 0; i < gaps.length; i++) {
            if (gaps[i] >= threshold && gaps[i] >= dissonanceMinGap) {
                log.debug("[Inflection] 拐点位于 index={}, gap={:.4f}, threshold={:.4f}",
                        i + 1, gaps[i], threshold);
                return i + 1; // 拐点后从 i+1 开始
            }
        }

        // 无拐点 → 全部不违和
        return sorted.size();
    }

    // =====================================================
    // Prompt 构建
    // =====================================================

    private String buildPromptBlock(List<ResonanceGroup> groups) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 感觉谐振分析\n\n");

        // 不违和汇总
        boolean hasAnyDissonance = groups.stream().anyMatch(ResonanceGroup::hasDissonance);

        if (!hasAnyDissonance) {
            sb.append("本轮触发的所有感觉均与已有经验一致，没有违和感。\n");
            for (ResonanceGroup g : groups) {
                List<DimensionSimilarity> c = g.getConsonant();
                if (!c.isEmpty()) {
                    sb.append("- 触发: **").append(g.sourceConcept).append("** → 关联: ");
                    sb.append(c.stream().map(m -> m.concept).collect(Collectors.joining(", ")));
                    sb.append("\n");
                }
            }
        } else {
            for (ResonanceGroup g : groups) {
                sb.append("### 触发感觉: **").append(g.sourceConcept)
                        .append("** (相似度 ").append(String.format("%.2f", g.sourceSimilarity)).append(")\n\n");

                // 不违和
                List<DimensionSimilarity> consonant = g.getConsonant();
                if (!consonant.isEmpty()) {
                    sb.append("**与以往一致 (不违和):**\n");
                    for (DimensionSimilarity m : consonant) {
                        sb.append("- ").append(m.concept);
                        sb.append(" (sim=").append(String.format("%.2f", m.similarity)).append(")\n");
                    }
                    sb.append("\n");
                }

                // 违和
                List<DimensionSimilarity> dissonant = g.getDissonant();
                if (!dissonant.isEmpty()) {
                    sb.append("**⚠️ 存在违和感:**\n");
                    for (DimensionSimilarity m : dissonant) {
                        sb.append("- **").append(m.concept).append("**");
                        sb.append(" (sim=").append(String.format("%.2f", m.similarity)).append(", ");
                        sb.append("status=").append(m.status).append(")");
                        if (m.llmNotes != null && !m.llmNotes.isBlank()) {
                            String notes = m.llmNotes.length() > 120
                                    ? m.llmNotes.substring(0, 120) + "..."
                                    : m.llmNotes;
                            sb.append("\n  之前的分析: ").append(notes);
                        }
                        sb.append("\n");
                    }
                    sb.append("\n请分析：为什么这些感觉与已有经验产生了矛盾？");
                    sb.append("在 finish_task 的 dissonance_updates 中说明你的判断。");
                    sb.append("如果暂时无法确定原因，也请如实记录。\n");
                }
            }
        }

        return sb.toString();
    }

    // =====================================================
    // 辅助
    // =====================================================

    private double[] getVector(List<FeelingDimension> dims, int dimId) {
        for (FeelingDimension d : dims) {
            if (d.id == dimId) return d.vector;
        }
        return null;
    }

    private FeelingDimension findDim(List<FeelingDimension> dims, int dimId) {
        for (FeelingDimension d : dims) {
            if (d.id == dimId) return d;
        }
        return null;
    }

    private double cosineSimilarity(double[] a, double[] b) {
        if (a == null || b == null || a.length != b.length) return 0.0;
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return (na == 0 || nb == 0) ? 0.0 : dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}
