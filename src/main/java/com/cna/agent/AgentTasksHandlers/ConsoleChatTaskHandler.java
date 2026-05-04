package com.cna.agent.AgentTasksHandlers;

import com.cna.agent.AgentTask.ChatTask;
import com.cna.agent.AgentTask.ConsoleChatTask;
import com.cna.agent.AgentTask.DefaultAgentTaskUnit;
import com.cna.agent.LivingLoop;
import com.cna.config.ConfigsManager;
import com.cna.llm.LLMAdapter;
import com.cna.llm.LLManager;
import com.fasterxml.jackson.databind.node.ArrayNode;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;

@Slf4j
public class ConsoleChatTaskHandler implements DefaultAgentTaskHandler{
    @Override
    public Class<? extends DefaultAgentTaskUnit> getSupportedTaskClass() {
        return ConsoleChatTask.class; // 认领class
    }

    @Override
    public void handleTask(DefaultAgentTaskUnit task, LivingLoop engine, ArrayNode toolsDefinitionArray) {
        ConsoleChatTask Task = (ConsoleChatTask) task;

        Map<String, Object> baseData = new HashMap<>();
        baseData.put("taskText", Task.getTaskText());
        baseData.put("deep_memories", LLManager.getDeepMemories(Task.getTaskText(), new LLMAdapter(ConfigsManager.EMBEDDING_CONFIG), ConfigsManager.MEMORY_DEPTH));

        // 调用 LivingLoop 的公共引擎
        engine.executeCognitiveCycle(
                "SolveConsoleChatTask",
                "ThinkConsoleChatTask",
                baseData,
                new LLMAdapter(ConfigsManager.BRAIN_CONFIG),
                toolsDefinitionArray,
                "系统聊天任务"
        );

        log.info("========== 常规聊天任务处理完毕 ==========\n");
    }
}
