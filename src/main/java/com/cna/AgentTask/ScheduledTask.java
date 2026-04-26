package com.cna.AgentTask;

import java.util.UUID;

public class ScheduledTask implements DefaultAgentTaskUnit {
    private final UUID uuid;

    // 固定的系统反思指令
    private final String taskText;

    public ScheduledTask(){
        this.uuid = UUID.randomUUID();
        this.taskText = "";
    }

    @Override
    public UUID getUUID() {
        return this.uuid;
    }

    @Override
    public String getTaskText() {
        return this.taskText;
    }
}
