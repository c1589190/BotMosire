package com.cna.agent.AgentTask;

import java.util.UUID;

public class UpdateThoughtsTask implements DefaultAgentTaskUnit {

    // 唯一标识符，防止任务队列里的任务混淆
    private final UUID uuid;

    // 固定的系统反思指令
    private final String taskText;

    public UpdateThoughtsTask() {
        // 实例化时自动分配一个唯一的 UUID
        this.uuid = UUID.randomUUID();

        // 核心的系统强制唤醒指令
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