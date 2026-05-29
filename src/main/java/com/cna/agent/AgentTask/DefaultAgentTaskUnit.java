package com.cna.agent.AgentTask;

import java.util.UUID;

public interface DefaultAgentTaskUnit {
    UUID getUUID();
    String getTaskText();
    String getTaskName();

    double getPriority();
    void setPriority(double priority);

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

    // 任务专属感觉维度，在 pushTask 时计算，随任务队列一起展示给 LLM
    String getTaskFeelings();
    void setTaskFeelings(String feelings);

    // 任务创建时间戳（毫秒），保留兼容
    long getCreateTime();

    // 任务被推入队列时的认知循环轮次，用于基于认知周期的过期判断
    int getBornAtLoop();
    void setBornAtLoop(int loop);

    // 展示用编号，LLM 可据此引用特定任务
    int getDisplayId();
    void setDisplayId(int id);

    // 本任务已激活的工具分组集合（LLM 通过 manage_tool_groups 动态控制）
    // 任务销毁时自动释放，无需手动清理
    default java.util.Set<String> getActivatedToolGroups() {
        return java.util.Collections.emptySet();
    }

    default void setActivatedToolGroups(java.util.Set<String> groups) {
        // 默认空实现，子类可覆盖
    }

    /**
     * 返回该任务的来源标识符列表。
     * 用于在 current memory 和 deep memory 中追溯信息来源。
     * 格式示例：
     * - 聊天任务: ["qq_group:xxx", "qqid:yyy"] 或 ["qqid:yyy"] (私聊)
     * - 网页任务: ["webaddress_192.168.1.100"]
     * - 系统任务: ["system:internal"]
     */
    default java.util.List<String> getSources() {
        return java.util.List.of("system:internal");
    }
}
