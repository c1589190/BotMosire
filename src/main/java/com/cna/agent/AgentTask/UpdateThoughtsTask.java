package com.cna.agent.AgentTask;

@Deprecated
public class UpdateThoughtsTask extends AbstractAgentTask {

    private final String taskText;

    public UpdateThoughtsTask() {
        super();
        this.priority = 2;
        this.taskText = "";
    }

    @Override
    public String getTaskText() {
        return this.taskText;
    }

}