package com.cna.agent.AgentTask;

import java.util.UUID;

public class ConsoleChatTask implements DefaultAgentTaskUnit {
    private final UUID uuid;
    private final String context;

    public ConsoleChatTask(String context) {
        this.uuid = UUID.randomUUID();
        this.context = context;
    }

    @Override
    public UUID getUUID() {
        return this.uuid; // 修复：之前这里返回了 null
    }

    @Override
    public String getTaskText() {
        return "来自部署者后台终端的直接消息:{\n" + context + "\n}";
    }
    @Override
    public int getPriority(){
        return 1;
    }
}