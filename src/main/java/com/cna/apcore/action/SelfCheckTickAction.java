package com.cna.apcore.action;

import com.cna.apcore.config.CoreConfig;
import com.cna.apcore.model.CognitivePrepareUnit;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 认知自检 TickAction — Agent 的"定期反思"。
 *
 * 每隔若干 tick 生成一个自我检查单元，提示 LLM 反思当前的
 * 认知状态、待办事项、关注方向，防止 Agent 陷入机械重复或
 * 忽略长期目标。
 *
 * 该 action 不需要池空闲条件，到了间隔就直接触发。
 * 生成的内容以"反思提示"形式呈现，不强制要求执行具体任务。
 */
@Slf4j
public class SelfCheckTickAction implements TickAction {

    private static final String ACTION_TYPE = "self_check";

    private final int intervalTicks;
    private final double baseSE;
    private final boolean requireIdle;
    private final int idleThreshold;

    public SelfCheckTickAction() {
        this.intervalTicks = CoreConfig.TICK_SELFCHECK_INTERVAL_TICKS;
        this.baseSE = CoreConfig.TICK_SELFCHECK_BASE_SE;
        this.requireIdle = CoreConfig.TICK_SELFCHECK_REQUIRE_IDLE;
        this.idleThreshold = CoreConfig.TICK_SELFCHECK_IDLE_THRESHOLD;

        log.info("[TickAction:{}] 初始化: interval={}ticks (≈{}s), baseSE={}, requireIdle={}, idleThreshold={}",
                ACTION_TYPE, intervalTicks, intervalTicks * 2L, baseSE, requireIdle, idleThreshold);
    }

    @Override
    public String getActionType() {
        return ACTION_TYPE;
    }

    @Override
    public int getIntervalTicks() {
        return intervalTicks;
    }

    /** 当 selfCheckRequireIdle=true 时，仅在池空闲时触发；false 则始终触发 */
    @Override
    public boolean isReady(int poolSize, int currentTick) {
        if (requireIdle) {
            return poolSize <= idleThreshold;
        }
        return true;
    }

    @Override
    public CognitivePrepareUnit generate(int currentTick) {
        String prompt = """
                【系统自检】
                请花一点时间反思你当前的认知状态：

                - 你最近主要在关注哪些话题？有被忽略的重要方向吗？
                - 是否有尚未完成的任务需要跟进（通过 next_actions 标记）？
                - 你当前的"情绪倾向"如何？需要主动调整关注方向吗？
                - 有哪些新出现的、值得进一步探索的事情？

                这是一次例行的自我检查，不需要执行具体任务，
                但如果你发现了值得关注的方向或者遗漏的事情，
                可以通过 next_actions 将它们标记为待办任务。
                """;

        CognitivePrepareUnit unit = CognitivePrepareUnit.create(
                prompt, List.of(TICK_SOURCE_ID), baseSE);
        unit.setEndogenous(true);

        log.debug("[TickAction:{}] 🔍 认知自检单元已生成, tick={}", ACTION_TYPE, currentTick);
        return unit;
    }
}
