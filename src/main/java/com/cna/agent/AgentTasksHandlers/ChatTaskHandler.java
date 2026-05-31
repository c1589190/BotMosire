package com.cna.agent.AgentTasksHandlers;

import com.cna.ChatAdaptersManager;
import com.cna.agent.AgentTask.ChatTask;
import com.cna.agent.AgentTask.DefaultAgentTaskUnit;
import com.cna.agent.LivingLoop;
import com.cna.config.ConfigsManager;
import com.cna.llm.LLManager;
import com.fasterxml.jackson.databind.node.ArrayNode;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

@Slf4j
@Deprecated
public class ChatTaskHandler extends AbstractAgentTaskHandler {

    public static final ThreadLocal<Long> CURRENT_REPLY_TO_ID = ThreadLocal.withInitial(() -> 0L);

    @Override
    public Class<? extends DefaultAgentTaskUnit> getSupportedTaskClass() {
        return ChatTask.class;
    }

    /**
     * 【战术重写】：为了保住 ThreadLocal 的清理机制，我们包装一下父类的执行流
     */
    @Override
    public DefaultAgentTaskUnit handleTask(DefaultAgentTaskUnit task, LivingLoop engine, ArrayNode toolsDefinitionArray) {
        ChatTask chatTask = (ChatTask) task;
        CURRENT_REPLY_TO_ID.set(chatTask.getReplyToMessageId());

        try {
            // 调用父类的核心流水线，并将返回值透传出去
            return super.handleTask(task, engine, toolsDefinitionArray);
        } finally {
            // 绝对保证清理 ThreadLocal
            CURRENT_REPLY_TO_ID.remove();
        }
    }

    @Override
    protected boolean prepareBaseData(DefaultAgentTaskUnit task, Map<String, Object> baseData, LivingLoop engine) {
        ChatTask chatTask = (ChatTask) task;

        // 1. 获取消息来源的 namespace
        String namespace = chatTask.getSource();
        log.info(namespace);

        // 2. 拉取近期聊天记录
        String recentHistory = ChatAdaptersManager.getHistory(namespace, ConfigsManager.CHATHISTORY_VIEW_AMOUNT);
        log.info("[ChatTaskHandler] 已为当前任务自动预载入目标 [{}] 的近期聊天上下文", namespace);

        // 3. 压入数据 (taskText 已经在父类装载过了)
        baseData.put("recent_history", recentHistory);
        baseData.put("deep_memories", LLManager.getDeepMemories(chatTask.getTaskText(), engine.getEmbLLM(), ConfigsManager.MEMORY_DEPTH));

        return true; // 数据准备就绪，放行
    }

    @Override
    protected String getTaskDescription() {
        return "常规聊天任务";
    }
}