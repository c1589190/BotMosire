package com.cna.agent.AgentTask;

@Deprecated
public class ScheduledTask extends AbstractAgentTask {

    private final String taskText;

    public ScheduledTask() {
        super();
        this.priority = 2;
        this.taskText = "";
    }

    @Override
    public String getTaskText() {
        return this.taskText;
    }
}