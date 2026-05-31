package com.cna.apcore.model;

import lombok.Getter;

import java.util.ArrayList;
import java.util.List;
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

    private double stimulateEnergy;           // SE: 外部刺激强度
    private double understandEnergy;          // UE: 理解能量（BFS 搜索结果）
    private int tick;                         // 未被选中的轮数
    private List<UEUnit> ueUnits;             // UE 计算涉及的所有感觉节点
    private double continueWeight;            // 持续权重，初始 1，每 tick 衰减，LLM 可 boost
    private double attentionEnergy;           // 注意力赋能累积（内源能量，与 SE 加算）
    private boolean endogenous;               // 是否来自 LLM 自生成（next_actions 创建）
    private double unitFatigue;               // 语义疲劳值 [0,1]，由 FatigueManager 在选前设置

    private CognitivePrepareUnit(String text, List<String> sourceIds, double stimulateEnergy) {
        this.uuid = UUID.randomUUID();
        this.text = text;
        this.sourceIds = sourceIds != null ? new ArrayList<>(sourceIds) : new ArrayList<>();
        this.stimulateEnergy = stimulateEnergy;
        this.understandEnergy = 0.0;
        this.tick = 0;
        this.ueUnits = new ArrayList<>();
        this.continueWeight = 1.0;
        this.attentionEnergy = 0.0;
        this.endogenous = false;
        this.unitFatigue = 0.0;
        this.createdAtMs = System.currentTimeMillis();
    }

    /** 工厂方法 */
    public static CognitivePrepareUnit create(String text, List<String> sourceIds) {
        return new CognitivePrepareUnit(text, sourceIds, 0.0);
    }

    /** 工厂方法，带初始 SE */
    public static CognitivePrepareUnit create(String text, List<String> sourceIds, double stimulateEnergy) {
        return new CognitivePrepareUnit(text, sourceIds, stimulateEnergy);
    }

    /** 设置刺激能量 */
    public void setSE(double se) {
        this.stimulateEnergy = Math.max(0.0, se);
    }

    /** 设置理解能量及关联的感觉节点 */
    public void setUE(double ue, List<UEUnit> units) {
        this.understandEnergy = Math.max(0.0, ue);
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

    /** 清除 UE 计算结果（文本变更后 UE 需要重算） */
    public void clearUE() {
        this.understandEnergy = 0.0;
        this.ueUnits = new ArrayList<>();
    }

    /**
     * 选择得分 = (SE + attentionEnergy) × UE × log₂(tick+1) × CW × fatiguePenalty
     *
     * 总能量 = 外源刺激能量 + 内源注意力累积能量。
     * 总能量 × UE 必须先超过 baselineThreshold 才有效，否则返回 0。
     *
     * tickFactor 使用对数压缩，避免旧任务无限积累优势：
     *   tick=0 → 1, tick=3 → 3, tick=7 → 4, tick=15 → 5
     *
     * fatiguePenalty = 1 / (1 + unitFatigue × sensitivity)
     *   疲劳越高，得分越低，自然倾向于切换到新鲜话题。
     */
    public double selectionScore(double baselineThreshold) {
        double totalEnergy = stimulateEnergy + attentionEnergy;
        double baseEnergy = totalEnergy * understandEnergy;
        if (baseEnergy < baselineThreshold) {
            return 0.0;
        }
        // 对数压缩 tick 因子
        double tickFactor = 1.0 + Math.log(tick + 1) / Math.log(2);
        // 疲劳惩罚
        double sensitivity = com.cna.apcore.config.CoreConfig.FATIGUE_SENSITIVITY;
        double fatiguePenalty = 1.0 / (1.0 + unitFatigue * sensitivity);

        return baseEnergy * tickFactor * continueWeight * fatiguePenalty;
    }

    /** 累加注意力能量 */
    public void addAttentionEnergy(double delta) {
        this.attentionEnergy = Math.max(0.0, this.attentionEnergy + delta);
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

    @Override
    public String toString() {
        String textPreview = text != null && text.length() > 50 ? text.substring(0, 50) + "..." : text;
        return String.format("CPU{uuid=%s, text='%s', SE=%.3f, UE=%.3f, tick=%d, cw=%.2f, ueUnits=%d}",
                uuid.toString().substring(0, 8), textPreview,
                stimulateEnergy, understandEnergy, tick, continueWeight,
                ueUnits != null ? ueUnits.size() : 0);
    }
}
