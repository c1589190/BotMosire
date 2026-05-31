package com.cna.apcore.attention;

import com.cna.apcore.MentalStateLogger;
import com.cna.apcore.config.CoreConfig;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * 语义疲劳管理器 — 基于 embedding 相似度的认知疲劳引擎。
 *
 * 核心思路：
 * 每轮处理完一个 CognitiveAction 后，将其 actionText embedding 记入近期历史缓冲。
 * 在后续选择时，每个准备单元与历史缓冲中的所有条目计算余弦相似度，
 * 按时间衰减加权平均得到 unitFatigue。
 *
 * unitFatigue 高 → 说明"刚处理过类似内容" → 选择时受到压制
 * unitFatigue 低 → 说明"这是新鲜话题" → 选择时不受影响
 *
 * 由此自然涌现出：
 * - 外部消息（通常新话题）天然低疲劳 → 优先被选中
 * - 内源任务如果跟刚处理的内容同话题 → 被暂时压制，等疲劳消退后再执行
 * - Agent 不会死磕一个话题，表现出自然的认知多样性
 */
@Slf4j
public class FatigueManager {

    // ── 配置 ──
    private final int maxHistory;
    private final double decayRate;
    private final double sensitivity;
    private final int maxAgeTicks;

    // ── 状态 ──
    private final List<FatigueEntry> history;

    /** 一条近期处理记录 */
    private static class FatigueEntry {
        final double[] embedding;
        final int processedAtTick;

        FatigueEntry(double[] embedding, int processedAtTick) {
            this.embedding = embedding;
            this.processedAtTick = processedAtTick;
        }
    }

    public FatigueManager() {
        this.maxHistory = CoreConfig.FATIGUE_MAX_HISTORY;
        this.decayRate = CoreConfig.FATIGUE_DECAY_RATE;
        this.sensitivity = CoreConfig.FATIGUE_SENSITIVITY;
        this.maxAgeTicks = CoreConfig.FATIGUE_MAX_AGE_TICKS;
        this.history = new ArrayList<>();
        log.info("[Fatigue] 初始化: maxHistory={}, decayRate={}, sensitivity={}, maxAgeTicks={}",
                maxHistory, decayRate, sensitivity, maxAgeTicks);
    }

    /**
     * 计算一个单元相对于近期处理历史的语义疲劳值。
     *
     * @param unitEmbedding 单元的文本 embedding
     * @param currentTick   当前 tick 编号
     * @return 疲劳值 [0.0, 1.0]，0 = 完全新鲜，1 = 与最近处理的完全一致
     */
    public double computeFatigue(double[] unitEmbedding, int currentTick) {
        if (unitEmbedding == null || unitEmbedding.length == 0) return 0.0;
        if (history.isEmpty()) return 0.0;

        double weightedSim = 0.0;
        double totalWeight = 0.0;

        for (FatigueEntry entry : history) {
            int age = currentTick - entry.processedAtTick;
            if (age < 0) continue; // 防御性检查

            double sim = cosineSimilarity(unitEmbedding, entry.embedding);
            // 指数衰减权重：越近的记录贡献越大
            double weight = Math.exp(-age * decayRate);

            weightedSim += sim * weight;
            totalWeight += weight;
        }

        if (totalWeight <= 0) return 0.0;

        double fatigue = weightedSim / totalWeight;
        fatigue = Math.min(1.0, Math.max(0.0, fatigue));

        // ★ 心智日志：疲劳计算
        double topSim = history.stream()
                .mapToDouble(e -> cosineSimilarity(unitEmbedding, e.embedding))
                .max().orElse(0.0);
        MentalStateLogger.getInstance().fatigueComputed(
                "unit", fatigue, history.size(), topSim, currentTick);

        return fatigue;
    }

    /**
     * 获得当前疲劳值对应的选择得分惩罚因子。
     * 对调用方暴露，方便在 selectionScore 和 computeAttractiveness 中复用。
     *
     * @param fatigue 单元疲劳值 [0, 1]
     * @return 惩罚因子 (0, 1]，1 = 无惩罚
     */
    public double penaltyFactor(double fatigue) {
        if (fatigue <= 0.0) return 1.0;
        return 1.0 / (1.0 + fatigue * sensitivity);
    }

    /**
     * 记录一次已处理的 action embedding。
     * 环形缓冲：超出上限时移除最旧的条目。
     */
    public void record(double[] embedding, int currentTick) {
        if (embedding == null || embedding.length == 0) return;

        if (history.size() >= maxHistory) {
            history.remove(0); // 移除最旧
        }
        history.add(new FatigueEntry(embedding, currentTick));
        log.debug("[Fatigue] 记录历史 #{}: dims={}, 当前缓冲 {} 条",
                history.size(), embedding.length, history.size());

        MentalStateLogger.getInstance().fatigueRecorded(history.size(), currentTick);
    }

    /**
     * 每 tick 调用：清理过于陈旧的条目。
     * 超过 maxAgeTicks 的条目不再对疲劳计算有实质贡献（权重接近 0），直接移除。
     */
    public void tick(int currentTick) {
        int before = history.size();
        history.removeIf(e -> currentTick - e.processedAtTick > maxAgeTicks);
        int after = history.size();
        if (before != after) {
            log.debug("[Fatigue] tick: 清理 {} 条过期记录，剩余 {}", before - after, after);
            MentalStateLogger.getInstance().fatiguePruned(before - after, after);
        }
    }

    /** 获取当前历史缓冲大小（调试用） */
    public int getHistorySize() {
        return history.size();
    }

    /** 强制清空历史（重置时使用） */
    public void reset() {
        history.clear();
        log.info("[Fatigue] 已重置");
    }

    // ==========================================
    // 工具方法
    // ==========================================

    /** 余弦相似度（内联，避免跨模块依赖） */
    private static double cosineSimilarity(double[] a, double[] b) {
        if (a == null || b == null || a.length != b.length) return 0.0;
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        double denom = Math.sqrt(na) * Math.sqrt(nb);
        return denom == 0 ? 0.0 : dot / denom;
    }
}
