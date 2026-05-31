package com.cna.agent.AgentInputHandlers;

import com.cna.agent.AgentInput.ChatMessageInput;
import com.cna.agent.AgentTask.ChatTask;
import com.cna.agent.LivingLoop;
import com.cna.agent.MessageKeywordManager;
import com.cna.config.ConfigsManager;
import com.cna.agent.MemoryManager;
import com.cna.db.FeelingDimensionManager;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

@Slf4j
@Deprecated
public class ChatMessageInputHandler extends AbstractInputHandler<ChatMessageInput> {

    protected LinkedHashMap<String, ChatTask> ChatTaskPreparationPool = new LinkedHashMap<>();
    protected Set<String> updatedRoles = new HashSet<>();
    protected final Map<String, Long> lastTaskPushedTime = new java.util.concurrent.ConcurrentHashMap<>();

    // ========== 积压区 ==========
    private final ConcurrentLinkedQueue<BackloggedInput> backlog = new ConcurrentLinkedQueue<>();

    private static class BackloggedInput {
        final ChatMessageInput input;
        final double interestScore;
        final String reason;

        BackloggedInput(ChatMessageInput input, double interestScore, String reason) {
            this.input = input;
            this.interestScore = interestScore;
            this.reason = reason;
        }
    }

    // 基准门槛
    private static final double BASE_REFLEX_THRESHOLD = ConfigsManager.SPINAL_REFLEX_THRESHOLD;

    public ChatMessageInputHandler(LivingLoop engine) {
        super(ChatMessageInput.class, engine);
    }

