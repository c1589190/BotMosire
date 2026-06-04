package com.cna.apcore.model;

import com.cna.apcore.config.CoreConfig;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;

/**
 * 认知动作 — 从 CognitivePrepareUnit 选中后转换而来。
 *
 * 携带 6 个计算好的"情绪"字段，以及关联的 UE 节点和先验经验预测，
 * 是整个 V4 认知循环中 LLM 交互的核心上下文载体。
 */
@Getter
public class CognitiveAction {
    /** 来源准备单元 */
    private final CognitivePrepareUnit sourceUnit;

    /** 动作文本（与 sourceUnit.text 相同，但语义上表示"这是要执行的"） */
    private final String actionText;

    // ===== 6 个情绪字段 =====

    /** 认知熟悉度（CF）：ActionText 与所有 UE 感觉维度 embedding 的加权相似度之和 */
    private double cognitiveFamiliarity;

    /** 认知规模：根据 UEUnit 数量决定请求多少条经验 */
    private int scale;

    /** 意外度：UE - CF，衡量这个 ActionText 有多"意料之外" */
    private double accidentDegree;

    /** 行动预测：从 ExperiencesDB 检索到的 top-N 先验经验 */
    private List<ActionPredict> actionPredicts;

    /** 行动压力：TODO，暂为 0 */
    private double actionPressure;

    /** 持续权重：初始 1，每 tick 衰减 */
    private double continueWeight;

    private CognitiveAction(CognitivePrepareUnit sourceUnit) {
        this.sourceUnit = sourceUnit;
        this.actionText = sourceUnit.getText();
        this.cognitiveFamiliarity = 0.0;
        this.scale = 1;
        this.accidentDegree = 0.0;
        this.actionPredicts = new ArrayList<>();
        this.actionPressure = 0.0;
        this.continueWeight = sourceUnit.getContinueWeight();
    }

    /** 从准备单元构建 CognitiveAction */
    public static CognitiveAction from(CognitivePrepareUnit unit) {
        return new CognitiveAction(unit);
    }

    // ===== 计算各情绪字段 =====

    /**
     * 计算 CognitiveFamiliarity（认知熟悉度）。
     *
     * 公式：CF = Σ cosSim(actionTextEmb, ueUnit.embedding) × ueUnit.noveltyWeight × ueUnit.layerWeight
     *
     * 即：ActionText 与每个 UE 节点的语义相似度，乘以该节点的感觉权重和 BFS 层数权重。
     *
     * @param actionTextEmb ActionText 的 embedding
     * @param ueUnits       UE 计算涉及的所有感觉节点
     */
    public void computeFamiliarity(double[] actionTextEmb, List<UEUnit> ueUnits) {
        if (ueUnits == null || ueUnits.isEmpty() || actionTextEmb == null) {
            this.cognitiveFamiliarity = 0.0;
            return;
        }
        double sum = 0.0;
        for (UEUnit u : ueUnits) {
            double sim = cosineSimilarity(actionTextEmb, u.getEmbedding());
            sum += sim * u.getNoveltyWeight() * u.getLayerWeight();
        }
        this.cognitiveFamiliarity = sum;
    }

    /** 根据 UEUnit 数量计算 Scale（决定请求多少条经验） */
    public void computeScale() {
        if (sourceUnit.getUeUnits() == null || sourceUnit.getUeUnits().isEmpty()) {
            this.scale = 1;
            return;
        }
        int nodeCount = sourceUnit.getUeUnits().size();
        this.scale = Math.max(1, nodeCount * CoreConfig.SCALE_PER_UE_NODE);
    }

    /** 计算 AccidentDegree（意外度）= UE - CF */
    public void computeAccidentDegree() {
        this.accidentDegree = sourceUnit.getSemanticWeight() - this.cognitiveFamiliarity;
    }

    /** 注入从 ExperiencesDB 检索到的先验经验 */
    public void setPredicts(List<ActionPredict> predicts) {
        this.actionPredicts = predicts != null ? predicts : new ArrayList<>();
    }

    /** 衰减 ContinueWeight */
    public void decayContinueWeight(double decayRate) {
        this.continueWeight = Math.max(0.0, this.continueWeight * decayRate);
    }

    /** 提升 ContinueWeight（上限由配置控制） */
    public void boostContinueWeight(double boost) {
        this.continueWeight = Math.min(CoreConfig.MAX_CONTINUE_WEIGHT, this.continueWeight + boost);
    }

    // ===== 查询方法 =====

    /** 获取所有 UE 节点的感觉维度 ID 列表 */
    public List<Integer> getUEDimIds() {
        List<Integer> ids = new ArrayList<>();
        if (sourceUnit.getUeUnits() != null) {
            for (UEUnit u : sourceUnit.getUeUnits()) {
                if (u.getDimId() > 0) {
                    ids.add(u.getDimId());
                }
            }
        }
        return ids;
    }

    /** 获取所有 UE 节点的概念文本列表 */
    public List<String> getUEConcepts() {
        List<String> concepts = new ArrayList<>();
        if (sourceUnit.getUeUnits() != null) {
            for (UEUnit u : sourceUnit.getUeUnits()) {
                if (u.getConcept() != null && !u.getConcept().isBlank()) {
                    concepts.add(u.getConcept());
                }
            }
        }
        return concepts;
    }

    /** 构建简要摘要，供 prompt 注入 */
    public String buildSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("【认知动作摘要】\n"));
        sb.append(String.format("动作文本: %s\n",
                actionText != null && actionText.length() > 100
                        ? actionText.substring(0, 100) + "..." : actionText));
        sb.append(String.format("认知熟悉度: %.3f | 规模: %d | 意外度: %.3f\n",
                cognitiveFamiliarity, scale, accidentDegree));
        sb.append(String.format("行动压力: %.3f | 持续权重: %.3f\n",
                actionPressure, continueWeight));
        sb.append(String.format("关联感觉: %s\n", getUEConcepts()));
        if (!actionPredicts.isEmpty()) {
            sb.append(String.format("先验经验 %d 条:\n", actionPredicts.size()));
            for (ActionPredict p : actionPredicts) {
                sb.append("  - ").append(p.toString()).append("\n");
            }
        }
        return sb.toString();
    }

    private static double cosineSimilarity(double[] a, double[] b) {
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
