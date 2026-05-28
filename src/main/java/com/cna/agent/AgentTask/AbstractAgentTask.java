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

    // 任务专属感觉维度，在 pushTask 时计算，随任务队列展示
    protected String taskFeelings = null;

    // 任务创建时间戳（毫秒），保留兼容
    protected final long createTime;

    // 任务被推入队列时的认知循环轮次
    protected int bornAtLoop = 0;

    // 展示用编号，LLM 可据此引用特定任务，-1 表示未分配
    protected int displayId = -1;

    // 本任务已激活的工具分组。仅被持有本任务的消费者线程访问，无需同步。
    protected java.util.Set<String> activatedToolGroups = new java.util.HashSet<>();

    public AbstractAgentTask() {
        this.uuid = UUID.randomUUID();
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
    public void setPriority(double priority) {
        this.priority = priority;
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
    public String getTaskFeelings() {
        return this.taskFeelings;
    }

    @Override
    public void setTaskFeelings(String feelings) {
        this.taskFeelings = feelings;
    }

    @Override
    public long getCreateTime() {
        return this.createTime;
    }

    @Override
    public int getBornAtLoop() {
        return this.bornAtLoop;
    }

    @Override
    public void setBornAtLoop(int loop) {
        this.bornAtLoop = loop;
    }

    @Override
    public int getDisplayId() {
        return this.displayId;
    }

    @Override
    public void setDisplayId(int id) {
        this.displayId = id;
    }

    @Override
    public java.util.Set<String> getActivatedToolGroups() {
        return this.activatedToolGroups;
    }

    @Override
    public void setActivatedToolGroups(java.util.Set<String> groups) {
        this.activatedToolGroups = groups;
    }

    // 注意：getTaskText() 保持抽象，强制要求具体的子类去实现它
    // getPriority() 默认走接口里的 default 3，子类有需要可以自行 Override
}
