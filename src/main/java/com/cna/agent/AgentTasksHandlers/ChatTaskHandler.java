package com.cna.agent.AgentTasksHandlers;

import com.cna.agent.AgentTask.ChatTask;
import com.cna.agent.AgentTask.DefaultAgentTaskUnit;
import com.cna.agent.LivingLoop;
import com.cna.config.ConfigsManager;
import com.cna.config.ScenePromptsManager;
import com.cna.llm.LLManager;
import com.fasterxml.jackson.databind.node.ArrayNode;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;

@Slf4j
public class ChatTaskHandler implements DefaultAgentTaskHandler {

    public static final ThreadLocal<Long> CURRENT_REPLY_TO_ID = ThreadLocal.withInitial(() -> 0L);

    @Override
    public Class<? extends DefaultAgentTaskUnit> getSupportedTaskClass() {
        return ChatTask.class;
    }

    @Override
    public void handleTask(DefaultAgentTaskUnit task, LivingLoop engine, ArrayNode toolsDefinitionArray) {
        ChatTask chatTask = (ChatTask) task;

        CURRENT_REPLY_TO_ID.set(chatTask.getReplyToMessageId());

        try {
            Map<String, Object> baseData = new HashMap<>();
            baseData.put("taskText", chatTask.getTaskText());
            baseData.put("deep_memories", LLManager.getDeepMemories(chatTask.getTaskText(), engine.getEmbLLM(), ConfigsManager.MEMORY_DEPTH));

            DefaultAgentTaskUnit retTask = engine.executeCognitiveCycle(
                    task,
                    new ScenePromptsManager(ChatTask.class.getName()),
                    baseData,
                    engine.getLargeLLM(),
                    toolsDefinitionArray,
                    "常规聊天任务"
            );

            if (retTask == null) {
                log.info("任务" + this.getClass().getName() + "已终结并销毁\n");
                return;
            }

            engine.pushTask(retTask);

        } finally {
            // 【关键修复】：无论成功还是异常，绝对保证清理 ThreadLocal
            CURRENT_REPLY_TO_ID.remove();
        }
    }
}
