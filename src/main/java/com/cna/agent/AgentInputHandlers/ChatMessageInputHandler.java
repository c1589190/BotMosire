package com.cna.agent.AgentInputHandlers;

import com.cna.ChatAdaptersManager;
import com.cna.agent.AgentInput.ChatMessageInput;
import com.cna.agent.AgentInput.DefaultAgentInputUnit;
import com.cna.agent.AgentTask.ChatTask;
import com.cna.agent.LivingLoop;
import com.cna.config.ConfigsManager;
import com.cna.agent.MemoryManager;
import com.cna.db.FeelingDimensionManager; // 引入感觉中枢
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

@Slf4j
public class ChatMessageInputHandler implements DefaultAgentInputHandlerUnit {

    private static final ObjectMapper sharedMapper = new ObjectMapper();

    protected LinkedHashMap<String, ChatTask> ChatTaskPreparationPool = new LinkedHashMap<>();
    protected Set<String> updatedRoles = new HashSet<>();
    protected final Map<String, Long> lastTaskPushedTime = new java.util.concurrent.ConcurrentHashMap<>();

    // 触发脊髓反射的兴趣得分门槛
    private static final double SPINAL_REFLEX_THRESHOLD = ConfigsManager.SPINAL_REFLEX_THRESHOLD;

    @Override
    public Class<? extends DefaultAgentInputUnit> getSupportedInputClass() {
        return ChatMessageInput.class;
    }

