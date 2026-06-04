package com.cna.apcore.interoception;

import com.cna.apcore.demand.DeviationLevel;

/**
 * 一条「内感受读数」—— 某个认知子系统当前相对自身基线的偏离。
 *
 * 刻意与具体子系统解耦：DemandManager 的六维、AttentionManager 的能量、
 * FatigueManager 的疲劳、FeelingsManager 的谐振/违和……都先归约成这一个统一形状，
 * 再交给 {@link InteroceptionNarrator} 收口。这样「内感受」就有了单一入口，
 * 不再是每个子系统各自往 prompt 塞一段。
 *
 * @param dim     维度名（用于查体感词典），如 "confidence" / "pressure" / "fatigue"
 * @param level   偏离方向与强度（复用 demand 的 z-score 分级）
 * @param z       原始 z-score，绝对值越大 = 越偏离基线 = 越该被「感觉到」。用于排序选主导感受。
 */
public record InteroceptionSignal(String dim, DeviationLevel level, double z) {

    /** 这条读数有多「响」—— 偏离基线的绝对程度，决定它能否挤进意识。 */
    public double salience() {
        return Math.abs(z);
    }

    /** 是否偏离常态（NORMAL 不进入叙事）。 */
    public boolean isDeviating() {
        return level != DeviationLevel.NORMAL;
    }
}
