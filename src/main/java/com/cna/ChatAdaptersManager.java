package com.cna;

import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.cna.Main.GlobalDiscordAdapter;
import static com.cna.Main.GlobalNapcatAdapter;

@Slf4j
public class ChatAdaptersManager {

    // getHistory TTL cache：避免 Gatekeeper 每次都同步呼叫平台 API
    private record CachedHistory(String content, long timestamp) {}
    private static final Map<String, CachedHistory> historyCache = new ConcurrentHashMap<>();
    private static final long HISTORY_TTL_MS    = 30_000; // 30 秒快取
    private static final long HISTORY_TIMEOUT_MS = 2_000; // 最多等 2 秒
    private static final ExecutorService historyFetcher = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "history-fetcher");
        t.setDaemon(true);
        return t;
    });

    public static String send(String namespace, String message, long replyToId) {
        try {
            if (namespace.startsWith("qq_group:")) {
                if (GlobalNapcatAdapter == null) return "ERROR: QQ 适配器未启动，无法发送群聊消息。";
                // 【修复】：用 length() 代替写死的数字，防手抖
                long groupId = Long.parseLong(namespace.substring("qq_group:".length()));
                if (replyToId > 0) {
                    GlobalNapcatAdapter.sendGroupMsgWithReply(groupId, message, replyToId);
                    log.info("[ChatAdaptersManager] 向群聊 [{}] 发送了引用回复，引用ID: {}", groupId, replyToId);
                } else {
                    GlobalNapcatAdapter.sendGroupMsg(groupId, message);
                    log.info("[ChatAdaptersManager] 向群聊 [{}] 发送了消息", groupId);
                }
                return "SUCCESS: 消息已成功发送至群聊 " + namespace;

            }  else if (namespace.startsWith("qq_private:") || namespace.startsWith("qqid:")) {
                if (GlobalNapcatAdapter == null) return "ERROR: QQ 适配器未启动，无法发送私聊消息。";
                // 兼容 qqid:senderId 形式（NapcatAdapter 用此当 role）
                int prefixLen = namespace.startsWith("qq_private:") ? "qq_private:".length() : "qqid:".length();
                long userId = Long.parseLong(namespace.substring(prefixLen));
                if (replyToId > 0) {
                    GlobalNapcatAdapter.sendPrivateMsgWithReply(userId, message, replyToId);
                } else {
                    GlobalNapcatAdapter.sendPrivateMsg(userId, message);
                }
                log.info("[ChatAdaptersManager] 向用户 [{}] 发送了私聊消息(source路由)", userId);
                return "SUCCESS: 消息已成功发送至用户 " + namespace;

            } else if (namespace.startsWith("discord_dm:") || namespace.startsWith("discord_guild:")) {
                if (GlobalDiscordAdapter == null || !GlobalDiscordAdapter.isConnected()) {
                    return "ERROR: Discord adapter 未连线或未启用。";
                }
                List<String> chunks = Utils.splitForDiscord(message);
                for (String chunk : chunks) {
                    String r = GlobalDiscordAdapter.sendMessage(namespace, chunk);
                    if (r.startsWith("ERROR")) {
                        log.warn("[ChatAdaptersManager] Discord 分段发送失败: {}", r);
                        return r;
                    }
                }
                log.info("[ChatAdaptersManager] Discord 发送完毕 [{}]，共 {} 段", namespace, chunks.size());
                return "SUCCESS: 消息已发送至 Discord " + namespace;

            } else {
                log.warn("[ChatAdaptersManager] 无法识别的 namespace: {}", namespace);
                return "ERROR: 无法识别的 namespace 格式。支持 'qq_group:'、'qq_private:'、'qqid:'、'discord_dm:'、'discord_guild:' 前缀。";
            }
        } catch (NumberFormatException e) {
            log.error("[ChatAdaptersManager] ID 解析错误: {}", namespace, e);
            return "ERROR: namespace 前缀后的 ID 必须是有效的数字。";
        } catch (Exception e) {
            log.error("[ChatAdaptersManager] 发送消息异常", e);
            return "ERROR: 消息发送失败，底层异常: " + e.getMessage();
        }
    }

    /**
     * 发送文件到指定目标（QQ群/私聊/Discord）
     */
    public static String sendFile(String namespace, Path filePath) {
        try {
            if (namespace.startsWith("qq_group:")) {
                if (GlobalNapcatAdapter == null) return "ERROR: QQ 适配器未启动。";
                // 🌟 修复魔法数字
                long groupId = Long.parseLong(namespace.substring("qq_group:".length()));
                return GlobalNapcatAdapter.sendGroupFile(groupId, filePath);

                // 🌟 移除了多余的 qqid: 检查，如果有历史遗留可以保留，但统一推荐使用 qq_private:
            } else if (namespace.startsWith("qq_private:") || namespace.startsWith("qqid:")) {
                if (GlobalNapcatAdapter == null) return "ERROR: QQ 适配器未启动。";
                int prefixLen = namespace.startsWith("qq_private:") ? "qq_private:".length() : "qqid:".length();
                long userId = Long.parseLong(namespace.substring(prefixLen));
                return GlobalNapcatAdapter.sendPrivateFile(userId, filePath);

            } else if (namespace.startsWith("discord_dm:") || namespace.startsWith("discord_guild:")) {
                if (GlobalDiscordAdapter == null || !GlobalDiscordAdapter.isConnected()) {
                    return "ERROR: Discord adapter 未连线或未启用。";
                }
                return GlobalDiscordAdapter.sendFile(namespace, filePath);

            } else {
                return "ERROR: 无法识别的 namespace 格式: " + namespace;
            }
        } catch (NumberFormatException e) {
            return "ERROR: namespace 中的 ID 必须是数字: " + namespace;
        } catch (Exception e) {
            log.error("[ChatAdaptersManager] sendFile 异常", e);
            return "ERROR: 发送文件失败 - " + e.getMessage();
        }
    }

    public static String getHistory(String namespace, int count) {
        long now = System.currentTimeMillis();
        CachedHistory cached = historyCache.get(namespace);

        // 快取未過期，直接返回（避免 Gatekeeper 同步 REST 卡 gatekeeperExecutor）
        if (cached != null && now - cached.timestamp() < HISTORY_TTL_MS) {
            return cached.content();
        }

        // 背景執行真實 API 呼叫，最多等 HISTORY_TIMEOUT_MS
        Future<String> future = historyFetcher.submit(() -> fetchHistorySync(namespace, count));
        try {
            String content = future.get(HISTORY_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            historyCache.put(namespace, new CachedHistory(content, System.currentTimeMillis()));
            return content;
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("[ChatAdaptersManager] getHistory({}) 超時 {}ms，返回{}快取",
                    namespace, HISTORY_TIMEOUT_MS, cached != null ? "舊" : "空");
            return cached != null ? cached.content() : "SYSTEM_FEEDBACK: 歷史記錄暫時無法獲取。";
        } catch (Exception e) {
            log.error("[ChatAdaptersManager] 获取历史记录异常", e);
            return cached != null ? cached.content() : "ERROR: 获取历史记录失败：" + e.getMessage();
        }
    }

    private static String fetchHistorySync(String namespace, int count) {
        try {
            List<String> historyList;
            String chatTypeDesc;

            if (namespace.startsWith("qq_group:")) {
                if (GlobalNapcatAdapter == null) return "ERROR: QQ 适配器未启动。";
                // 【修复】：用 length() 代替写死的 9
                long groupId = Long.parseLong(namespace.substring("qq_group:".length()));
                historyList = GlobalNapcatAdapter.getGroupHistorySync(groupId, count);
                chatTypeDesc = "QQ群聊";

            } else if (namespace.startsWith("qq_private:") || namespace.startsWith("qqid:")) {
                if (GlobalNapcatAdapter == null) return "ERROR: QQ 适配器未启动。";
                // 兼容 qqid:senderId 形式
                int prefixLen = namespace.startsWith("qq_private:") ? "qq_private:".length() : "qqid:".length();
                long userId = Long.parseLong(namespace.substring(prefixLen));
                historyList = GlobalNapcatAdapter.getFriendHistorySync(userId, count);
                chatTypeDesc = "QQ私聊";

            } else if (namespace.startsWith("discord_dm:")) {
                if (GlobalDiscordAdapter == null || !GlobalDiscordAdapter.isConnected())
                    return "ERROR: Discord 适配器未连线。";
                String userId = namespace.substring("discord_dm:".length());
                historyList = GlobalDiscordAdapter.getDmHistorySync(userId, count);
                chatTypeDesc = "Discord私聊";

            } else if (namespace.startsWith("discord_guild:")) {
                if (GlobalDiscordAdapter == null || !GlobalDiscordAdapter.isConnected())
                    return "ERROR: Discord 适配器未连线。";
                String[] parts = namespace.split(":");
                if (parts.length < 3) return "ERROR: discord_guild 格式需为 discord_guild:{guildId}:{channelId}";
                long channelId = Long.parseLong(parts[2]);
                historyList = GlobalDiscordAdapter.getChannelHistorySync(channelId, count);
                chatTypeDesc = "Discord频道";

            } else {
                log.warn("[ChatAdaptersManager] 无法识别的 namespace: {}", namespace);
                return "ERROR: 无法识别的 namespace 格式。支持 'qq_group:'、'qq_private:'、'qqid:'、'discord_dm:'、'discord_guild:' 前缀。";
            }

            if (historyList == null || historyList.isEmpty()) {
                return "SYSTEM_FEEDBACK: 该目标没有最近的历史记录，或物理层获取失败。";
            }

            return String.format("【以下是目标 %s 的最近 %d 条%s记录】:\n%s",
                    namespace, historyList.size(), chatTypeDesc, String.join("\n", historyList));

        } catch (NumberFormatException e) {
            log.error("[ChatAdaptersManager] ID 解析错误: {}", namespace, e);
            return "ERROR: namespace 前缀后的 ID 必须是纯数字";
        } catch (Exception e) {
            log.error("[ChatAdaptersManager] 获取历史记录异常", e);
            return "ERROR: 获取历史记录失败，底层异常: " + e.getMessage();
        }
    }
}