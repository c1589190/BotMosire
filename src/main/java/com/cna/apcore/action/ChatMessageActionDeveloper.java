package com.cna.apcore.action;

import com.cna.agent.AgentInput.ChatMessageInput;
import com.cna.agent.AgentInput.DefaultAgentInputUnit;
import com.cna.agent.AgentInput.WebEventInput;
import com.cna.apcore.config.CoreConfig;
import com.cna.apcore.model.CognitivePrepareUnit;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Chat 消息动作孵化器 — 将零散的聊天消息按来源聚合为认知准备单元。
 *
 * 职责：
 * 1. 接收每 tick 从 AgentInputTasksQueue 捞出的原始输入
 * 2. 按 source（如 qq_group:12345）分桶累积
 * 3. 满足 flush 条件时，将桶内消息合并为一个 CognitivePrepareUnit 吐出
 * 4. 未满足条件的桶保留，等待后续 tick 继续累积
 *
 * Flush 条件（任一满足即触发）：
 * - 含 @提及：立即 flush（高优先级）
 * - 攒够了：桶内消息数 ≥ minMessages 阈值
 * - 等够了：首条消息等待时间 ≥ maxWaitMs
 * - 私聊：降低阈值，更快响应
 *
 * 所有阈值均通过 application.properties 中的 v4.chat.* 配置项控制。
 */
@Slf4j
public class ChatMessageActionDeveloper {

    // ── 配置（从 CoreConfig 读取，类加载时已初始化） ──
    private final int minMessages;
    private final long maxWaitMs;
    private final long cooldownMs;
    private final int privateMinMessages;

    // ── 每 source 一个累加桶 ──
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    // ── flush 后的冷却期记录 ──
    private final Map<String, Long> cooldownUntil = new ConcurrentHashMap<>();

    public ChatMessageActionDeveloper() {
        this.minMessages = CoreConfig.CHAT_BATCH_MIN_MESSAGES;
        this.maxWaitMs = CoreConfig.CHAT_BATCH_MAX_WAIT_MS;
        this.cooldownMs = CoreConfig.CHAT_BATCH_COOLDOWN_MS;
        this.privateMinMessages = CoreConfig.CHAT_BATCH_PRIVATE_MIN_MESSAGES;
        log.info("[ChatDev] 初始化: minMessages={}, maxWaitMs={}, cooldownMs={}, privateMinMessages={}",
                minMessages, maxWaitMs, cooldownMs, privateMinMessages);
    }

    /**
     * 每个 tick 调用：接收本 tick 的所有原始输入，返回满足 flush 条件的 CognitivePrepareUnit 列表。
     *
     * @param rawInputs 本 tick 从 AgentInputTasksQueue 中捞出的所有输入
     * @return 已聚合好、可以直接 push 到准备池的单元列表
     */
    public List<CognitivePrepareUnit> accumulate(List<DefaultAgentInputUnit> rawInputs) {
        if (rawInputs == null || rawInputs.isEmpty()) {
            return List.of();
        }

        int totalMessages = rawInputs.size();
        int bucketedCount = 0;
        int skippedCount = 0;

        // —— 第一步：所有输入按 source 分桶累积 ——
        for (DefaultAgentInputUnit input : rawInputs) {
            String source = extractSource(input);
            if (source == null || source.isBlank()) {
                skippedCount++;
                continue;
            }

            String text = extractText(input);
            if (text == null || text.isBlank()) {
                skippedCount++;
                continue;
            }

            // 提取 ChatMessageInput 的 recentHistory（仅第一条消息的会被保留）
            String recentHistory = "";
            if (input instanceof ChatMessageInput chatMsg) {
                String rh = chatMsg.getRecentHistory();
                if (rh != null && !rh.isBlank()) recentHistory = rh;
            }

            boolean isPrivate = source.contains("private") || source.contains("dm:");
            boolean hasAtMention = detectAtMention(text, input);

            Bucket bucket = buckets.computeIfAbsent(source, k -> new Bucket(source, isPrivate));
            bucket.add(text, hasAtMention, recentHistory);
            bucketedCount++;
        }

        if (totalMessages > 0) {
            log.info("[ChatDev] 📥 本 tick 收到 {} 条消息 → {} 入桶, {} 跳过 (活跃桶: {})",
                    totalMessages, bucketedCount, skippedCount, buckets.size());
        }

        // —— 第二步：检查每个桶是否需要 flush ——
        List<CognitivePrepareUnit> ready = new ArrayList<>();
        long now = System.currentTimeMillis();
        List<String> flushedSources = new ArrayList<>();

        for (Bucket bucket : buckets.values()) {
            if (shouldFlush(bucket, now)) {
                CognitivePrepareUnit unit = bucket.flush();
                if (unit != null) {
                    ready.add(unit);
                    flushedSources.add(bucket.source);
                }
            }
        }

        // 清理已 flush 的桶
        for (String src : flushedSources) {
            buckets.remove(src);
            cooldownUntil.put(src, now + cooldownMs);
        }

        if (!ready.isEmpty()) {
            log.info("[ChatDev] 🔥 flush {} 个单元 → 准备池 (剩余桶: {})",
                    ready.size(), buckets.size());
        }

        return ready;
    }

    /**
     * 强制 flush 所有桶（用于关闭时清空）。
     */
    public List<CognitivePrepareUnit> flushAll() {
        List<CognitivePrepareUnit> all = new ArrayList<>();
        for (Bucket bucket : buckets.values()) {
            CognitivePrepareUnit unit = bucket.flush();
            if (unit != null) {
                all.add(unit);
            }
        }
        buckets.clear();
        cooldownUntil.clear();
        if (!all.isEmpty()) {
            log.info("[ChatDev] 🔥 flushAll: {} 个单元", all.size());
        }
        return all;
    }

