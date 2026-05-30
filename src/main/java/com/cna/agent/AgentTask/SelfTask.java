package com.cna.agent.AgentTask;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Agent 自主创建的任务。
 *
 * 与 ChatTask（外部消息驱动）不同，SelfTask 是 Agent 在认知循环中
 * 通过 create_self_task 工具主动为自己创建的任务。它可以关联任意来源、
 * 设置优先级、并可选择性地挂载到父任务下形成任务树。
 */
public class SelfTask extends AbstractAgentTask {

    private final String taskText;
    private final List<String> sources;
    private final UUID parentTaskId;

    /**
     * @param taskText     任务描述——Agent 写给未来自己的话
     * @param sources      任务来源标签（LLM 自由指定，如 "curiosity:5", "research:AI对齐", "system:self"）
     * @param parentTaskId 父任务 UUID，可为 null
     */
    public SelfTask(String taskText, List<String> sources, UUID parentTaskId) {
        super();
        this.taskText = taskText;
        this.sources = new ArrayList<>();
        if (sources != null && !sources.isEmpty()) {
            this.sources.addAll(sources);
        }
        if (this.sources.isEmpty()) {
            this.sources.add("system:self");
        }
        this.parentTaskId = parentTaskId;
    }

    @Override
    public String getTaskText() {
        return this.taskText;
    }

    @Override
    public List<String> getSources() {
        return this.sources;
    }

    /** 父任务 UUID，null 表示顶层任务 */
    public UUID getParentTaskId() {
        return this.parentTaskId;
    }

    /** 是否有父任务 */
    public boolean hasParent() {
        return this.parentTaskId != null;
    }

    @Override
    public String getTaskName() {
        String preview = taskText.length() > 40 ? taskText.substring(0, 40) + "..." : taskText;
        return "SelfTask: " + preview;
    }
}
