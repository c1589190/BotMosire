package com.cna.apcore.attention;

import com.cna.apcore.MentalStateLogger;
import com.cna.apcore.config.CoreConfig;
import com.cna.apcore.model.UEUnit;
import lombok.extern.slf4j.Slf4j;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 语义疲劳管理器 — 基于感觉维度粒度的认知疲劳引擎。
 *
 * 核心思路：
 * 不再对整段 actionText 做 embedding 相似度比较，而是追踪每个感觉维度（Feeling Dimension）
 * 最近一次被处理是在哪个 tick。当新的准备单元到来时，根据其匹配到的感觉节点（UEUnit 列表），
 * 逐一查询每个维度的近期处理时间，按时间衰减加权平均得到 unitFatigue。
 *
 * unitFatigue 高 → "这个单元涉及的感觉最近刚处理过" → 选择时受到压制
 * unitFatigue 低 → "这个单元涉及的感觉近期没碰过，新鲜" → 选择时不受影响
 *
 * 由此自然涌现出：
 * - 外部消息（通常触发新感觉）天然低疲劳 → 优先被选中
 * - 内源任务如果涉及刚处理过的感觉维度 → 被暂时压制，等疲劳消退后再执行
 * - Agent 不会死磕一个话题，表现出自然的认知多样性
 *
 * 与新鲜度/关注度的关系：
 * - 疲劳度（此处）：临时、内存态，按感觉维度追踪近期被处理的频次
 * - 新鲜度（noveltyCurve）：永久、DB 态，由 FeelingsDB.activationCount 驱动
 * - 关注度（attentionAttitude）：永久、DB 态，由 FeelingsDB.attentionAttitude 驱动
 *   三者各自独立计算，在 selectionScore 中共同作用。
 */
@Slf4j
public class FatigueManager {

    // ── 配置 ──
    private final double decayRate;
    private final double sensitivity;
    private final int maxAgeTicks;

    // ── 状态：感觉维度 → 最近一次被处理的 tick（纯内存，不持久化）──
    private final Map<Integer, Integer> dimLastProcessedTick;

    public FatigueManager() {
        this.decayRate = CoreConfig.FATIGUE_DECAY_RATE;
        this.sensitivity = CoreConfig.FATIGUE_SENSITIVITY;
        this.maxAgeTicks = CoreConfig.FATIGUE_MAX_AGE_TICKS;
        this.dimLastProcessedTick = new ConcurrentHashMap<>();
        log.info("[Fatigue] 初始化（感觉维度粒度）: decayRate={}, sensitivity={}, maxAgeTicks={}",
                decayRate, sensitivity, maxAgeTicks);
    }

    /**
     * 计算一个单元相对于近期处理历史的语义疲劳值。
     *
     * 对于单元的每个 UEUnit（感觉节点），查询该感觉维度最近被处理的 tick，
     * 按时间衰减加权后得到该维度的疲劳贡献。最终疲劳 = Σ(regencyWeight × ueWeight) / Σ(ueWeight)。
     *
     * 某个维度从未被处理过 → 对该维度贡献 0 疲劳（全新的感觉方向）。
     *
     * @param ueUnits     该单元匹配到的所有感觉节点（UE 计算结果）
     * @param currentTick 当前 tick 编号
     * @return 疲劳值 [0.0, 1.0]，0 = 完全新鲜，1 = 所有感觉维度都刚刚被密集处理过
     */
    public double computeFatigue(List<UEUnit> ueUnits, int currentTick) {
        if (ueUnits == null || ueUnits.isEmpty()) return 0.0;
        if (dimLastProcessedTick.isEmpty()) return 0.0;

        double weightedFatigue = 0.0;
        double totalWeight = 0.0;
        int matchedDims = 0;
        double maxRecency = 0.0;

        for (UEUnit u : ueUnits) {
            double weight = u.getNoveltyWeight() * u.getLayerWeight();
            totalWeight += weight;

            Integer lastTick = dimLastProcessedTick.get(u.getDimId());
            if (lastTick != null) {
                int age = currentTick - lastTick;
                if (age >= 0) {
                    // 指数衰减：越近处理过 → recency 越大 → 疲劳越高
                    double recency = Math.exp(-age * decayRate);
                    weightedFatigue += recency * weight;
                    matchedDims++;
                    if (recency > maxRecency) maxRecency = recency;
                }
            }
            // dim 不在历史中 → 该维度贡献 0 疲劳（未被处理过的新方向）
        }

        if (totalWeight <= 0) return 0.0;

        double fatigue = weightedFatigue / totalWeight;
        fatigue = Math.min(1.0, Math.max(0.0, fatigue));

        // ★ 心智日志：疲劳计算
        MentalStateLogger.getInstance().fatigueComputed(
                "unit", fatigue, dimLastProcessedTick.size(),
                matchedDims, ueUnits.size(), maxRecency, currentTick);

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
     * 记录一次已处理 action 涉及的所有感觉维度。
     * 每个维度的 lastProcessedTick 更新为当前 tick。
     *
     * @param ueUnits     已处理 action 关联的所有感觉节点
     * @param currentTick 当前 tick 编号
     */
    public void record(List<UEUnit> ueUnits, int currentTick) {
        if (ueUnits == null || ueUnits.isEmpty()) return;

        int recorded = 0;
        for (UEUnit u : ueUnits) {
            Integer prev = dimLastProcessedTick.put(u.getDimId(), currentTick);
            if (prev == null) recorded++; // 新出现的维度
        }

        log.debug("[Fatigue] 记录 {} 个感觉维度 (其中 {} 个首次出现)，当前追踪 {} 维",
                ueUnits.size(), recorded, dimLastProcessedTick.size());

        MentalStateLogger.getInstance().fatigueRecorded(
                ueUnits.size(), recorded, dimLastProcessedTick.size(), currentTick);
    }

    /**
     * 每 tick 调用：清理过于陈旧的维度记录。
     * 超过 maxAgeTicks 的维度对疲劳计算贡献接近 0，直接移除以控制内存。
     */
    public void tick(int currentTick) {
        int before = dimLastProcessedTick.size();
        Iterator<Map.Entry<Integer, Integer>> it = dimLastProcessedTick.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Integer> e = it.next();
            if (currentTick - e.getValue() > maxAgeTicks) {
                it.remove();
            }
        }
        int after = dimLastProcessedTick.size();
        if (before != after) {
            log.debug("[Fatigue] tick: 清理 {} 个过期维度，剩余 {}", before - after, after);
            MentalStateLogger.getInstance().fatiguePruned(before - after, after);
        }
    }

    /** 获取当前追踪的感觉维度数量（调试用） */
    public int getHistorySize() {
        return dimLastProcessedTick.size();
    }

    /** 强制清空所有维度记录（重置时使用） */
    public void reset() {
        dimLastProcessedTick.clear();
        log.info("[Fatigue] 已重置");
    }
}
