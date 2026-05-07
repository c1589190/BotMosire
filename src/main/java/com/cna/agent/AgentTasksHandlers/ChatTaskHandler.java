package com.cna.agent.AgentTasksHandlers;

import com.cna.agent.AgentTask.ChatTask;
import com.cna.agent.AgentTask.DefaultAgentTaskUnit;
import com.cna.agent.LivingLoop;
import com.cna.config.ConfigsManager;
import com.cna.config.ScenePromptsManager;
import com.cna.llm.LLMAdapter;
import com.cna.llm.LLManager;
import com.fasterxml.jackson.databind.node.ArrayNode;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;

@Slf4j
public class ChatTaskHandler implements DefaultAgentTaskHandler {

    /** 當前線程正在處理的 ChatTask 的 replyToMessageId（0 表示無引用） */
    public static final ThreadLocal<Long> CURRENT_REPLY_TO_ID = ThreadLocal.withInitial(() -> 0L);

    @Override
    public Class<? extends DefaultAgentTaskUnit> getSupportedTaskClass() {
        return ChatTask.class; // 认领 ChatTask
    }

    @Override
    public void handleTask(DefaultAgentTaskUnit task, LivingLoop engine, ArrayNode toolsDefinitionArray) {
        ChatTask chatTask = (ChatTask) task;

        // 把 replyToMessageId 存入 ThreadLocal，供 SendChatMessage 讀取
        CURRENT_REPLY_TO_ID.set(chatTask.getReplyToMessageId());

        Map<String, Object> baseData = new HashMap<>();
        baseData.put("taskText", chatTask.getTaskText());
        baseData.put("deep_memories", LLManager.getDeepMemories(chatTask.getTaskText(), new LLMAdapter(ConfigsManager.EMBEDDING_CONFIG), ConfigsManager.MEMORY_DEPTH));

        // 调用 LivingLoop 的公共引擎
        engine.executeCognitiveCycle(
                new ScenePromptsManager(ChatTask.class.getName()),
                baseData,
                new LLMAdapter(ConfigsManager.BRAIN_CONFIG),
                toolsDefinitionArray,
                "常规聊天任务"
        );

        // 清理 ThreadLocal
        CURRENT_REPLY_TO_ID.remove();

        log.info("========== 常规聊天任务处理完毕 ==========\n");
    }
}