package com.cna.agent.AgentInputHandlers;

import com.cna.agent.AgentInput.DefaultAgentInputUnit;

import java.util.List;

/**
 * 将 Input (感知) 转化为 Task (潜意识任务) 的发生器接口
 */
public interface DefaultAgentInputHandlerUnit {

    /**
     * 声明本处理器负责哪种类型的 Input
     */
    Class<? extends DefaultAgentInputUnit> getSupportedInputClass();

    /**
     * 阶段 1 & 2：处理新到来的感知输入
     * @param inputs 属于该类型的新鲜 Input 列表
     */
    void handleInputs(List<DefaultAgentInputUnit> inputs);

    /**
     * 阶段 3：周期性心跳与催熟
     * (由主引擎每隔一个认知周期调用一次。用来检查预备池里的任务是否“熟透”，并推入执行总线)
     */
    void tick();
}