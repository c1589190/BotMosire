package com.cna.agent.AgentInput;

import com.cna.Utils;
import lombok.Getter;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * 平台无关的聊天消息抽象。NapcatAdapter / DiscordAdapter 各自负责将平台事件翻译为此结构。
 * 新字段均有默认值，未适配的 adapter（如 Discord）使用旧构造器即可正常工作。
 */
public class ChatMessageInput implements DefaultAgentInputUnit {
    private UUID uuid;
    @Getter
    private String source;       // 平台无关来源标识 (e.g. "qq_group:xxx", "discord_dm:xxx")
    @Getter
    private String source_name;  // 可读来源名 (e.g. "BotMosire灰测群", "Discord私聊")
    @Getter
    private String role;         // 发送者标识 (e.g. "qqid:xxx", "discordid:xxx")
    @Getter
    private String role_name;    // 发送者昵称
    @Getter
    private String content;      // 已解析为纯文本的消息正文
    private long quotedMessageId = 0;

    // ========== 平台无关的结构化元数据（默认值保证向后兼容） ==========
    @Getter
    private boolean isPrivate;         // 私聊/群聊
    @Getter
    private boolean isAtMe;            // 自身是否被 @提及
    @Getter
    private List<String> atTargets;    // 消息中所有被 @ 的用户 ID 列表
    @Getter
    private String senderCard;         // 发送者在此场景下的展示名（QQ群名片 / Discord公会昵称），可能为空
    @Getter
    private String senderGroupRole;    // 发送者在此场景下的角色（owner/admin/member），非群聊场景为空
    @Getter
    private String subType;            // 消息子类型（平台相关：normal/anonymous/notice...）
    @Getter
    private boolean hasForward;        // 是否包含合并转发内容
    @Getter
    private String richSummary;        // 富媒体摘要，如 "[图片x2][@提及][合并转发]"，方便 LLM 快速感知

    // 最近聊天历史（由 adapter 在创建 Input 时注入，ActionLoop 无需关心）
    private String recentHistory = "";

    // ★ 文件附件信息（NapcatAdapter 收到文件消息时填充）
    @Getter
    private List<FileAttachment> fileAttachments = Collections.emptyList();

    /** 文件附件记录 */
    public record FileAttachment(
            String fileName,
            String fileId,       // Napcat file_id，用于调用 /get_file API
            long fileSize,
            int busid,           // 群文件业务 ID，调用 /get_group_file_url 时需要
            String virtualLink,  // 虚拟链接，如 napcat://group/12345/file/abc123/报告.pdf
            String realUrl       // 事件中直接携带的真实 URL（如果有）
    ) {}

    // ========== 兼容旧构造器（DiscordAdapter 等未适配的调用方继续使用） ==========

    public ChatMessageInput(String source, String source_name, String role, String role_name, String content) {
        this(source, source_name, role, role_name, content, 0, false, false,
                Collections.emptyList(), "", "", "normal", false, "");
    }

    public ChatMessageInput(String source, String source_name, String role, String role_name,
                            String content, long quotedMessageId) {
        this(source, source_name, role, role_name, content, quotedMessageId, false, false,
                Collections.emptyList(), "", "", "normal", false, "");
    }

    // ========== 完整构造器 ==========

    public ChatMessageInput(String source, String source_name, String role, String role_name,
                            String content, long quotedMessageId,
                            boolean isPrivate, boolean isAtMe, List<String> atTargets,
                            String senderCard, String senderGroupRole, String subType,
                            boolean hasForward, String richSummary) {
        uuid = UUID.randomUUID();
        this.source = source;
        this.source_name = source_name;
        this.role = role;
        this.role_name = role_name;
        this.content = content;
        this.quotedMessageId = quotedMessageId;
        this.isPrivate = isPrivate;
        this.isAtMe = isAtMe;
        this.atTargets = atTargets != null ? atTargets : Collections.emptyList();
        this.senderCard = senderCard != null ? senderCard : "";
        this.senderGroupRole = senderGroupRole != null ? senderGroupRole : "";
        this.subType = subType != null ? subType : "normal";
        this.hasForward = hasForward;
        this.richSummary = richSummary != null ? richSummary : "";
    }

    public long getQuotedMessageId() { return quotedMessageId; }
    public boolean hasQuotedMessage() { return quotedMessageId > 0; }

    /** 由 adapter 在创建 Input 时注入最近聊天历史 */
    public void setRecentHistory(String history) {
        this.recentHistory = history != null ? history : "";
    }

    /** 由 adapter 在创建 Input 时注入文件附件列表 */
    public void setFileAttachments(List<FileAttachment> attachments) {
        this.fileAttachments = attachments != null ? List.copyOf(attachments) : Collections.emptyList();
    }

    @Override
    public String getInputText() {
        StringBuilder ret = new StringBuilder();

        // 最近聊天历史（adapter 注入，提供完整上下文）
        if (!recentHistory.isBlank()) {
            ret.append("[最近聊天记录]\n");
            ret.append(recentHistory);
            ret.append("\n--- 最新消息 ---\n");
        }

        ret.append(Utils.getNowPrecise()).append(",");

        // 聊天类型标签
        if (isPrivate) {
            ret.append("[私聊] ");
        } else if (source.startsWith("qq_group:") || source.startsWith("discord_guild:")) {
            ret.append("[群聊] ");
        }

        // 自身被 @ 提示
        if (isAtMe) {
            ret.append("(自身被@提及) ");
        }

        ret.append("来源于 ").append(this.source_name).append(" (").append(this.source).append(") ");
        ret.append("的 ").append(this.role_name).append(" (").append(this.role).append(")");

        // 发送者场景内身份
        if (!senderCard.isBlank() && !senderCard.equals(this.role_name)) {
            ret.append(" [群名片:").append(senderCard).append("]");
        }
        if (!senderGroupRole.isBlank() && !"member".equals(senderGroupRole)) {
            ret.append(" [角色:").append(senderGroupRole).append("]");
        }

        // 回复引用
        if (quotedMessageId > 0) {
            ret.append(" 引用回复了消息[").append(quotedMessageId).append("]");
        }

        // 富媒体摘要（让 LLM 快速感知消息里有什么）
        if (!richSummary.isBlank()) {
            ret.append(" ").append(richSummary);
        }

        ret.append(" 发送了: {\n").append(this.content).append("\n};");

        // ★ 文件附件下载链接
        if (!fileAttachments.isEmpty()) {
            ret.append("\n📎 附带文件 (").append(fileAttachments.size()).append("个):");
            for (FileAttachment fa : fileAttachments) {
                String sizeStr = fa.fileSize > 1_000_000
                        ? String.format("%.1fMB", fa.fileSize / 1_000_000.0)
                        : fa.fileSize > 1_000 ? String.format("%.1fKB", fa.fileSize / 1_000.0)
                        : fa.fileSize + "B";
                ret.append("\n  - ").append(fa.fileName).append(" (").append(sizeStr).append(")");
                if (fa.realUrl != null && !fa.realUrl.isBlank()) {
                    ret.append("\n    🔗 真实链接: ").append(fa.realUrl);
                } else {
                    ret.append("\n    📥 下载链接: ").append(fa.virtualLink);
                }
            }
            ret.append("\n💡 你可以用 download_chat_file 工具下载这些文件。");
        }

        return ret.toString();
    }

    @Override
    public UUID getUUID() {
        return uuid;
    }
}