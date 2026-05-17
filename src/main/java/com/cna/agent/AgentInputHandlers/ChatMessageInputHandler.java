package com.cna.agent.AgentInputHandlers;

import com.cna.agent.AgentInput.ChatMessageInput;
import com.cna.agent.AgentTask.ChatTask;
import com.cna.agent.LivingLoop;
import com.cna.config.ConfigsManager;
import com.cna.agent.MemoryManager;
import com.cna.db.FeelingDimensionManager;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

@Slf4j
public class ChatMessageInputHandler extends AbstractInputHandler<ChatMessageInput> {

    protected LinkedHashMap<String, ChatTask> ChatTaskPreparationPool = new LinkedHashMap<>();
    protected Set<String> updatedRoles = new HashSet<>();
    protected final Map<String, Long> lastTaskPushedTime = new java.util.concurrent.ConcurrentHashMap<>();

    // 基准门槛
    private static final double BASE_REFLEX_THRESHOLD = ConfigsManager.SPINAL_REFLEX_THRESHOLD;

    public ChatMessageInputHandler(LivingLoop engine) {
        super(ChatMessageInput.class, engine);
    }

    @Override
    protected void processInputs(List<ChatMessageInput> inputs) {
        updatedRoles.clear();
        log.info("[CognitiveCycle] ChatMessage处理器捕获到 {} 条待处理的感知输入", inputs.size());

        List<ChatMessageInput> unknownInputs = new ArrayList<>();

        // ==========================================
        // 阶段 1：本地极速分拣
        // ==========================================
        for (ChatMessageInput input : inputs) {
            ChatTask existingChatTask;
            String senderRole = input.getRole();

            if (senderRole != null && !senderRole.isBlank() && ChatTaskPreparationPool.containsKey(senderRole)) {
                existingChatTask = ChatTaskPreparationPool.get(senderRole);
                existingChatTask.addContext(input.getContent());

                long quotedId = input.getQuotedMessageId();
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

        // ==========================================
        // 阶段 2：感觉中枢综合筛选 (高优拦截 + 空虚捞取 一体化)
        // ==========================================
        if (!unknownInputs.isEmpty()) {
            log.info("[Gatekeeper-Feeling] 启动底层感觉评估引擎，开始扫描 {} 条新消息...", unknownInputs.size());

            Map<ChatMessageInput, String> interestingInputsWithReasons = new LinkedHashMap<>();
            FeelingDimensionManager feelingManager = FeelingDimensionManager.getInstance();

            // 计算动态阈值与当前系统的空虚状态
            double currentDynamicThreshold = getDynamicThreshold(BASE_REFLEX_THRESHOLD);
            int currentHeat = this.engine.getCognitiveHeat().get();
            boolean isStarving = (currentHeat == 0); // 系统压力为 0 时，判定为处于极度空虚的“饥饿状态”

            log.info("[Gatekeeper] 当前系统压力热度: {}, 动态拦截阈值已自适应调整为: {}", currentHeat, currentDynamicThreshold);

            for (ChatMessageInput input : unknownInputs) {
                String textContent = input.getContent();

                if (feelingManager == null) {
                    log.warn("[Gatekeeper-Feeling] 感觉引擎未挂载，默认放行此消息。");
                    interestingInputsWithReasons.put(input, "系统感觉中枢离线，出于本能接收所有刺激");
                    continue;
                }

                FeelingDimensionManager.FeelingEvaluation eval = feelingManager.evaluateInput(textContent);

                // 【核心重构】：一次性判定是否接纳
                boolean isHighValue = eval.finalScore >= currentDynamicThreshold;
                // 仅当分值不够，且系统极度空虚时，才触发概率捞取
                boolean isLuckyTrash = !isHighValue && isStarving && (Math.random() < ConfigsManager.RANDOM_CHAT_CHANCE);

                if (isHighValue || isLuckyTrash) {
                    String reason;
                    if (isHighValue) {
                        reason = String.format("这条消息强烈触碰了你的核心关注点：[%s] (潜意识得分: %.2f，打破当前动态阈值: %.2f)",
                                eval.topConcept, eval.finalScore, currentDynamicThreshold);
                        log.info("高优消息被拦截放行。原因：{}", reason);
                    } else {
                        reason = String.format("这条消息在你的感觉中枢里得分极低(%.2f < %.2f)，你感觉它是废话。但是由于现在你也没其他事干，你决定勉为其难地随便回复一下它，维持活性。",
                                eval.finalScore, currentDynamicThreshold);
                        log.info("系统处于空虚状态，低价值消息被破格捞起。");

                        // 【防抖保护】：一旦破格捞起了一条垃圾，系统立刻脱离空虚状态，防止在本轮循环中捞取多条废话
                        isStarving = false;
                    }
                    interestingInputsWithReasons.put(input, reason);
                } else {
                    log.info("消息被抛弃 (得分: {} < 门槛: {}): [{}]", eval.finalScore, currentDynamicThreshold, textContent);
                }
            }

            if (interestingInputsWithReasons.isEmpty()) {
                log.info("[Gatekeeper-Feeling] 全盘否定，判定所有新消息均为无意义噪音（未能打破动态阈值 {}）。", currentDynamicThreshold);
            }

            // 处理最终被接纳的所有消息（无论是高优还是捞取的）
            for (Map.Entry<ChatMessageInput, String> entry : interestingInputsWithReasons.entrySet()) {
                ChatMessageInput input = entry.getKey();
                String reason = entry.getValue();

                String source = input.getSource();
                String source_name = input.getSource_name();
                String senderRole = input.getRole();
                String senderName = input.getRole_name();
                String text = input.getContent();

                if (senderRole != null && !senderRole.isBlank()) {
                    long quotedId = input.getQuotedMessageId();
                    String reasonContext = "( 你的感觉这条消息值得你回复，理由是: " + reason + " )";

                    if (ChatTaskPreparationPool.containsKey(senderRole)) {
                        ChatTask existingTask = ChatTaskPreparationPool.get(senderRole);
                        existingTask.addContext(reasonContext);
                        existingTask.addContext(text);
                        if (quotedId > 0) existingTask.setReplyToMessageId(quotedId);
                    } else {
                        ChatTask task = new ChatTask(source, source_name, senderRole, senderName, quotedId);
                        task.addContext(text);
                        task.addContext(reasonContext);
                        ChatTaskPreparationPool.put(senderRole, task);
                        log.info("为新目标 [Role:{}] 创建了预备任务及潜意识理由", senderRole);
                    }

                    List<String> l = new LinkedList<>();
                    l.add("本能决定回复这条消息:{\n" + input.getInputText() + "\n}; 理由是: " + reason);
                    MemoryManager.getInstance().inputCurrentMemorys(l);

                    updatedRoles.add(senderRole);
                }
            }
            // 阶段 2.5 代码块已彻底消灭
        }
    }

    @Override
    public void tick() {
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
                        this.engine.pushTask(task);
                        iterator.remove();
                    }
                }
            }
        }
        updatedRoles.clear();
    }
}