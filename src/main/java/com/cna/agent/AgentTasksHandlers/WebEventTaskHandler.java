package com.cna.agent.AgentTasksHandlers;

import com.cna.agent.AgentTask.DefaultAgentTaskUnit;
import com.cna.agent.AgentTask.WebEventTask;
import com.cna.agent.LivingLoop;
import com.cna.config.ConfigsManager;
import com.cna.llm.LLManager;

import java.util.Map;

public class WebEventTaskHandler extends AbstractAgentTaskHandler {

    @Override
    public Class<? extends DefaultAgentTaskUnit> getSupportedTaskClass() {
        return WebEventTask.class; // 声明专门负责处理 WebEventTask
    }

    @Override
    protected boolean prepareBaseData(DefaultAgentTaskUnit task, Map<String, Object> baseData, LivingLoop engine) {
        // 如果网页交互需要检索长期记忆支持，保留这行
        //baseData.put("deep_memories", LLManager.getDeepMemories(task.getTaskText(), engine.getEmbLLM(), ConfigsManager.MEMORY_DEPTH));

        return true; // 允许执行
    }

    @Override
    protected String getTaskDescription() {
        return "网页交互响应任务";
    }
}