package com.cna.agent.AgentTasksHandlers;

import com.cna.agent.AgentTask.DefaultAgentTaskUnit;
import com.cna.agent.AgentTask.UpdateThoughtsTask;
import com.cna.agent.AgentTool.ReflectiveCompactionTool;
import com.cna.agent.LivingLoop;
import com.cna.config.ConfigsManager;
import com.cna.db.MDManager;
import com.cna.llm.LLMAdapter;
import com.fasterxml.jackson.databind.node.ArrayNode;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;

@Slf4j
public class UpdateThoughtsTaskHandler implements DefaultAgentTaskHandler {

    @Override
    public Class<? extends DefaultAgentTaskUnit> getSupportedTaskClass() {
        return UpdateThoughtsTask.class; // 认领反思任务
    }

    @Override
    public void handleTask(DefaultAgentTaskUnit task, LivingLoop engine, ArrayNode toolsDefinitionArray) {
        toolsDefinitionArray.add(new ReflectiveCompactionTool().getToolDefinition());

        UpdateThoughtsTask thoughtsTask = (UpdateThoughtsTask) task;

        Map<String, Object> baseData = new HashMap<>();
        baseData.put("taskText", thoughtsTask.getTaskText());
        baseData.put("current_interests", MDManager.read("interests.md", ""));
        baseData.put("current_scheduled", MDManager.read("scheduled.md", ""));

        // 调用 LivingLoop 的公共引擎，使用 largeLLM
        engine.executeCognitiveCycle(
                "LivingLoop_CognitiveCycle_updateThoughts",
                "",
                baseData,
                new LLMAdapter(ConfigsManager.BRAIN_CONFIG),
                toolsDefinitionArray,
                "系统级反思任务"
        );

        log.info("========== 系统反思任务处理完毕 ==========\n");
    }
}