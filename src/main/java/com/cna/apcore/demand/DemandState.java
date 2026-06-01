package com.cna.apcore.demand;

/**
 * 动机状态 — 一个 CognitiveAction 的六维认知感受计算结果。
 *
 * 包含原始值、z-score、偏离方向，供 DemandManager.renderPrompt() 使用。
 */
public class DemandState {

    // ===== 六维原始值 =====
    public final double confidenceRaw;    // 把握感: CF / UE
    public final double surpriseRaw;      // 惊: max(0, UE - CF)
    public final double dissonanceRaw;    // 违和感: mutualExclusions 加权
    public final double plausibilityRaw;  // 合理感: resonance 共鸣强度
    public final double expectationRaw;   // 期待: experience_scoring 加权均值
    public final double pressureRaw;      // 压力: 外部积压归一化

    // ===== 六维 z-score =====
    public final double confidenceZ;
    public final double surpriseZ;
    public final double dissonanceZ;
    public final double plausibilityZ;
    public final double expectationZ;
    public final double pressureZ;

    // ===== 六维偏离方向 =====
    public final DeviationLevel confidenceLvl;
    public final DeviationLevel surpriseLvl;
    public final DeviationLevel dissonanceLvl;
    public final DeviationLevel plausibilityLvl;
    public final DeviationLevel expectationLvl;
    public final DeviationLevel pressureLvl;

    // ===== 附带元数据 =====
    public final int mutualExclusionCount;
    public final int poolExternalCount;
    public final int poolTotalCount;
    public final double avgExperienceScore;

    DemandState(double confidenceRaw, double surpriseRaw, double dissonanceRaw,
                double plausibilityRaw, double expectationRaw, double pressureRaw,
                double confidenceZ, double surpriseZ, double dissonanceZ,
                double plausibilityZ, double expectationZ, double pressureZ,
                DeviationLevel confidenceLvl, DeviationLevel surpriseLvl,
                DeviationLevel dissonanceLvl, DeviationLevel plausibilityLvl,
                DeviationLevel expectationLvl, DeviationLevel pressureLvl,
                int mutualExclusionCount, int poolExternalCount, int poolTotalCount,
                double avgExperienceScore) {
        this.confidenceRaw = confidenceRaw;
        this.surpriseRaw = surpriseRaw;
        this.dissonanceRaw = dissonanceRaw;
        this.plausibilityRaw = plausibilityRaw;
        this.expectationRaw = expectationRaw;
        this.pressureRaw = pressureRaw;
        this.confidenceZ = confidenceZ;
        this.surpriseZ = surpriseZ;
        this.dissonanceZ = dissonanceZ;
        this.plausibilityZ = plausibilityZ;
        this.expectationZ = expectationZ;
        this.pressureZ = pressureZ;
        this.confidenceLvl = confidenceLvl;
        this.surpriseLvl = surpriseLvl;
        this.dissonanceLvl = dissonanceLvl;
        this.plausibilityLvl = plausibilityLvl;
        this.expectationLvl = expectationLvl;
        this.pressureLvl = pressureLvl;
        this.mutualExclusionCount = mutualExclusionCount;
        this.poolExternalCount = poolExternalCount;
        this.poolTotalCount = poolTotalCount;
        this.avgExperienceScore = avgExperienceScore;
    }

    /** 是否所有维度都在正常范围 */
    public boolean isAllNormal() {
        return confidenceLvl == DeviationLevel.NORMAL
            && surpriseLvl == DeviationLevel.NORMAL
            && dissonanceLvl == DeviationLevel.NORMAL
            && plausibilityLvl == DeviationLevel.NORMAL
            && expectationLvl == DeviationLevel.NORMAL
            && pressureLvl == DeviationLevel.NORMAL;
    }

    /** 偏离的维度数量（不含 NORMAL） */
    public int deviatingCount() {
        int n = 0;
        if (confidenceLvl != DeviationLevel.NORMAL) n++;
        if (surpriseLvl != DeviationLevel.NORMAL) n++;
        if (dissonanceLvl != DeviationLevel.NORMAL) n++;
        if (plausibilityLvl != DeviationLevel.NORMAL) n++;
        if (expectationLvl != DeviationLevel.NORMAL) n++;
        if (pressureLvl != DeviationLevel.NORMAL) n++;
        return n;
    }
}
