package com.cna.agent;

import com.cna.config.ConfigsManager;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 消息关键词系统 — 允许 LLM 通过 tool 自行设置关键词，
 * 当 ChatMessageInput 的任意字段命中关键词时，给予额外权重加分。
 * <p>
 * 持久化：关键词以 JSON 形式存储于 message_keywords.json，启动时自动加载。
 * <p>
 * 线程安全：基于 ConcurrentHashMap，无外部锁。
 */
@Slf4j
public class MessageKeywordManager {

    private static final String PERSIST_FILE = "message_keywords.json";
    private static final ObjectMapper jsonMapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    /** 单线程异步写入，避免并发写文件导致内容交错 */
    private static final ExecutorService persistExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "keyword-persist");
        t.setDaemon(true);
        return t;
    });

    @Getter
    private static volatile MessageKeywordManager instance;

    public static synchronized void init() {
        if (instance == null) {
            instance = new MessageKeywordManager();
            instance.loadFromFile();
        }
    }

    public static MessageKeywordManager getInstance() {
        if (instance == null) {
            synchronized (MessageKeywordManager.class) {
                if (instance == null) {
                    instance = new MessageKeywordManager();
                    instance.loadFromFile();
                }
            }
        }
        return instance;
    }

    // ==========================================
    // 数据结构
    // ==========================================

    public static class KeywordEntry {
        public String keyword;       // 原始关键词（匹配时统一 toLowerCase）
        public volatile double weight;      // 命中时累加的权重
        public long createdAt;        // 创建时间戳 (ms)
        public volatile long expiresAt;     // 过期时间戳 (ms)，0 = 永不过期

        /** Jackson 反序列化用 */
        public KeywordEntry() {}

        public KeywordEntry(String keyword, double weight, long expiresAt) {
            this.keyword = keyword;
            this.weight = weight;
            this.createdAt = System.currentTimeMillis();
            this.expiresAt = expiresAt;
        }

        public boolean isExpired() {
            return expiresAt > 0 && System.currentTimeMillis() > expiresAt;
        }

        public long remainingMinutes() {
            if (expiresAt <= 0) return -1; // 永不过期
            long remaining = expiresAt - System.currentTimeMillis();
            return remaining > 0 ? remaining / 60_000 : 0;
        }
    }

    // ==========================================
    // 存储
    // ==========================================

    /** key = keyword.toLowerCase()，保证大小写不敏感的唯一性 */
    private final ConcurrentHashMap<String, KeywordEntry> keywords = new ConcurrentHashMap<>();

    // ==========================================
    // 管理接口
    // ==========================================

    /**
     * 添加或更新关键词。weight 会自动钳位到配置范围。
     * @return 操作结果描述
     */
    public String addKeyword(String keyword, double weight, int ttlMinutes) {
        if (keyword == null || keyword.isBlank()) {
            return "错误：关键词不能为空。";
        }

        String normalized = keyword.trim().toLowerCase();

        // weight 钳位
        double clampedWeight = Math.max(ConfigsManager.MESSAGE_KEYWORD_WEIGHT_MIN,
                Math.min(ConfigsManager.MESSAGE_KEYWORD_WEIGHT_MAX, weight));

        long expiresAt = ttlMinutes > 0
                ? System.currentTimeMillis() + (ttlMinutes * 60_000L)
                : 0;

        boolean existed = keywords.containsKey(normalized);
        keywords.put(normalized, new KeywordEntry(keyword.trim(), clampedWeight, expiresAt));
        persistAsync();

        String ttlInfo = ttlMinutes > 0 ? ttlMinutes + "分钟" : "永不过期";
        if (existed) {
            log.info("[MsgKeyword] 更新关键词: \"{}\" weight={} ttl={}", keyword.trim(), clampedWeight, ttlInfo);
            return String.format("已更新关键词 \"%s\"，权重=%.2f，有效期=%s。", keyword.trim(), clampedWeight, ttlInfo);
        }

        // 检查数量上限
        if (keywords.size() > ConfigsManager.MESSAGE_KEYWORD_MAX_COUNT) {
            // 清理过期
            clearExpired();
            if (keywords.size() > ConfigsManager.MESSAGE_KEYWORD_MAX_COUNT) {
                // 仍然超限，拒绝
                keywords.remove(normalized);
                return String.format("错误：关键词数量已达上限（%d个）。请先 remove 或 clear 后再添加。",
                        ConfigsManager.MESSAGE_KEYWORD_MAX_COUNT);
            }
        }

        log.info("[MsgKeyword] 新增关键词: \"{}\" weight={} ttl={}", keyword.trim(), clampedWeight, ttlInfo);
        return String.format("已添加关键词 \"%s\"，权重=%.2f，有效期=%s。", keyword.trim(), clampedWeight, ttlInfo);
    }

    /**
     * 精确删除一个关键词（大小写不敏感）。
     */
    public String removeKeyword(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return "错误：请指定要删除的关键词。";
        }
        String normalized = keyword.trim().toLowerCase();
        KeywordEntry removed = keywords.remove(normalized);
        if (removed != null) {
            persistAsync();
            log.info("[MsgKeyword] 删除关键词: \"{}\"", removed.keyword);
            return String.format("已删除关键词 \"%s\"。", removed.keyword);
        }
        return String.format("关键词 \"%s\" 不存在。", keyword.trim());
    }

    /**
     * 列出当前所有关键词。
     */
    public String listKeywords() {
        clearExpired();
        if (keywords.isEmpty()) {
            return "当前没有任何消息关键词。";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("当前消息关键词（共 ").append(keywords.size()).append(" 个）：\n");

        // 按创建时间排序
        keywords.values().stream()
                .sorted(Comparator.comparingLong(e -> e.createdAt))
                .forEach(e -> {
                    String ttlInfo = e.expiresAt <= 0 ? "永不过期" : "剩余约" + e.remainingMinutes() + "分钟";
                    sb.append(String.format("  \"%s\"  weight=%.2f  %s\n", e.keyword, e.weight, ttlInfo));
                });

        return sb.toString();
    }

    /**
     * 清空所有关键词。
     */
    public String clearKeywords() {
        int count = keywords.size();
        keywords.clear();
        persistAsync();
        log.info("[MsgKeyword] 清空全部 {} 个关键词", count);
        return String.format("已清空全部 %d 个关键词。", count);
    }

    // ==========================================
    // 持久化
    // ==========================================

    /** 异步将当前关键词写入 JSON 文件 */
    private void persistAsync() {
        List<KeywordEntry> snapshot = new ArrayList<>(keywords.values());
        CompletableFuture.runAsync(() -> {
            try {
                Path path = Paths.get(PERSIST_FILE);
                String json = jsonMapper.writeValueAsString(snapshot);
                Files.writeString(path, json, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            } catch (IOException e) {
                log.error("[MsgKeyword] 持久化关键词失败: {}", e.getMessage());
            }
        }, persistExecutor);
    }

    /** 启动时从 JSON 文件加载关键词 */
    private void loadFromFile() {
        Path path = Paths.get(PERSIST_FILE);
        if (!Files.exists(path)) {
            log.info("[MsgKeyword] 未发现持久化文件，从空集启动。");
            return;
        }
        try {
            String json = Files.readString(path, StandardCharsets.UTF_8);
            List<KeywordEntry> loaded = jsonMapper.readValue(json,
                    new TypeReference<List<KeywordEntry>>() {});
            for (KeywordEntry entry : loaded) {
                if (entry.keyword != null && !entry.keyword.isBlank() && !entry.isExpired()) {
                    keywords.put(entry.keyword.toLowerCase(), entry);
                }
            }
            log.info("[MsgKeyword] 从 {} 加载了 {} 个关键词", PERSIST_FILE, keywords.size());
        } catch (IOException e) {
            log.error("[MsgKeyword] 加载持久化文件失败: {}", e.getMessage());
        }
    }

    /**
     * 清理所有过期条目。
     */
    public void clearExpired() {
        keywords.entrySet().removeIf(e -> e.getValue().isExpired());
    }

    // ==========================================
    // 评估接口
    // ==========================================

    /**
     * 对一条 ChatMessageInput 的各字段做关键词命中评估，返回累加的总 bonus。
     * 任意字段包含关键词（大小写不敏感）即视为命中，累加该关键词的 weight。
     *
     * @param content    消息正文
     * @param role       发送者 role（如 qq_private:12345）
     * @param roleName   发送者名称
     * @param source     消息来源（如 qq_group:67890）
     * @param sourceName 来源名称
     * @return 所有命中关键词的权重累加值，无命中返回 0.0
     */
    public double evaluateInput(String content, String role, String roleName,
                                 String source, String sourceName) {
        if (keywords.isEmpty()) return 0.0;

        // 先清理一波过期
        clearExpired();
        if (keywords.isEmpty()) return 0.0;

        // 构建统一小写字段拼接串，一次遍历同时检查所有 keyword
        String lowerContent = content != null ? content.toLowerCase() : "";
        String lowerRole = role != null ? role.toLowerCase() : "";
        String lowerRoleName = roleName != null ? roleName.toLowerCase() : "";
        String lowerSource = source != null ? source.toLowerCase() : "";
        String lowerSourceName = sourceName != null ? sourceName.toLowerCase() : "";

        double totalBonus = 0.0;
        List<String> hitKeywords = null; // 惰性初始化

        for (KeywordEntry entry : keywords.values()) {
            if (entry.isExpired()) continue;
            String kw = entry.keyword.toLowerCase();

            boolean hit = lowerContent.contains(kw)
                    || lowerRole.contains(kw)
                    || lowerRoleName.contains(kw)
                    || lowerSource.contains(kw)
                    || lowerSourceName.contains(kw);

            if (hit) {
                totalBonus += entry.weight;
                if (hitKeywords == null) hitKeywords = new ArrayList<>();
                hitKeywords.add(entry.keyword);
            }
        }

        if (hitKeywords != null) {
            log.debug("[MsgKeyword] 命中 {} 个关键词: {} bonus={}", hitKeywords.size(), hitKeywords, totalBonus);
        }

        return totalBonus;
    }
}
