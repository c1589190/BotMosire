package com.cna.agent.AgentTask;

import java.util.UUID;

public abstract class AbstractAgentTask implements DefaultAgentTaskUnit {

    // 基础标识
    protected final UUID uuid;

    // 状态机流转数据
    protected int currentTurn = 1;
    protected String turnsAddition = "";

    // 高级特性支持（如果在接口中定义了的话）
    protected boolean requireAdvancedModel = false;
    protected String deepMemoriesCache = null;

    public AbstractAgentTask() {
        // 实例化时自动分配唯一 UUID
        this.uuid = UUID.randomUUID();
    }

    @Override
    public UUID getUUID() { return this.uuid; }

    @Override
    public int getCurrentTurn() { return this.currentTurn; }

    @Override
    public void setCurrentTurn(int turn) { this.currentTurn = turn; }

    @Override
    public String getTurnsAddition() { return this.turnsAddition; }

    @Override
    public void setTurnsAddition(String addition) { this.turnsAddition = addition; }

    double priority = 3.0;

    @Override
    public double getPriority(){
        return this.priority;
    }

    // 注意：getTaskText() 保持抽象，强制要求具体的子类去实现它
    // getPriority() 默认走接口里的 default 3，子类有需要可以自行 Override
}