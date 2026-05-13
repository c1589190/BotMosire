package com.cna.agent.AgentInputHandlers;

import com.cna.agent.AgentInput.ChatMessageInput;
import com.cna.agent.AgentInput.DefaultAgentInputUnit;
import com.cna.agent.AgentTask.ChatTask;
import com.cna.agent.AgentTask.DefaultAgentTaskUnit;
import com.cna.agent.LivingLoop;
import com.cna.agent.MemoryManager;
import com.cna.config.ConfigsManager;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class ExpectedChatMessageInputHandler extends ChatMessageInputHandler {

    // 内部类：用于记录任务和过期时间
    public static class PendingTaskWrapper {
        public ChatTask task;
        public long expireTimeMs;
        public PendingTaskWrapper(ChatTask task, long expireTimeMs) {
            this.task = task;
            this.expireTimeMs = expireTimeMs;
        }
    }

    // 挂起池：专门存放设下陷阱等待的目标
    private final Map<String, PendingTaskWrapper> pendingExpectationPool = new ConcurrentHashMap<>();

    // 对外开放的操作方法（供 LivingLoop 强制注入）
    private void addPendingTask(String role, ChatTask task, int timeoutTimes) {
        long expireTimeMs = System.currentTimeMillis() + timeoutTimes;
        pendingExpectationPool.put(role, new PendingTaskWrapper(task, expireTimeMs));
        log.info("[ExpectedHandler] 成功接收引擎层的挂起任务，目标: [{}], 将在 {} 毫秒后超时", role, timeoutTimes);
    }

    @Override
    public void handleInputs(List<DefaultAgentInputUnit> inputs, LivingLoop engine) {
        // 用于收集没有被拦截下来的正常消息
        List<DefaultAgentInputUnit> normalInputs = new ArrayList<>();

        for (DefaultAgentInputUnit input : inputs) {
            if (input instanceof ChatMessageInput) {
                ChatMessageInput chatInput = (ChatMessageInput) input;
                String senderRole = chatInput.getRole();
                String text = chatInput.getContent();
                long quotedId = chatInput.getQuotedMessageId();

                // 核心拦截逻辑：判断是不是猎物上钩了
                if (senderRole != null && pendingExpectationPool.containsKey(senderRole)) {
                    log.info("目标 [Role:{}] 触发了预定挂起任务！", senderRole);

                    PendingTaskWrapper wrapper = pendingExpectationPool.remove(senderRole);
                    ChatTask existingChatTask = wrapper.task;

                    existingChatTask.addContext("触发预定任务的消息内容:\n" + text);
                    if (quotedId > 0) existingChatTask.setReplyToMessageId(quotedId);

                    // 直接塞进父类的池子里，享受父类原汁原味的催熟逻辑！
                    this.ChatTaskPreparationPool.put(senderRole, existingChatTask);
                    this.updatedRoles.add(senderRole);

                    List<String> l = new LinkedList<>();
                    l.add("主动等待的目标 [ " + senderRole + " ] 终于发消息了: [ " + text + " ]，预定任务开始催熟。");
                    MemoryManager.getInstance().inputCurrentMemorys(l);
                } else {
                    // 不是设局目标，加入普通列表
                    normalInputs.add(input);
                }
            } else {
                normalInputs.add(input);
            }
        }

        // 拦截完毕后，把剩下的普通消息全盘交给父类去处理（极速分拣、小模型审查、无聊捞垃圾）
        if (!normalInputs.isEmpty()) {
            super.handleInputs(normalInputs, engine);
        }
    }

    @Override
    public void tick(LivingLoop engine) {

        // ==========================================
        // 【新增】：从引擎总线的缓冲队列中“收快递”
        // ==========================================
        DefaultAgentTaskUnit pendingReq;
        // 循环拿取，直到队列为空
        while ((pendingReq = engine.pollPendingRequest()) != null) {
            // 我们只处理 ChatTask 类型的挂起任务
            if (pendingReq instanceof ChatTask) {
                ChatTask chatTask = (ChatTask) pendingReq;
                String targetRole = chatTask.getRole(); // 确保你的 ChatTask 有获取目标 role 的方法

                if (targetRole != null) {
                    //int defaultTimeoutTime = 15; // 极简处理：统一默认挂起等待 3 分钟
                    this.addPendingTask(targetRole, chatTask, ConfigsManager.PENDING_CHAT_WAITING_TIME);
                }
            } else {
                // 如果大模型以后挂起了别的类型的任务（比如 EmailTask），又塞回常规执行队列里
                log.warn("[ExpectedHandler] 拿到非 ChatTask 挂起任务，已重新退回普通执行总线");
                engine.pushTask(pendingReq);
            }
        }

        long now = System.currentTimeMillis();

        // 1. 清理超时的挂起任务（默默作废）
        pendingExpectationPool.entrySet().removeIf(entry -> {
            if (now > entry.getValue().expireTimeMs) {
                log.info("挂起的预设任务 [Role:{}] 已超时作废", entry.getKey());
                return true;
            }
            return false;
        });

        // 2. 呼叫父类的 tick 正常走催熟和推送逻辑
        super.tick(engine);
    }
}