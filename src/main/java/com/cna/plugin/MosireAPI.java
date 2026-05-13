package com.cna.plugin;

import com.cna.agent.AgentInput.DefaultAgentInputUnit;
import com.cna.agent.AgentTask.DefaultAgentTaskUnit;
import com.cna.agent.AgentTool.DefaultAgentToolUnit;
import com.cna.agent.AgentTasksHandlers.DefaultAgentTaskHandler;
import com.cna.agent.AgentInputHandlers.DefaultAgentInputHandlerUnit;
import org.slf4j.Logger;

public interface MosireAPI {
    // 1. 注册扩展能力
    void registerTool(DefaultAgentToolUnit tool);
    void registerTaskHandler(DefaultAgentTaskHandler handler);
    void registerInputHandler(DefaultAgentInputHandlerUnit handler);

    // 2. 交互与推送
    void pushInput(DefaultAgentInputUnit input);
    void pushTask(DefaultAgentTaskUnit task);

    // 3. 提供统一日志接口
    Logger getLogger();
}