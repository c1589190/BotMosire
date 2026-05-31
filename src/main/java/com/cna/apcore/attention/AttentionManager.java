package com.cna.apcore.attention;

import com.cna.apcore.MentalStateLogger;
import com.cna.apcore.config.CoreConfig;
import com.cna.apcore.model.CognitivePrepareUnit;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

/**
 * AP 注意力管理器 — 内源性能量引擎。
 *
 * 注意力是连接"外界刺激"与"内在动机"的桥梁：
 * - 外部输入提供 SE（刺激能量），决定什么"被看到"
 * - 注意力提供 attentionEnergy（内源能量），决定什么"被关注"
 * - 两者加算后共同参与 selectionScore 竞争
 *
 * 核心机制：
 * 1. 注意力池：有限的、每 tick 恢复的能量资源
 * 2. 吸引力评分：每个单元根据意外度/新颖度/CW boost/内源标记等打分
 * 3. 能量分配：每 tick 向 top-N 最具吸引力的单元注入注意力能量
 * 4. 能量衰减：未被持续关注的单元，注意力能量逐步消退
 *
 * 这使得 LLM 通过 next_actions 创建的自生成任务可以逐步积累内源能量，
 * 最终在 selectionScore 竞争中胜过外部输入，实现"主动思考"。
 */
@Slf4j
public class AttentionManager {

    // ── 配置 ──
    private final double poolMax;
    private final double regenPerTick;
    private final int maxAttendUnits;
    private final double decayPerTick;

    // ── 状态 ──
    private double attentionPool;

    public AttentionManager() {
        this.poolMax = CoreConfig.ATTENTION_POOL_MAX;
        this.regenPerTick = CoreConfig.ATTENTION_REGEN_PER_TICK;
        this.maxAttendUnits = CoreConfig.ATTENTION_MAX_ATTEND_UNITS;
        this.decayPerTick = CoreConfig.ATTENTION_DECAY_PER_TICK;
        this.attentionPool = poolMax * 0.5; // 初始半满
        log.info("[Attention] 初始化: poolMax={}, regenPerTick={}, maxAttend={}, decay={}",
                poolMax, regenPerTick, maxAttendUnits, decayPerTick);
    }

    /**
     * 每个 tick 调用一次。
     * 在池的 tickAll()（衰减 CW）之后、selectAndConvert()（选择执行）之前调用。
     *
     * @param units 当前池中所有单元
     */
    public void tick(List<CognitivePrepareUnit> units) {
        if (units.isEmpty()) return;

        // 1. 注意力池恢复
        double prevPool = attentionPool;
        attentionPool = Math.min(poolMax, attentionPool + regenPerTick);
        double regenerated = attentionPool - prevPool;

        // 2. 衰减所有单元的注意力能量（未被持续关注的会消退）
        int decayedCount = 0;
        for (CognitivePrepareUnit unit : units) {
            double before = unit.getAttentionEnergy();
            unit.decayAttentionEnergy(decayPerTick);
            if (before > 0 && unit.getAttentionEnergy() < before) {
                decayedCount++;
            }
        }

        // 3. 吸引力评分
        record ScoredUnit(CognitivePrepareUnit unit, double attractiveness) {}
        List<ScoredUnit> scored = new ArrayList<>();
        for (CognitivePrepareUnit unit : units) {
            double attr = computeAttractiveness(unit);
            if (attr > 0) {
                scored.add(new ScoredUnit(unit, attr));
            }
        }

        if (scored.isEmpty()) {
            if (regenerated > 0.01) {
                log.debug("[Attention] tick: 池恢复 +" + String.format("%.2f", regenerated)
                        + " (pool=" + String.format("%.2f", attentionPool) + "/" + String.format("%.2f", poolMax) + "), 无吸引单元");
            }
            return;
        }

        // 按吸引力降序，取 top-N
        scored.sort((a, b) -> Double.compare(b.attractiveness, a.attractiveness));
        int n = Math.min(maxAttendUnits, scored.size());
        double totalAttr = scored.stream().limit(n).mapToDouble(s -> s.attractiveness).sum();

        if (totalAttr <= 0) return;

        // 4. 按吸引力比例分配注意力能量
        double allocated = 0;
        for (int i = 0; i < n; i++) {
            ScoredUnit s = scored.get(i);
            double share = attentionPool * (s.attractiveness / totalAttr);
            if (share < 0.01) continue; // 太小了不值得分配

            double before = s.unit.getAttentionEnergy();
            s.unit.addAttentionEnergy(share);
            allocated += share;
            attentionPool -= share;

            // ★ 心智日志：单条注意力分配
            MentalStateLogger.getInstance().attentionAllocated(
                    s.unit.getUuid().toString(), share, s.attractiveness,
                    s.unit.getUnitFatigue(), s.unit.isEndogenous());

            if (log.isDebugEnabled()) {
                String label = s.unit.isEndogenous() ? "[内源]" : "[外源]";
                log.debug("[Attention]   → " + label + " " + s.unit.getUuid().toString().substring(0, 8)
                        + ": +" + String.format("%.3f", share)
                        + " (attr=" + String.format("%.3f", s.attractiveness)
                        + ", attnE=" + String.format("%.3f", before) + "→" + String.format("%.3f", s.unit.getAttentionEnergy()) + ")");
            }
        }

        // 5. 汇总日志
        int endogenousAttended = (int) scored.stream().limit(n)
                .filter(s -> s.unit.isEndogenous()).count();
        log.info("[Attention] tick: 恢复 +" + String.format("%.2f", regenerated)
                + ", 分配 " + String.format("%.2f", allocated)
                + " → " + n + "个单元 (" + endogenousAttended + "内源)"
                + ", 池余 " + String.format("%.2f", attentionPool) + "/" + String.format("%.2f", poolMax)
                + ", " + decayedCount + "单元注意力衰减");

        // ★ 心智日志：注意力整体状态
        MentalStateLogger.getInstance().attentionTick(
                regenerated, allocated, attentionPool, n, endogenousAttended, decayedCount);
    }

