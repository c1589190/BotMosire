package com.cna.apcore.demand;

/**
 * 偏离等级：维度当前值相对于 EMA 基线的偏离方向。
 */
public enum DeviationLevel {
    /** 显著高于基线 (z > 1.5) */
    VERY_HIGH,
    /** 高于基线 (0.5 < z ≤ 1.5) */
    HIGH,
    /** 正常范围 (|z| ≤ 0.5) */
    NORMAL,
    /** 低于基线 (-1.5 ≤ z < -0.5) */
    LOW,
    /** 显著低于基线 (z < -1.5) */
    VERY_LOW
}
