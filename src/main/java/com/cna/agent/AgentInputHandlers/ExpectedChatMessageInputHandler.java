package com.cna.agent.AgentInputHandlers;

import com.cna.agent.AgentInput.ChatMessageInput;
import com.cna.agent.AgentTask.ChatTask;
import com.cna.agent.AgentTask.DefaultAgentTaskUnit;
import com.cna.agent.LivingLoop;
import com.cna.agent.MemoryManager;
import com.cna.config.ConfigsManager;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Deprecated
public class ExpectedChatMessageInputHandler extends ChatMessageInputHandler {

    public static class PendingTaskWrapper {
        public ChatTask task;
        public long expireTimeMs;
        public PendingTaskWrapper(ChatTask task, long expireTimeMs) {
            this.task = task;
            this.expireTimeMs = expireTimeMs;
        }
    }

    private final Map<String, PendingTaskWrapper> pendingExpectationPool = new ConcurrentHashMap<>();

    public ExpectedChatMessageInputHandler(LivingLoop engine) {
        super(engine);
    }

    private void addPendingTask(String role, ChatTask task, int timeoutTimes) {
        long expireTimeMs = System.currentTimeMillis() + timeoutTimes;
        pendingExpectationPool.put(role, new PendingTaskWrapper(task, expireTimeMs));
        log.info("[ExpectedHandler] 成功接收引擎层的挂起任务，目标: [{}], 将在 {} 毫秒后超时", role, timeoutTimes);
    }

    @Override
    protected void processInputs(List<ChatMessageInput> inputs) {
        List<ChatMessageInput> normalInputs = new ArrayList<>();

        for (ChatMessageInput chatInput : inputs) {
            String senderRole = chatInput.getRole();
            String text = chatInput.getContent();
            long quotedId = chatInput.getQuotedMessageId();

            if (senderRole != null && pendingExpectationPool.containsKey(senderRole)) {
                log.info("目标 [Role:{}] 触发了预定挂起任务！", senderRole);

                PendingTaskWrapper wrapper = pendingExpectationPool.remove(senderRole);
                ChatTask existingChatTask = wrapper.task;

                existingChatTask.addContext("触发预定任务的消息内容:\n" + text);
                if (quotedId > 0) existingChatTask.setReplyToMessageId(quotedId);

                this.ChatTaskPreparationPool.put(senderRole, existingChatTask);
                this.updatedRoles.add(senderRole);

                List<String> l = new LinkedList<>();
                l.add("主动等待的目标 [ " + senderRole + " ] 终于发消息了: [ " + text + " ]，预定任务开始催熟。");
                MemoryManager.getInstance().inputCurrentMemorys(l, buildSourcesFromInput(chatInput));
            } else {
                normalInputs.add(chatInput);
            }
        }

        if (!normalInputs.isEmpty()) {
            super.processInputs(normalInputs);
        }
    }

    @Override
    public void tick() {
        DefaultAgentTaskUnit pendingReq;
        while ((pendingReq = this.engine.pollPendingRequest()) != null) {
            if (pendingReq instanceof ChatTask) {
                ChatTask chatTask = (ChatTask) pendingReq;
                String targetRole = chatTask.getRole();
                if (targetRole != null) {
                    this.addPendingTask(targetRole, chatTask, ConfigsManager.PENDING_CHAT_WAITING_TIME);
                }
            } else {
                log.warn("[ExpectedHandler] 拿到非 ChatTask 挂起任务，已重新退回普通执行总线");
                this.engine.pushTask(pendingReq);
            }
        }

        long now = System.currentTimeMillis();
        pendingExpectationPool.entrySet().removeIf(entry -> {
            if (now > entry.getValue().expireTimeMs) {
                log.info("挂起的预设任务 [Role:{}] 已超时作废", entry.getKey());
                return true;
            }
            return false;
        });

        super.tick();
    }
}