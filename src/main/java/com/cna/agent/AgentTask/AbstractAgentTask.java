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

    // 仅在任务首次创建时计算并缓存的感觉快照，后续轮次复用，避免每轮重新计算破坏缓存前缀
    protected String initialFeelings = null;

    // 任务创建时间戳（毫秒），用于判断挂起过期
    protected final long createTime;

    public AbstractAgentTask() {
        // 实例化时自动分配唯一 UUID
        this.uuid = UUID.randomUUID();
        // 记录任务创建时间
        this.createTime = System.currentTimeMillis();
    }

    @Override
    public UUID getUUID() { return this.uuid; }

    @Override
    public String getTaskName(){
        return this.getClass().getSimpleName();
    }

    @Override
    public int getCurrentTurn() { return this.currentTurn; }

    @Override
    public void setCurrentTurn(int turn) { this.currentTurn = turn; }

    @Override
    public String getTurnsAddition() { return this.turnsAddition; }

    @Override
    public void setTurnsAddition(String addition) { this.turnsAddition = addition; }

    double priority = 3.0;

    // 标记该任务是否曾经开始执行过（turn > 1），用于防止同优先级饥饿
    protected boolean inProgress = false;

    @Override
    public double getPriority(){
        return this.priority;
    }

    @Override
    public boolean isInProgress() {
        return this.inProgress;
    }

    @Override
    public void markInProgress() {
        this.inProgress = true;
    }

    @Override
    public String getInitialFeelings() {
        return this.initialFeelings;
    }

    @Override
    public void setInitialFeelings(String feelings) {
        this.initialFeelings = feelings;
    }

    @Override
    public long getCreateTime() {
        return this.createTime;
    }

    // 注意：getTaskText() 保持抽象，强制要求具体的子类去实现它
    // getPriority() 默认走接口里的 default 3，子类有需要可以自行 Override
}