    /** 获取当前活跃桶数（调试用） */
    public int getActiveBucketCount() {
        return buckets.size();
    }

    // ==========================================
    // Flush 判断
    // ==========================================

    private boolean shouldFlush(Bucket bucket, long now) {
        // 冷却期内不 flush
        Long cooldownEnd = cooldownUntil.get(bucket.source);
        if (cooldownEnd != null && now < cooldownEnd) {
            return false;
        }

        // 条件 1：含 @提及 → 立即 flush
        if (bucket.atMentionCount > 0) {
            log.debug("[ChatDev]   bucket [{}] flush 原因: @提及 ({}次)", bucket.source, bucket.atMentionCount);
            return true;
        }

        // 条件 2：攒够消息数（私聊用更低阈值）
        int threshold = bucket.isPrivate ? privateMinMessages : minMessages;
        if (bucket.messageCount >= threshold) {
            log.debug("[ChatDev]   bucket [{}] flush 原因: 攒够 {}条 >= {}条",
                    bucket.source, bucket.messageCount, threshold);
            return true;
        }

        // 条件 3：等够时间
        long elapsed = now - bucket.firstMessageTime;
        if (elapsed >= maxWaitMs) {
            log.debug("[ChatDev]   bucket [{}] flush 原因: 等待 {}ms >= {}ms",
                    bucket.source, elapsed, maxWaitMs);
            return true;
        }

        return false;
    }

    // ==========================================
    // 输入解析（从 ActionLoop 移入）
    // ==========================================

    private String extractSource(DefaultAgentInputUnit input) {
        if (input instanceof ChatMessageInput chatInput) {
            String source = chatInput.getSource();
            if (source != null && !source.isBlank()) return source;
            String sourceName = chatInput.getSource_name();
            if (sourceName != null && !sourceName.isBlank()) return "chat:" + sourceName;
            return "chat:unknown";
        }
        if (input instanceof WebEventInput) {
            return "web_event";
        }
        return "system:" + input.getClass().getSimpleName();
    }

    private String extractText(DefaultAgentInputUnit input) {
        if (input instanceof ChatMessageInput chatInput) {
            String text = chatInput.getInputText();
            if (text != null && !text.isBlank()) return text;
            return chatInput.getContent();
        }
        if (input instanceof WebEventInput webInput) {
            return webInput.toString();
        }
        return input.toString();
    }

    private boolean detectAtMention(String text, DefaultAgentInputUnit input) {
        if (text == null) return false;
        // 直接文本检测
        if (text.contains("@")) return true;
        // ChatMessageInput 的结构化检测
        if (input instanceof ChatMessageInput chatInput) {
            if (chatInput.isAtMe()) return true;
            if (chatInput.getAtTargets() != null && !chatInput.getAtTargets().isEmpty()) return true;
        }
        return false;
    }

    // ==========================================
    // SE 计算
    // ==========================================

    /**
     * 计算刺激能量 SE。
     * 基础值 × 消息量因子 × @提及加成 × 私聊加成。
     */
    private double computeSE(int messageCount, int atCount, boolean isPrivate) {
        double baseSE = 0.5;
        double volumeFactor = 1.0 + Math.log1p(messageCount) * 0.5;
        double se = baseSE * volumeFactor;
        if (atCount > 0) {
            se *= 1.0 + Math.min(0.5, atCount * 0.1);
        }
        if (isPrivate) {
            se *= 1.2; // 私聊天然更需要关注
        }
        return se;
    }

    // ==========================================
    // 累加桶
    // ==========================================

    private class Bucket {
        final String source;
        final boolean isPrivate;
        final long firstMessageTime;
        final List<String> texts = new ArrayList<>();
        int messageCount;
        int atMentionCount;
        String firstRecentHistory = "";  // 第一条消息附带的上下文史，flush 时只注入一次

        Bucket(String source, boolean isPrivate) {
            this.source = source;
            this.isPrivate = isPrivate;
            this.firstMessageTime = System.currentTimeMillis();
        }

        void add(String text, boolean hasAt, String recentHistory) {
            texts.add(text);
            messageCount++;
            if (hasAt) atMentionCount++;
            // 只保留第一条消息的历史（群聊场景下所有消息拿到的历史是一样的）
            if (firstRecentHistory.isEmpty() && recentHistory != null && !recentHistory.isBlank()) {
                firstRecentHistory = recentHistory;
            }
        }

        CognitivePrepareUnit flush() {
            if (texts.isEmpty()) return null;

            String messagesPart = String.join("\n---\n", texts);

            // 最近聊天历史只注一次（不重复在每条消息里）
            String combined;
            if (!firstRecentHistory.isBlank()) {
                combined = "[最近聊天记录]\n" + firstRecentHistory
                        + "\n--- 最新消息 ---\n" + messagesPart;
            } else {
                combined = messagesPart;
            }

            double se = computeSE(messageCount, atMentionCount, isPrivate);

            CognitivePrepareUnit unit = CognitivePrepareUnit.create(
                    combined, List.of(source), se);

            log.info("[ChatDev]   🪣 flush bucket [" + source + "]: " + messageCount + "条消息, "
                    + combined.length() + "chars, SE=" + String.format("%.3f", se)
                    + ", private=" + isPrivate + ", atCount=" + atMentionCount);

            return unit;
        }
    }
}
