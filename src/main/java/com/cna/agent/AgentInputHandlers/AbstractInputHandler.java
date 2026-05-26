package com.cna.agent.AgentInputHandlers;

import com.cna.agent.AgentInput.DefaultAgentInputUnit;
import com.cna.agent.LivingLoop;
import com.cna.config.ConfigsManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 通用的 Input 处理器抽象基类
 * @param <T> 该处理器专门负责的 Input 具体类型
 */
public abstract class AbstractInputHandler<T extends DefaultAgentInputUnit> implements DefaultAgentInputHandlerUnit {

    private final Class<T> supportedInputClass;
    protected final LivingLoop engine; // 挂载总线引用

    public AbstractInputHandler(Class<T> supportedInputClass, LivingLoop engine) {
        this.supportedInputClass = supportedInputClass;
        this.engine = engine;
    }

    @Override
    public Class<? extends DefaultAgentInputUnit> getSupportedInputClass() {
        return supportedInputClass;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void handleInputs(List<DefaultAgentInputUnit> inputs) {

        // 累加 heat，但 clamp 到 MAX_COGNITIVE_HEAT 上限，避免短时累计超过认知循环 8 秒 decay 窗口
        AtomicInteger heat = this.engine.getCognitiveHeat();
        heat.set(Math.min(ConfigsManager.MAX_COGNITIVE_HEAT, heat.get() + 1));

        if (inputs == null || inputs.isEmpty()) return;

        List<T> typedInputs = new ArrayList<>();
        // 顶层统一进行安全向下转型，解放子类
        for (DefaultAgentInputUnit input : inputs) {
            if (supportedInputClass.isInstance(input)) {
                typedInputs.add((T) input);
            }
        }

        if (!typedInputs.isEmpty()) {
            processInputs(typedInputs);
        }
    }

    /**
     * 子类需实现的核心方法，此时传进来的已经是强转好的泛型 T 列表
     */
    protected abstract void processInputs(List<T> inputs);

    /**
     * 默认的 tick 实现为空。如果子类不需要周期性操作，就不必强制重写此方法了。
     */
    @Override
    public void tick() {
        // 留空，供有需要的子类覆盖
    }

    /**
     * 【核心认知架构】：获取动态感觉阈值
     * 基于基准线与当前系统压力值(Cognitive Heat)进行惩罚运算
     */
    protected double getDynamicThreshold(double baseThreshold) {
        AtomicInteger currentHeat = this.engine.getCognitiveHeat();
        double penaltyFactor = ConfigsManager.FD_PRESSURE_PENALTY;
        return baseThreshold + (currentHeat.get() * penaltyFactor);
    }
}