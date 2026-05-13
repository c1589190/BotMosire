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

}