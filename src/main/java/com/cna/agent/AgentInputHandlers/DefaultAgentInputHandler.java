package com.cna.agent.AgentInputHandlers;

import com.cna.agent.AgentInput.DefaultAgentInputUnit;
import com.cna.agent.LivingLoop;

import java.util.List;

/**
 * 将 Input (感知) 转化为 Task (潜意识任务) 的发生器接口
 */
public interface DefaultAgentInputHandler {

    /**
     * 声明本处理器负责哪种类型的 Input
     */
    Class<? extends DefaultAgentInputUnit> getSupportedInputClass();

    /**
     * 阶段 1 & 2：处理新到来的感知输入
     * (比如在这里调用小模型 Gatekeeper 过滤垃圾信息，或者存入预备池)
     * @param inputs 属于该类型的新鲜 Input 列表
     * @param engine 主引擎引用，方便调用小模型
     */
    void handleInputs(List<DefaultAgentInputUnit> inputs, LivingLoop engine);

    /**
     * 阶段 3：周期性心跳与催熟
     * (由主引擎每隔一个认知周期调用一次。用来检查预备池里的任务是否“熟透”，并推入执行总线)
     * @param engine 主引擎引用，用于调用 engine.pushTask(...)
     */
    void tick(LivingLoop engine);
}