    @Override
    protected void processInputs(List<ChatMessageInput> inputs) {
        updatedRoles.clear();
        log.info("[CognitiveCycle] ChatMessage处理器捕获到 {} 条待处理的感知输入", inputs.size());

        // ==========================================
        // 阶段 0：判断是否需要积压
        // ==========================================
        boolean taskQueueFull = engine.getPendingTaskCount() >= ConfigsManager.MAX_TASK_AMOUNT;
        boolean hasBacklog = !backlog.isEmpty();

        if (taskQueueFull || hasBacklog) {
            // 积压模式：跳过正常审查，所有Input评分后直接入积压区
            log.info("[Backlog] TaskQueue已满({})或积压区已有积压，{} 条Input进入积压区",
                    taskQueueFull ? "满" : "有空位但积压区非空", inputs.size());

            FeelingDimensionManager feelingManager = FeelingDimensionManager.getInstance();

            for (ChatMessageInput input : inputs) {
                String textContent = input.getContent();
                double score = 0;
                String reason = "默认";

                if (feelingManager != null) {
                    FeelingDimensionManager.FeelingEvaluation eval = feelingManager.evaluateInput(textContent);
                    score = eval.InterestScore;
                    reason = String.format("触达关注点：[%s] (得分: %.2f)", eval.topConcept, eval.InterestScore);
                }

                // 消息关键词命中加分
                double keywordBonus = MessageKeywordManager.getInstance()
                        .evaluateInput(input.getContent(), input.getRole(),
                                input.getRole_name(), input.getSource(), input.getSource_name());
                score += keywordBonus;
                if (keywordBonus > 0) {
                    reason += String.format(" + 关键词加成:%.2f", keywordBonus);
                }

                backlog.offer(new BackloggedInput(input, score, reason));
                // 超上限踢最旧
                while (backlog.size() > ConfigsManager.INPUT_BACKLOG_MAX_SIZE) {
                    backlog.poll();
                }
            }
            log.info("[Backlog] 积压区大小: {}", backlog.size());
            return;
        }

        // ========== 正常审查流程（原有逻辑不变） ==========

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
                log.info("为已有任务 [Role:{}] 追加了新消息", senderRole);
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

            double currentDynamicThreshold = getDynamicThreshold(BASE_REFLEX_THRESHOLD);
            int currentHeat = this.engine.getCognitiveHeat().get();
            boolean isStarving = (currentHeat == 0);

            log.info("[Gatekeeper] 当前系统压力热度: {}, 动态拦截阈值已自适应调整为: {}", currentHeat, currentDynamicThreshold);

            for (ChatMessageInput input : unknownInputs) {
                String textContent = input.getContent();

                // ========== @提及强通：被 @ 直接放行，无需感觉评估 ==========
                if (ConfigsManager.ALWAYS_RESPOND_TO_AT_ME && input.isAtMe()) {
                    String reason = "这条消息明确@了你 (自身被提及)，直接放行，无需感觉评估";
                    interestingInputsWithReasons.put(input, reason);
                    log.info("高优消息被 @提及强通拦截放行。原因：{}", reason);
                    int newHeat = this.engine.getCognitiveHeat().incrementAndGet();
                    log.debug("认知热度上升至: {}", newHeat);
                    continue;
                }

                if (feelingManager == null) {
                    log.warn("[Gatekeeper-Feeling] 感觉引擎未挂载，默认放行此消息。");
                    interestingInputsWithReasons.put(input, "系统感觉中枢离线，出于本能接收所有刺激");
                    continue;
                }

                FeelingDimensionManager.FeelingEvaluation eval = feelingManager.evaluateInput(textContent);

                // 消息关键词命中加分
                double keywordBonus = MessageKeywordManager.getInstance()
                        .evaluateInput(input.getContent(), input.getRole(),
                                input.getRole_name(), input.getSource(), input.getSource_name());
                double adjustedScore = eval.InterestScore + keywordBonus;

                // 好奇心优先审查：检查输入是否触及活跃的好奇维度
                com.cna.agent.CuriosityListManager clm = com.cna.agent.CuriosityListManager.getInstance();
                java.util.List<Integer> curiosityMatch = (clm != null)
                        ? clm.checkCuriosityMatch(textContent) : java.util.List.of();
                double curiosityBonus = curiosityMatch.isEmpty() ? 0.0
                        : com.cna.config.ConfigsManager.CURIOSITY_INPUT_BONUS;
                double finalScore = adjustedScore + curiosityBonus;

                boolean isHighValue = finalScore >= currentDynamicThreshold;
                boolean isLuckyTrash = !isHighValue && isStarving && (Math.random() < ConfigsManager.RANDOM_CHAT_CHANCE);

                if (isHighValue || isLuckyTrash) {
                    String reason;
                    if (isHighValue) {
                        if (adjustedScore < currentDynamicThreshold && curiosityBonus > 0) {
                            // 仅因好奇心加成而通过
                            String matchConcepts = clm.buildCuriosityMatchReason(curiosityMatch);
                            reason = String.format("这条消息虽然感觉得分较低(%.2f)，但触及了你的未解认知矛盾[%s](好奇心加成%.2f)，出于探索本能决定处理。综合得分: %.2f >= 阈值: %.2f",
                                    adjustedScore, matchConcepts, curiosityBonus, finalScore, currentDynamicThreshold);
                            log.info("好奇心加成放行。原因：{}", reason);
                        } else {
                            reason = String.format("这条消息强烈触碰了你的核心关注点：[%s] (感觉得分: %.2f",
                                    eval.topConcept, eval.InterestScore);
                            if (keywordBonus > 0) {
                                reason += String.format(" + 关键词加成:%.2f", keywordBonus);
                            }
                            if (curiosityBonus > 0) {
                                reason += String.format(" + 好奇心加成:%.2f", curiosityBonus);
                            }
                            reason += String.format("，打破当前动态阈值: %.2f)", currentDynamicThreshold);
                            log.info("高优消息被拦截放行。原因：{}", reason);
                        }
                    } else {
                        reason = String.format("这条消息在你的感觉中枢里得分极低(%.2f < %.2f)，你感觉它是废话。但是由于现在你也没其他事干，你决定勉为其难地随便回复一下它，维持活性。",
                                eval.InterestScore, currentDynamicThreshold);
                        log.info("系统处于空虚状态，低价值消息被破格捞起。");
                        isStarving = false;
                    }
                    interestingInputsWithReasons.put(input, reason);
                    int newHeat = this.engine.getCognitiveHeat().incrementAndGet();
                    log.debug("认知热度上升至: {}", newHeat);
                } else {
                    log.info("消息被抛弃 (得分: {} < 门槛: {}): [{}]", eval.InterestScore, currentDynamicThreshold, textContent);
                }
            }

            if (interestingInputsWithReasons.isEmpty()) {
                log.info("[Gatekeeper-Feeling] 全盘否定，判定所有新消息均为无意义噪音（未能打破动态阈值 {}）。", currentDynamicThreshold);
            }

            // 处理最终被接纳的所有消息
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
                        ChatTask task = new ChatTask(source, source_name, senderRole, senderName, quotedId, input.isPrivate());
                        task.addContext(text);
                        task.addContext(reasonContext);

                        FeelingDimensionManager.FeelingEvaluation eval = feelingManager.evaluateInput(text);
                        double priority = 3.0;
                        if (input.isPrivate()) {
                            priority -= 0.5;
                        }
                        priority -= eval.stimulusBonus;
                        task.setPriority(priority);
                        log.info("为新目标 [Role:{}] 创建了预备任务，优先级: {} (私聊优惠: {}, 刺激加成: {})",
                                senderRole, String.format("%.2f", priority),
                                "private".equalsIgnoreCase(source) ? "是" : "否",
                                String.format("%.3f", eval.stimulusBonus));

                        ChatTaskPreparationPool.put(senderRole, task);
                    }

