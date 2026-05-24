package com.cna.agent.AgentTask;

import java.util.UUID;

public interface DefaultAgentTaskUnit {
    UUID getUUID();
    String getTaskText();
    String getTaskName();

    double getPriority();

    // 记录当前执行到了第几轮
    int getCurrentTurn();
    void setCurrentTurn(int turn);

    // 记录已经发生过的思考和工具调用记录
    String getTurnsAddition();
    void setTurnsAddition(String addition);

    // 标记该任务是否曾经开始执行过（turn > 1），用于防止同优先级饥饿
    boolean isInProgress();
    void markInProgress();

    // 感觉维度快照，仅在任务首次创建时计算一次，后续轮次复用
    String getInitialFeelings();
    void setInitialFeelings(String feelings);

    // 任务创建时间戳（毫秒），用于判断过期
    long getCreateTime();

}
