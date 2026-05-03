package com.cna.agent.AgentTasksHandlers;

import com.cna.agent.AgentTask.DefaultAgentTaskUnit;
import com.cna.agent.LivingLoop;
import com.fasterxml.jackson.databind.node.ArrayNode;

public interface DefaultAgentTaskHandler {

    /**
     * 告诉系统，这个 Handler 专门负责处理哪种 Task 类
     */
    Class<? extends DefaultAgentTaskUnit> getSupportedTaskClass();

    /**
     * 执行具体任务的逻辑
     * @param task 当前要处理的任务实例
     * @param engine LivingLoop 引擎实例 (提供执行认知循环、获取模型等核心能力)
     * @param toolsDefinitionArray 当前系统已装配好的所有工具说明书
     */
    void handleTask(DefaultAgentTaskUnit task, LivingLoop engine, ArrayNode toolsDefinitionArray);
}