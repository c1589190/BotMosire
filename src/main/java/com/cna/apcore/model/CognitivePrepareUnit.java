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
    private final String text;                // 聚合后的消息文本
    private final List<String> sourceIds;     // 来源标识符列表（如 "qqid:12345", "qq_group:67890"）
    private final long createdAtMs;           // 创建时间戳

    private double stimulateEnergy;           // SE: 外部刺激强度
    private double understandEnergy;          // UE: 理解能量（BFS 搜索结果）
    private int tick;                         // 未被选中的轮数
    private List<UEUnit> ueUnits;             // UE 计算涉及的所有感觉节点
    private double continueWeight;            // 持续权重，初始 1，每 tick 衰减，LLM 可 boost

    private CognitivePrepareUnit(String text, List<String> sourceIds, double stimulateEnergy) {
        this.uuid = UUID.randomUUID();
        this.text = text;
        this.sourceIds = sourceIds != null ? new ArrayList<>(sourceIds) : new ArrayList<>();
        this.stimulateEnergy = stimulateEnergy;
        this.understandEnergy = 0.0;
        this.tick = 0;
        this.ueUnits = new ArrayList<>();
        this.continueWeight = 1.0;
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

    /**
     * 选择得分 = SE × UE × tick
     * SE×UE 必须先超过 baselineThreshold 才有效，否则返回 0。
     */
    public double selectionScore(double baselineThreshold) {
        double baseEnergy = stimulateEnergy * understandEnergy;
        if (baseEnergy < baselineThreshold) {
            return 0.0;
        }
        // tick 至少为 1 以保证新单元也能参与比较
        int effectiveTick = Math.max(1, tick);
        return baseEnergy * effectiveTick * continueWeight;
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