    /**
     * 计算单元对注意力的吸引力。
     *
     * 吸引力因子：
     * - 基线：每个单元都有被注意的基本可能 (0.05)
     * - 新颖度：UE 越低越新颖，注意力天然倾向探索未知 (0 ~ 0.25)
     * - LLM boost：CW > 1 意味着 LLM 显式标记为重要 (0 ~ 0.30)
     * - 内源任务：自生成任务需要注意力滋养才能成长 (0.15)
     * - tick 紧迫度：等太久还没被选中的单元有一定紧迫感 (0 ~ 0.15)
     */
    private double computeAttractiveness(CognitivePrepareUnit unit) {
        double attr = 0.05; // 基线

        // 新颖度：UE 低 = 感觉图里匹配少 = 新颖，值得探索
        double ue = unit.getUnderstandEnergy();
        if (ue < 0.3) {
            attr += 0.25; // 高度新颖
        } else if (ue < 0.6) {
            attr += 0.15; // 中等新颖
        } else if (ue < 1.0) {
            attr += 0.05; // 略有新颖
        }
        // UE >= 1.0：非常熟悉，不需要额外注意

        // LLM 显式 boost：CW 超过 1 的部分说明 LLM 觉得重要
        double cw = unit.getContinueWeight();
        if (cw > 1.0) {
            attr += Math.min(0.30, (cw - 1.0) * 0.3);
        }

        // ★ 疲劳惩罚：高疲劳单元降低注意力吸引力，注意力倾向新鲜话题
        double fatigue = unit.getUnitFatigue();
        attr *= (1.0 - fatigue * 0.7);

        // tick 紧迫度：等待超过 5 tick 的单元，越等越"急"
        int tick = unit.getTick();
        if (tick > 15) {
            attr += 0.15; // 等很久了，紧迫
        } else if (tick > 10) {
            attr += 0.10;
        } else if (tick > 5) {
            attr += 0.05;
        }

        return attr;
    }

    /** 获取当前注意力池余量（调试用） */
    public double getPoolRemaining() {
        return attentionPool;
    }

    /** 强制清空注意力状态（关闭时调用） */
    public void reset() {
        attentionPool = poolMax * 0.5;
        log.info("[Attention] 已重置");
    }
}
