package com.cna.apcore.model;

import lombok.Builder;
import lombok.Getter;

/**
 * UE（理解能量）计算中涉及的单个感觉节点记录。
 * 记录该节点在 BFS 搜索中位于第几层、获得多少层衰减权重、以及当时的新颖程度。
 */
@Getter
@Builder
public class UEUnit {
    /** 感觉维度 ID（来自 FeelingsDB 或旧 Feeling_Dimensions） */
    private final int dimId;

    /** 感觉概念文本 */
    private final String concept;

    /** BFS 搜索时该节点位于第几层（0 = 种子节点） */
    private final int bfsLayer;

    /** 层衰减权重 = decayFactor ^ bfsLayer */
    private final double layerWeight;

    /** 该节点在被搜到时的 novelty 曲线权重 */
    private final double noveltyWeight;

    /** 该节点的 embedding 向量（用于后续 CognitiveFamiliarity 计算） */
    private final double[] embedding;

    @Override
    public String toString() {
        return String.format("UEUnit{dim=%d, concept='%s', layer=%d, layerW=%.3f, noveltyW=%.3f}",
                dimId, concept, bfsLayer, layerWeight, noveltyWeight);
    }
}
