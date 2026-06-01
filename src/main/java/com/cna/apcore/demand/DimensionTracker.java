package com.cna.apcore.demand;

/**
 * 单维度 EMA 基线追踪器。
 *
 * 维护该维度的指数移动平均 (EMA) 和指数移动方差，
 * 用于判断当前值相对于 agent 自身历史基线的偏离程度。
 *
 * 基线来自 agent 的物质实践——在具体环境中跑得越久，基线越反映该环境的统计规律。
 * 同一套代码在不同环境下会自动校准出不同的"正常"标准。
 */
public class DimensionTracker {

    private final double alpha;   // EMA 平滑系数
    private double ema = Double.NaN;
    private double emaVar = Double.NaN;
    private int sampleCount = 0;

    public DimensionTracker(double alpha) {
        this.alpha = alpha;
    }

    public DimensionTracker() {
        this(0.05);
    }

    /**
     * 喂入一个新样本，更新 EMA 基线。
     */
    public void feed(double value) {
        sampleCount++;
        if (Double.isNaN(ema)) {
            ema = value;
            emaVar = 0.0;
        } else {
            double diff = value - ema;
            ema += alpha * diff;
            emaVar = (1.0 - alpha) * (emaVar + alpha * diff * diff);
        }
    }

    /**
     * 计算当前值相对于历史基线的 z-score。
     * z=0 表示完全处于基线中心，
     * z>0 表示高于基线，z<0 表示低于基线。
     */
    public double zScore(double value) {
        if (Double.isNaN(ema) || emaVar <= 0.0 || sampleCount < 3) {
            return 0.0; // 样本不足，不判断偏离
        }
        double std = Math.sqrt(emaVar);
        if (std <= 0.0) return 0.0;
        return (value - ema) / std;
    }

    /**
     * 将 z-score 翻译为偏离等级。
     */
    public DeviationLevel describe(double value) {
        double z = zScore(value);
        if (z > 1.5)  return DeviationLevel.VERY_HIGH;
        if (z > 0.5)  return DeviationLevel.HIGH;
        if (z < -1.5) return DeviationLevel.VERY_LOW;
        if (z < -0.5) return DeviationLevel.LOW;
        return DeviationLevel.NORMAL;
    }

    // ── 查询 ──

    public double getEma() { return ema; }
    public double getEmaVar() { return emaVar; }
    public int getSampleCount() { return sampleCount; }
}
