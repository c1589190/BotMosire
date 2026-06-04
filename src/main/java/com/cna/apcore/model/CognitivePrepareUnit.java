package com.cna.apcore.model;

import lombok.Getter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 认知准备单元 — 聚合某一时间窗口内来自同一来源的消息。
 *
 * 存放在 CognitivePreparePool 中，每 tick 其 tick++。
 * 当被选中时（SE × UE × tick 最大且 SE×UE 超过基础底线），
 * 会被转换为 CognitiveAction 送入 LLM 处理。
 */
@Getter
public class CognitivePrepareUnit {
    private final UUID uuid;
    private String text;                      // 聚合后的消息文本（支持同源合并追加）
    private final List<String> sourceIds;     // 来源标识符列表（如 "qqid:12345", "qq_group:67890"）
    private final long createdAtMs;           // 创建时间戳

    private double sourcePriority;            // 来源优先级（外源=消息统计值，内源=继承父权重+定值）
    private double semanticWeight;            // 语义权重（ActionText 在感觉维度超图 BFS 结果）
    private int tick;                         // 未被选中的轮数
    private List<UEUnit> ueUnits;             // UE 计算涉及的所有感觉节点
    private double continueWeight;            // 持续权重，初始 1，每 tick 衰减，LLM 可 boost
    private double attentionEnergy;           // 注意力赋能累积（内源能量，与 SE 加算）
    private boolean endogenous;               // 是否来自 LLM 自生成（next_actions 创建）
    private double unitFatigue;               // 语义疲劳值 [0,1]，由 FatigueManager 在选前设置
    private double[] cachedEmbedding;         // 预计算的文本 embedding（push 时计算，避免 select 时重复）
    private Map<Integer, Double> feelingMatchStrengths; // 每个感觉维度的归一化匹配强度 dimId→strength
    private double attentionAttitudeMultiplier; // 注意力态度乘数，默认 1.0（中性），>1=应多关注，<1=应少关注

    private CognitivePrepareUnit(String text, List<String> sourceIds, double sourcePriority) {
        this.uuid = UUID.randomUUID();
        this.text = text;
        this.sourceIds = sourceIds != null ? new ArrayList<>(sourceIds) : new ArrayList<>();
        this.sourcePriority = sourcePriority;
        this.semanticWeight = 0.0;
        this.tick = 0;
        this.ueUnits = new ArrayList<>();
        this.continueWeight = 1.0;
        this.attentionEnergy = 0.0;
        this.endogenous = false;
        this.unitFatigue = 0.0;
        this.cachedEmbedding = null;
        this.feelingMatchStrengths = new HashMap<>();
        this.attentionAttitudeMultiplier = 1.0;
        this.createdAtMs = System.currentTimeMillis();
    }

    /** 工厂方法（无来源优先级） */
    public static CognitivePrepareUnit create(String text, List<String> sourceIds) {
        return new CognitivePrepareUnit(text, sourceIds, 0.0);
    }

    /** 工厂方法，带初始来源优先级 */
    public static CognitivePrepareUnit create(String text, List<String> sourceIds, double sourcePriority) {
        return new CognitivePrepareUnit(text, sourceIds, sourcePriority);
    }

    /** 设置来源优先级 */
    public void setSourcePriority(double p) {
        this.sourcePriority = Math.max(0.0, p);
    }

    /** 设置语义权重及关联的感觉节点 */
    public void setSemanticWeight(double sw, List<UEUnit> units) {
        this.semanticWeight = Math.max(0.0, sw);
        this.ueUnits = units != null ? new ArrayList<>(units) : new ArrayList<>();
    }

    /** 设置持续权重 */
    public void setContinueWeight(double w) {
        this.continueWeight = Math.max(0.0, w);
    }

    /** tick++ */
    public void tick() {
        this.tick++;
    }

    /** 衰减 ContinueWeight */
    public void decayContinueWeight(double decayFactor) {
        this.continueWeight = Math.max(0.0, this.continueWeight * decayFactor);
    }

    /** 提升 ContinueWeight（上限由外部控制） */
    public void boostContinueWeight(double boost, double maxWeight) {
        this.continueWeight = Math.min(maxWeight, this.continueWeight + boost);
    }

    /** 追加文本（同源合并时使用），用分隔符连接 */
    public void appendText(String newText) {
        if (newText != null && !newText.isBlank()) {
            this.text = this.text + "\n---\n" + newText;
        }
    }

    /** 重置 tick 为 0（同源合并时使用，给合并后的单元新鲜度） */
    public void resetTick() {
        this.tick = 0;
    }

