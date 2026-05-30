package com.cna.apcore.model;

import com.cna.apcore.db.FeelingsDB;
import lombok.Builder;
import lombok.Getter;

/**
 * V4 感觉维度数据模型。
 *
 * 实际权重 = noveltyCurve(activationCount) × accuracy
 * - noveltyCurve: 新颖→高，厌倦→低，永熟悉→中（永不归零）
 * - accuracy: 该感觉对应的经验与新实践的匹配程度（0.0~1.0）
 */
@Getter
@Builder
public class FeelingEntry {
    private final int id;
    private final String concept;
    private final double[] embedding;
    private final int activationCount;
    private final double accuracy;

    /**
     * 实际权重 = noveltyCurve × accuracy。
     * 越精准、越新颖的感觉越值得思考。
     */
    public double actualWeight() {
        return FeelingsDB.noveltyCurve(activationCount) * accuracy;
    }

    @Override
    public String toString() {
        return String.format("Feeling{id=%d, concept='%s', act=%d, acc=%.3f, w=%.4f}",
                id, concept, activationCount, accuracy, actualWeight());
    }
}
