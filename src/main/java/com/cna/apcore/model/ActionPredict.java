package com.cna.apcore.model;

import lombok.Builder;
import lombok.Getter;

/**
 * 从经验库（ExperiencesDB）中检索到的先验经验，作为本次行动的预测参考。
 */
@Getter
@Builder
public class ActionPredict {
    /** 经验记录 ID（来自 V4_Experiences） */
    private final int experienceId;

    /** 经验文本摘要 */
    private final String expText;

    /** 与当前 ActionText 的 embedding 相似度 */
    private final double similarity;

    /** 当前经验的 HelpfulDegree（-1..1），越大表示该经验越被验证为有用 */
    private final double helpfulDegree;

    /** 关联的感觉维度 ID 列表 */
    private final java.util.List<Integer> feelingDimIds;

    @Override
    public String toString() {
        return String.format("ActionPredict{expId=%d, sim=%.3f, helpful=%.1f, text='%s'}",
                experienceId, similarity, helpfulDegree,
                expText != null && expText.length() > 60 ? expText.substring(0, 60) + "..." : expText);
    }
}