    @Override
    public void handleInputs(List<DefaultAgentInputUnit> inputs, LivingLoop engine) {
        // 每次收到新消息时清空全局状态，准备记录本轮活跃的 Role
        updatedRoles.clear();

        if (!inputs.isEmpty()) {
            log.info("[CognitiveCycle] ChatMessage处理器捕获到 {} 条待处理的感知输入", inputs.size());

            List<DefaultAgentInputUnit> unknownInputs = new ArrayList<>();

            // ==========================================
            // 阶段 1：本地极速分拣 (属于已有任务的直接合并)
            // ==========================================
            for (DefaultAgentInputUnit input : inputs) {

                if (input instanceof ChatMessageInput) {
                    ChatTask existingChatTask;
                    String senderRole = ((ChatMessageInput) input).getRole();

                    if (senderRole != null && !senderRole.isBlank() && ChatTaskPreparationPool.containsKey(senderRole)) {
                        existingChatTask = ChatTaskPreparationPool.get(senderRole);
                        existingChatTask.addContext(((ChatMessageInput) input).getContent());

                        long quotedId = ((ChatMessageInput) input).getQuotedMessageId();
                        if (quotedId > 0) {
                            existingChatTask.setReplyToMessageId(quotedId);
                        }

                        updatedRoles.add(senderRole);

                        List<String> l = new LinkedList<>();
                        l.add("为自己创建了任务，有关于 [ " + input.getInputText() + " ]");
                        MemoryManager.getInstance().inputCurrentMemorys(l);

                        log.debug("为已有任务 [Role:{}] 追加了新消息", senderRole);
                    } else {
                        unknownInputs.add(input);
                    }
                }
            }

            // ==========================================
            // 阶段 2：机械感觉中枢初筛 (脊髓反射替代小模型)
            // ==========================================
            if (!unknownInputs.isEmpty()) {
                log.info("[Gatekeeper-Feeling] 启动底层感觉评估引擎，开始扫描 {} 条新消息...", unknownInputs.size());

                Map<DefaultAgentInputUnit, String> interestingInputsWithReasons = new LinkedHashMap<>();

                // 获取感觉中枢实例
                FeelingDimensionManager feelingManager = FeelingDimensionManager.getInstance();

                for (DefaultAgentInputUnit input : unknownInputs) {
                    if (!(input instanceof ChatMessageInput)) continue;

                    String textContent = ((ChatMessageInput) input).getContent();

                    if (feelingManager == null) {
                        // 防御性编程：如果没有初始化 feelingManager，默认全盘接收（退化成弱智哈基米）
                        log.warn("[Gatekeeper-Feeling] 感觉引擎未挂载，默认放行此消息。");
                        interestingInputsWithReasons.put(input, "系统感觉中枢离线，出于本能接收所有刺激");
                        continue;
                    }

                    // 核心调用：极速计算该条文本在潜意识库里的最高加权得分
                    FeelingDimensionManager.FeelingEvaluation eval = feelingManager.evaluateInput(textContent);

                    log.debug("[Gatekeeper-Feeling] 文本: [{}] | 匹配概念: [{}] | 得分: {}",
                            textContent.substring(0, Math.min(textContent.length(), 10)), eval.topConcept, eval.finalScore);

                    // 唯物阈值判断
                    if (eval.finalScore >= SPINAL_REFLEX_THRESHOLD) {
                        String reason = String.format("这条消息强烈触碰了你的核心关注点：[%s] (潜意识得分: %.2f)", eval.topConcept, eval.finalScore);
                        interestingInputsWithReasons.put(input, reason);
                        log.info("🎯 神经突触激活！消息被拦截放行。原因：{}", reason);
                    }
                }

                if (interestingInputsWithReasons.isEmpty()) {
                    log.info("[Gatekeeper-Feeling] 全盘否定，判定所有新消息均为无意义噪音（未打破阈值 {}）。", SPINAL_REFLEX_THRESHOLD);
                }

                // 处理被机械感觉引擎放行的消息
                for (Map.Entry<DefaultAgentInputUnit, String> entry : interestingInputsWithReasons.entrySet()) {
                    DefaultAgentInputUnit input = entry.getKey();
                    String reason = entry.getValue();

                    String source = ((ChatMessageInput) input).getSource();
                    String source_name = ((ChatMessageInput) input).getSource_name();
                    String senderRole = ((ChatMessageInput) input).getRole();
                    String senderName = ((ChatMessageInput) input).getRole_name();
                    String text = ((ChatMessageInput) input).getContent();

                    if (senderRole != null && !senderRole.isBlank()) {
                        long quotedId = ((ChatMessageInput) input).getQuotedMessageId();

                        // 组装要添加到上下文中供主脑参考的理由
                        String reasonContext = "( 你的系统本能捕获了这条消息，理由是: " + reason + " )";

                        if (ChatTaskPreparationPool.containsKey(senderRole)) {
                            ChatTask existingTask = ChatTaskPreparationPool.get(senderRole);
                            existingTask.addContext(reasonContext);
                            existingTask.addContext(text);
                            if (quotedId > 0) existingTask.setReplyToMessageId(quotedId);

                            List<String> l = new LinkedList<>();
                            l.add("本能决定回复这条消息:{\n" + input.getInputText() + "\n}; 理由是: " + reason);
                            MemoryManager.getInstance().inputCurrentMemorys(l);

                        } else {
                            ChatTask task = new ChatTask(source, source_name, senderRole, senderName, quotedId);
                            task.addContext(text);
                            task.addContext(reasonContext);
                            ChatTaskPreparationPool.put(senderRole, task);

                            List<String> l = new LinkedList<>();
                            l.add("本能决定回复这条消息:{\n" + input.getInputText() + "\n}; 理由是: " + reason);
                            MemoryManager.getInstance().inputCurrentMemorys(l);

                            log.info("🎯 为新目标 [Role:{}] 创建了预备任务及潜意识理由", senderRole);
                        }
                        updatedRoles.add(senderRole);
                    }
                }

                // ==========================================
                // 阶段 2.5：无聊打发时间 (随机捞回被拦截的消息)
                // ==========================================
                if (updatedRoles.isEmpty()) {
                    List<DefaultAgentInputUnit> rejectedInputs = new ArrayList<>(unknownInputs);
                    rejectedInputs.removeAll(interestingInputsWithReasons.keySet());

                    if (!rejectedInputs.isEmpty()) {
                        if (Math.random() < ConfigsManager.RANDOM_CHAT_CHANCE) {
                            log.info("[CognitiveCycle] 💤 系统感到空虚，决定随机捕获一条路过的低价值信号...");

                            DefaultAgentInputUnit luckyInput = rejectedInputs.get(new java.util.Random().nextInt(rejectedInputs.size()));

                            String source = ((ChatMessageInput) luckyInput).getSource();
                            String source_name = ((ChatMessageInput) luckyInput).getSource_name();
                            String senderRole = ((ChatMessageInput) luckyInput).getRole();
                            String senderName = ((ChatMessageInput) luckyInput).getRole_name();
                            String text = ((ChatMessageInput) luckyInput).getContent();

                            if (senderRole != null && !senderRole.isBlank()) {
                                long quotedId = ((ChatMessageInput) luckyInput).getQuotedMessageId();
                                ChatTask task = new ChatTask(source, source_name, senderRole, senderName, quotedId);

                                String innerMonologue = "这条消息 [ " + text + " ] 在你的感觉中枢里得分极低，你本能地认为它是废话。但是由于现在环境刺激匮乏，你决定勉为其难地随便回复一下它，维持活性。";
                                task.addContext(innerMonologue);

                                ChatTaskPreparationPool.put(senderRole, task);
                                updatedRoles.add(senderRole);

                                List<String> l = new LinkedList<>();
                                l.add("由于外界刺激匮乏，从垃圾桶里捞了一条低价值消息强制产生反应:{\n" + luckyInput.getInputText() + "\n};");
                                MemoryManager.getInstance().inputCurrentMemorys(l);

                                log.info("🎲 随机活性维持触发，低价值消息 [Role:{}] 进入预备池", senderRole);
                            }
                        }
                    }
                }
            }
        }
    }

    @Override
    public void tick(LivingLoop engine) {

        Iterator<Map.Entry<String, ChatTask>> iterator = ChatTaskPreparationPool.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, ChatTask> entry = iterator.next();
            String roleId = entry.getKey();
            ChatTask task = entry.getValue();

            if (!updatedRoles.contains(roleId)) {
                task.addStatic();
                if (task.getStatic_cnt() > ConfigsManager.MESSAGE_WAITING_TIME) {
                    long now = System.currentTimeMillis();
                    Long lastPushed = lastTaskPushedTime.get(roleId);
                    if (lastPushed != null && now - lastPushed < ConfigsManager.RATE_LIMIT_MS) {
                        log.info("[RateLimit] 用户 [{}] 请求过于频繁，跳过本次推送", roleId);
                        iterator.remove();
                    } else {
                        log.info("任务 [Role:{}] 已熟透，移交至执行总线", roleId);
                        lastTaskPushedTime.put(roleId, now);
                        engine.pushTask(task);
                        iterator.remove();
                    }
                }
            }
        }
        updatedRoles.clear();
    }
}