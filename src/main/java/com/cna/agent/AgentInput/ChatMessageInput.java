package com.cna.agent.AgentInput;

import com.cna.Utils;
import lombok.Getter;

import java.util.UUID;

public class ChatMessageInput implements DefaultAgentInputUnit {
    private UUID uuid;
    @Getter
    private String source; //消息来源
    @Getter
    private String source_name;
    @Getter
    private String role; //发送角色
    @Getter
    private String role_name;
    @Getter
    private String content; //具体内容
    private long quotedMessageId = 0; //被引用回覆的源消息ID（Napcat message_id），0表示无引用

    public ChatMessageInput(String source, String source_name, String role, String role_name, String content) {
        uuid = UUID.randomUUID();
        this.source = source;
        this.source_name = source_name;
        this.role = role;
        this.role_name = role_name;
        this.content = content;
        this.quotedMessageId = 0;
    }

    public ChatMessageInput(String source, String source_name, String role, String role_name, String content, long quotedMessageId) {
        uuid = UUID.randomUUID();
        this.source = source;
        this.source_name = source_name;
        this.role = role;
        this.role_name = role_name;
        this.content = content;
        this.quotedMessageId = quotedMessageId;
    }

    /** 被引用回覆的源消息ID，0表示这条消息不是在回复某人 */
    public long getQuotedMessageId() { return quotedMessageId; }
    public boolean hasQuotedMessage() { return quotedMessageId > 0; }

    @Override
    public String getInputText() {
        StringBuilder ret = new StringBuilder();
        ret.append(Utils.getNowPrecise()).append(",");
        ret.append("来源于 " + this.source_name + " (" + this.source + ") ");
        ret.append("的 " + this.role_name + " (" + this.role + ") ");
        if (quotedMessageId > 0) {
            ret.append("引用回复了消息 [" + quotedMessageId + "] ");
        }
        ret.append("发送了: {\n" + this.content + "\n};");
        return ret.toString();
    }

    @Override
    public UUID getUUID() {
        return uuid;
    }
}