                    List<String> l = new LinkedList<>();
                    l.add("本能决定回复这条消息:{\n" + input.getInputText() + "\n}; 理由是: " + reason);
                    MemoryManager.getInstance().inputCurrentMemorys(l, buildSourcesFromInput(input));

                    updatedRoles.add(senderRole);
                }
            }
        }
    }

    @Override
    public void tick() {
        // ========== 原有逻辑：催熟 PreparationPool → pushTask ==========
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

        // ========== 新增：积压区择优消化 ==========
        boolean taskQueueFull = engine.getPendingTaskCount() >= ConfigsManager.MAX_TASK_AMOUNT;
        if (taskQueueFull || backlog.isEmpty()) {
            return;
        }

        log.info("[Backlog-Drain] TaskQueue有空位，积压区有 {} 条待审查Input，启动择优消化", backlog.size());

        // 1. 收集所有积压条目，按 interestScore 降序排列
        List<BackloggedInput> allBacklogged = new ArrayList<>(backlog);
        allBacklogged.sort((a, b) -> Double.compare(b.interestScore, a.interestScore));

        // 2. 取前 INPUT_REVIEW_BATCH_SIZE 条
        int batchSize = Math.min(ConfigsManager.INPUT_REVIEW_BATCH_SIZE, allBacklogged.size());
        List<BackloggedInput> selected = allBacklogged.subList(0, batchSize);

        // 3. 按 role 分组选中条目
        Map<String, List<BackloggedInput>> groupedByRole = new LinkedHashMap<>();
        for (BackloggedInput bi : selected) {
            String role = bi.input.getRole();
            if (role != null && !role.isBlank()) {
                groupedByRole.computeIfAbsent(role, k -> new ArrayList<>()).add(bi);
            }
        }

        // 4. 为每个 role 创建 ChatTask，并压入该 role 在积压区中的所有消息
        FeelingDimensionManager feelingManager = FeelingDimensionManager.getInstance();

        for (Map.Entry<String, List<BackloggedInput>> group : groupedByRole.entrySet()) {
            String role = group.getKey();
            List<BackloggedInput> roleBatch = group.getValue();

            // 取第一条作为代表构建 ChatTask
            BackloggedInput first = roleBatch.get(0);
            ChatMessageInput rep = first.input;

            String source = rep.getSource();
            String source_name = rep.getSource_name();
            String senderName = rep.getRole_name();
            long quotedId = rep.getQuotedMessageId();

            ChatTask task;
            if (ChatTaskPreparationPool.containsKey(role)) {
                task = ChatTaskPreparationPool.get(role);
            } else {
                task = new ChatTask(source, source_name, role, senderName, quotedId, rep.isPrivate());

                // 优先级计算
                double priority = 3.0;
                if (rep.isPrivate()) {
                    priority -= 0.5;
                }
                if (feelingManager != null) {
                    FeelingDimensionManager.FeelingEvaluation eval = feelingManager.evaluateInput(rep.getContent());
                    priority -= eval.stimulusBonus;
                }
                task.setPriority(priority);
            }

            // 把该 role 在积压区中的所有消息文本 + 理由全部灌入
            for (BackloggedInput bi : roleBatch) {
                task.addContext(bi.input.getContent());
                task.addContext("( 理由: " + bi.reason + " )");
            }

            ChatTaskPreparationPool.put(role, task);
            log.info("[Backlog-Drain] 为 [Role:{}] 从积压区创建/扩容了ChatTask，消耗 {} 条积压Input", role, roleBatch.size());

            List<String> l = new LinkedList<>();
            l.add("从积压区消化了来自 " + role + " 的消息，回复理由是: " + first.reason);
            MemoryManager.getInstance().inputCurrentMemorys(l, buildSourcesFromInput(rep));
        }

        // 5. 从积压区移除已消费的条目
        Set<BackloggedInput> consumed = new HashSet<>(selected);
        backlog.removeIf(consumed::contains);

        log.info("[Backlog-Drain] 消化完成，积压区剩余: {}", backlog.size());
    }

    /**
     * 根据 ChatMessageInput 构造来源标识符列表。
     * 私聊仅标注 role（用户ID），群聊标注 source（群ID）+ role（用户ID）。
     */
    protected List<String> buildSourcesFromInput(ChatMessageInput input) {
        List<String> sources = new ArrayList<>();
        if (!input.isPrivate() && input.getSource() != null && !input.getSource().isBlank()) {
            sources.add(input.getSource());
        }
        if (input.getRole() != null && !input.getRole().isBlank()) {
            sources.add(input.getRole());
        }
        return sources.isEmpty() ? List.of("system:internal") : sources;
    }
}