package com.cna;

import lombok.extern.slf4j.Slf4j;

import java.util.List;

import static com.cna.Main.GlobalDiscordAdapter;
import static com.cna.Main.GlobalNapcatAdapter;

@Slf4j
public class ChatAdaptersManager {

    public static String send(String namespace, String message, long replyToId) {
        try {
            if (namespace.startsWith("qq_group:")) {
                if (GlobalNapcatAdapter == null) return "ERROR: QQ 适配器未启动，无法发送群聊消息。";
                long groupId = Long.parseLong(namespace.substring(9));
                if (replyToId > 0) {
                    GlobalNapcatAdapter.sendGroupMsgWithReply(groupId, message, replyToId);
                    log.info("[ChatAdaptersManager] 向群聊 [{}] 发送了引用回复，引用ID: {}", groupId, replyToId);
                } else {
                    GlobalNapcatAdapter.sendGroupMsg(groupId, message);
                    log.info("[ChatAdaptersManager] 向群聊 [{}] 发送了消息", groupId);
                }
                return "SUCCESS: 消息已成功发送至群聊 " + namespace;

            } else if (namespace.startsWith("qqid:")) {
                if (GlobalNapcatAdapter == null) return "ERROR: QQ 适配器未启动，无法发送私聊消息。";
                long userId = Long.parseLong(namespace.substring(5));
                if (replyToId > 0) {
                    GlobalNapcatAdapter.sendPrivateMsgWithReply(userId, message, replyToId);
                    log.info("[ChatAdaptersManager] 向用户 [{}] 发送了引用回复私聊，引用ID: {}", userId, replyToId);
                } else {
                    GlobalNapcatAdapter.sendPrivateMsg(userId, message);
                    log.info("[ChatAdaptersManager] 向用户 [{}] 发送了私聊消息", userId);
                }
                return "SUCCESS: 消息已成功发送至用户 " + namespace;

            } else if (namespace.startsWith("qq_private:")) {
                if (GlobalNapcatAdapter == null) return "ERROR: QQ 适配器未启动，无法发送私聊消息。";
                long userId = Long.parseLong(namespace.substring("qq_private:".length()));
                if (replyToId > 0) {
                    GlobalNapcatAdapter.sendPrivateMsgWithReply(userId, message, replyToId);
                } else {
                    GlobalNapcatAdapter.sendPrivateMsg(userId, message);
                }
                log.info("[ChatAdaptersManager] 向用户 [{}] 发送了私聊消息(source路由)", userId);
                return "SUCCESS: 消息已成功发送至用户 " + namespace;

            } else if (namespace.startsWith("discord_dm:") || namespace.startsWith("discord_guild:")) {
                if (GlobalDiscordAdapter == null || !GlobalDiscordAdapter.isConnected()) {
                    return "ERROR: Discord adapter 未連線或未啟用。";
                }
                List<String> chunks = Utils.splitForDiscord(message);
                for (String chunk : chunks) {
                    String r = GlobalDiscordAdapter.sendMessage(namespace, chunk);
                    if (r.startsWith("ERROR")) {
                        log.warn("[ChatAdaptersManager] Discord 分段發送失敗: {}", r);
                        return r;
                    }
                }
                log.info("[ChatAdaptersManager] Discord 發送完畢 [{}]，共 {} 段", namespace, chunks.size());
                return "SUCCESS: 消息已发送至 Discord " + namespace;

            } else {
                log.warn("[ChatAdaptersManager] 无法识别的 namespace: {}", namespace);
                return "ERROR: 无法识别的 namespace 格式。支持 'qq_group:'、'qqid:'、'qq_private:'、'discord_dm:'、'discord_guild:' 前缀。";
            }
        } catch (NumberFormatException e) {
            log.error("[ChatAdaptersManager] ID 解析错误: {}", namespace, e);
            return "ERROR: namespace 前缀后的 ID 必须是有效的数字。";
        } catch (Exception e) {
            log.error("[ChatAdaptersManager] 发送消息异常", e);
            return "ERROR: 消息发送失败，底层异常: " + e.getMessage();
        }
    }

    public static String getHistory(String namespace, int count) {
        try {
            List<String> historyList;
            String chatTypeDesc;

            if (namespace.startsWith("qq_group:")) {
                if (GlobalNapcatAdapter == null) return "ERROR: QQ 适配器未启动。";
                long groupId = Long.parseLong(namespace.substring(9));
                historyList = GlobalNapcatAdapter.getGroupHistorySync(groupId, count);
                chatTypeDesc = "QQ群聊";
            } else if (namespace.startsWith("qqid:")) {
                if (GlobalNapcatAdapter == null) return "ERROR: QQ 适配器未启动。";
                long userId = Long.parseLong(namespace.substring(5));
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
                return "ERROR: 无法识别的 namespace 格式。支持 'qq_group:'、'qqid:'、'discord_dm:'、'discord_guild:' 前缀。";
            }

            if (historyList == null || historyList.isEmpty()) {
                return "SYSTEM_FEEDBACK: 该目标没有最近的历史记录，或物理层获取失败。";
            }

            return String.format("【以下是目标 %s 的最近 %d 条%s记录】:\n%s",
                    namespace, historyList.size(), chatTypeDesc, String.join("\n", historyList));

        } catch (NumberFormatException e) {
            log.error("[ChatAdaptersManager] ID 解析错误: {}", namespace, e);
            return "ERROR: namespace 前缀后的 ID 必须是数字";
        } catch (Exception e) {
            log.error("[ChatAdaptersManager] 获取历史记录异常", e);
            return "ERROR: 获取历史记录失败，底层异常: " + e.getMessage();
        }
    }
}
