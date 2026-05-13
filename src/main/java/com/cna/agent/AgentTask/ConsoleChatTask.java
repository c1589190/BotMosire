package com.cna.agent.AgentTask;

public class ConsoleChatTask extends AbstractAgentTask {

    private final String context;

    public ConsoleChatTask(String context) {
        super();
        this.priority = 1;
        this.context = context;
    }

    @Override
    public String getTaskText() {
        return "来自部署者后台终端的直接消息:{\n" + context + "\n}";
    }

}