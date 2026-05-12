package com.cna.agent.AgentTasksHandlers;

import com.cna.ChatAdaptersManager;
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
            // 1. 获取消息来源的 namespace (注意：这里的 getNamespace() 需要根据你 ChatTask 实际的 getter 方法名调整，比如 getSource() 等)
            String namespace = chatTask.getSource();
            log.info(namespace);

            // 2. 提前拉取该区域的近期聊天记录
            String recentHistory = ChatAdaptersManager.getHistory(namespace, ConfigsManager.CHATHISTORY_VIEW_AMOUNT);
            log.info("[ChatTaskHandler] 已为当前任务自动预载入目标 [{}] 的近期聊天上下文", namespace);

            // 3. 装配提示词所需的数据模型
            Map<String, Object> baseData = new HashMap<>();
            baseData.put("taskText", chatTask.getTaskText());
            baseData.put("recent_history", recentHistory); // 【新增】：将刚刚获取的历史记录塞入 data
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