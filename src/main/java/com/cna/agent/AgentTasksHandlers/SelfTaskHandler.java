package com.cna.agent.AgentTasksHandlers;

import com.cna.agent.AgentTask.DefaultAgentTaskUnit;
import com.cna.agent.AgentTask.SelfTask;
import com.cna.agent.LivingLoop;
import com.cna.config.ConfigsManager;
import com.cna.llm.LLManager;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * 自主任务处理器。
 *
 * 把 Agent 之前通过 create_self_task 写给自己的任务描述注入上下文，
 * 让 Agent 在当前认知循环中执行它。与 ChatTask 不同，SelfTask 没有
 * 外部聊天记录——它的全部上下文就是任务描述 + 深层记忆 + 系统状态。
 */
@Slf4j
@Deprecated
public class SelfTaskHandler extends AbstractAgentTaskHandler {

    @Override
    public Class<? extends DefaultAgentTaskUnit> getSupportedTaskClass() {
        return SelfTask.class;
    }

    @Override
    protected boolean prepareBaseData(DefaultAgentTaskUnit task, Map<String, Object> baseData, LivingLoop engine) {
        SelfTask selfTask = (SelfTask) task;

        String taskText = selfTask.getTaskText();
        if (taskText == null || taskText.isBlank()) {
            log.warn("[SelfTaskHandler] 任务文本为空，拒绝执行");
            return false;
        }

        // 注入深层记忆
        baseData.put("deep_memories",
                LLManager.getDeepMemories(taskText, engine.getEmbLLM(), ConfigsManager.MEMORY_DEPTH));

        // 标注来源供 Agent 了解任务背景
        baseData.put("self_task_sources", String.join(", ", selfTask.getSources()));

        if (selfTask.hasParent()) {
            baseData.put("parent_task_info", "此任务是父任务创建的子任务，完成后父任务会收到通知。");
        }

        return true;
    }

    @Override
    protected String getTaskDescription() {
        return "自主任务";
    }
}
