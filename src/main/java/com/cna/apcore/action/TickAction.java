package com.cna.apcore.action;

import com.cna.apcore.model.CognitivePrepareUnit;

/**
 * 系统级周期性检查动作的统一接口。
 *
 * 每个 TickAction 代表一个系统在每次认知 tick 中可能触发的检查任务。
 * 所有 tick action 共享 source ID "system:tick"，由 TickActionManager 保证
 * 池中同时最多只有一个 tick action 单元（新触发替换旧的）。
 *
 * 实现类只需关心"何时触发"和"生成什么内容"，
 * 单例约束和池管理由 TickActionManager 统一负责。
 *
 * <p>使用示例：
 * <pre>{@code
 * public class FileSurveyTickAction implements TickAction {
 *     public String getActionType() { return "file_survey"; }
 *     public int getIntervalTicks() { return 8; }
 *     public boolean isReady(int poolSize, int tick) { return poolSize <= 2; }
 *     public CognitivePrepareUnit generate(int tick) {
 *         // 构建目录快照文本...
 *         return CognitivePrepareUnit.create(snapshot, List.of("system:tick"), 0.6);
 *     }
 * }
 * }</pre>
 *
 * @see TickActionManager
 */
public interface TickAction {

    /** 去重和注册用的 source ID，所有 tick action 共享 */
    String TICK_SOURCE_ID = "system:tick";

    /**
     * 唯一动作类型标识符。
     * 用于 manager 的注册和 lastFireTick 追踪。
     * 例如 "file_survey"、"self_check"。
     */
    String getActionType();

    /**
     * 两次触发之间的最小 tick 间隔。
     * manager 在间隔不足时直接跳过，不调用 isReady 或 generate。
     */
    int getIntervalTicks();

    /**
     * 额外就绪条件检查（已通过间隔检查后调用）。
     * 默认永远就绪。子类可覆盖以添加条件，例如：
     * FileSurveyTickAction 要求池空闲（poolSize <= threshold）。
     *
     * @param poolSize    当前准备池大小
     * @param currentTick 当前全局 tick 编号
     * @return true 表示此动作本轮应触发
     */
    default boolean isReady(int poolSize, int currentTick) {
        return true;
    }

    /**
     * 生成要推入准备池的 CognitivePrepareUnit。
     * 仅在间隔检查和 isReady 都通过后由 manager 调用。
     *
     * 生成的单元应：
     * - 使用 sourceIds = List.of(TickAction.TICK_SOURCE_ID)
     * - 调用 setEndogenous(true)
     * - 设置合理的 stimulateEnergy
     *
     * @param currentTick 当前全局 tick 编号
     * @return 要推入池的单元，返回 null 则跳过本轮
     */
    CognitivePrepareUnit generate(int currentTick);
}