    /** 清除语义权重计算结果（文本变更后需要重算） */
    public void clearSemanticWeight() {
        this.semanticWeight = 0.0;
        this.ueUnits = new ArrayList<>();
        this.cachedEmbedding = null;
        this.feelingMatchStrengths = new HashMap<>();
        this.attentionAttitudeMultiplier = 1.0;
    }

    /**
     * 选取得分 = (semanticWeight + sourcePriority + tickBonus + attentionEnergy)
     *           × CW × fatiguePenalty × attnAttitudeMultiplier
     *
     * semanticWeight:   ActionText 在感觉维度超图 BFS 的结果（外源有值，内源为 0）
     * sourcePriority:   来源的实践重要性（外源=消息统计，内源=继承父权重+定值加成）
     * tickBonus:        挂起时间对数加成 log₂(tick+1)
     * attentionEnergy:  AttentionManager 每 tick 分配的内源蓄能
     * CW:               LLM 手动 boost 的持续权重
     * fatiguePenalty:   刚处理过类似感觉 → 分数降低
     * attnAttitudeMultiplier: 对匹配感觉维度的长期态度倾向
     */
    public double selectionScore(double baselineThreshold) {
        double tickBonus = 1.0 + Math.log(tick + 1) / Math.log(2);
        double practicalBonus = sourcePriority + tickBonus + attentionEnergy;
        double baseScore = semanticWeight + practicalBonus;

        if (baseScore < baselineThreshold) {
            return 0.0;
        }

        double sensitivity = com.cna.apcore.config.CoreConfig.FATIGUE_SENSITIVITY;
        double fatiguePenalty = 1.0 / (1.0 + unitFatigue * sensitivity);

        return baseScore * continueWeight * fatiguePenalty * attentionAttitudeMultiplier;
    }

    /** 累加注意力能量 */
    public void addAttentionEnergy(double delta) {
        this.attentionEnergy = Math.max(0.0, this.attentionEnergy + delta);
    }

    /** 获取语义权重子项（用于 next_action 继承） */
    public double getSemanticWeight() { return semanticWeight; }

    /** 获取来源优先级子项（用于 next_action 继承） */
    public double getSourcePriority() { return sourcePriority; }

    /** next_action 继承用的父权重 = semanticWeight + sourcePriority */
    public double getParentWeight() {
        return semanticWeight + sourcePriority;
    }

    /** 衰减注意力能量（未被持续关注时消退） */
    public void decayAttentionEnergy(double factor) {
        this.attentionEnergy = Math.max(0.0, this.attentionEnergy * (1.0 - factor));
    }

    /** 标记为内源自生成任务 */
    public void setEndogenous(boolean endogenous) {
        this.endogenous = endogenous;
    }

    public boolean isEndogenous() {
        return endogenous;
    }

    /** 设置语义疲劳值（由 FatigueManager 在选前计算） */
    public void setUnitFatigue(double fatigue) {
        this.unitFatigue = Math.max(0.0, Math.min(1.0, fatigue));
    }

    /** 获取语义疲劳值 */
    public double getUnitFatigue() {
        return unitFatigue;
    }

    // ── 注意力态度乘数相关 ──

    /** 获取预计算的文本 embedding（push 时计算，select 时复用） */
    public double[] getCachedEmbedding() {
        return cachedEmbedding;
    }

    /** 设置预计算的文本 embedding */
    public void setCachedEmbedding(double[] emb) {
        this.cachedEmbedding = emb;
    }

    /** 获取每个感觉维度的归一化匹配强度 */
    public Map<Integer, Double> getFeelingMatchStrengths() {
        return feelingMatchStrengths;
    }

    /** 设置感觉匹配强度 */
    public void setFeelingMatchStrengths(Map<Integer, Double> strengths) {
        this.feelingMatchStrengths = strengths != null ? new HashMap<>(strengths) : new HashMap<>();
    }

    /** 获取注意力态度乘数 */
    public double getAttentionAttitudeMultiplier() {
        return attentionAttitudeMultiplier;
    }

    /** 设置注意力态度乘数（自动 clamp 到安全范围） */
    public void setAttentionAttitudeMultiplier(double multiplier) {
        this.attentionAttitudeMultiplier = Math.max(0.2, Math.min(5.0, multiplier));
    }

    @Override
    public String toString() {
        String textPreview = text != null && text.length() > 50 ? text.substring(0, 50) + "..." : text;
        return String.format("CPU{uuid=%s, text='%s', SE=%.3f, UE=%.3f, tick=%d, cw=%.2f, ueUnits=%d}",
                uuid.toString().substring(0, 8), textPreview,
                sourcePriority, semanticWeight, tick, continueWeight,
                ueUnits != null ? ueUnits.size() : 0);
    }
}
