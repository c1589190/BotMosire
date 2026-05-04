package com.cna.agent.AgentTask;

import java.util.UUID;

public interface DefaultAgentTaskUnit {
    UUID getUUID();
    String getTaskText();
    default int getPriority(){
        return 3;
    }; // 可选：为任务添加优先级，数值越小优先级越高
}
