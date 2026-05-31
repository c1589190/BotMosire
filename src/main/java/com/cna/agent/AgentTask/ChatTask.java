package com.cna.agent.AgentTask;

import lombok.Getter;
import java.util.ArrayList;
import java.util.List;

@Deprecated
public class ChatTask extends AbstractAgentTask {

    @Getter
    private String source;
    private String source_name;
    @Getter
    private String role;
    private String role_name;

    /** 是否为私聊——影响 getSources() 的行为：私聊仅标注 role，不重复标注 source */
    @Getter
    private boolean isPrivate = false;

    private List<String> Contexts = new ArrayList<>();

    @Getter
    private int static_cnt = 0;
    @Getter
    private long replyToMessageId = 0;

    public ChatTask(String source, String source_name, String role, String role_name) {
        super(); // 调用父类构造器生成 UUID
        this.source = source;
        this.source_name = source_name;
        this.role = role;
        this.role_name = role_name;
        this.replyToMessageId = 0;
    }

    public ChatTask(String source, String source_name, String role, String role_name, long replyToMessageId) {
        this(source, source_name, role, role_name);
        this.replyToMessageId = replyToMessageId;
    }

    public ChatTask(String source, String source_name, String role, String role_name, long replyToMessageId, boolean isPrivate) {
        this(source, source_name, role, role_name, replyToMessageId);
        this.isPrivate = isPrivate;
    }

    @Override
    public List<String> getSources() {
        List<String> sources = new ArrayList<>();
        // 私聊：仅标注用户 ID（私聊环境已在语义中）
        // 群聊：标注群 ID + 用户 ID
        if (!isPrivate && source != null && !source.isBlank()) {
            sources.add(source);
        }
        if (role != null && !role.isBlank()) {
            sources.add(role);
        }
        return sources.isEmpty() ? List.of("system:internal") : sources;
    }

    public void addContext(String text) {
        this.Contexts.add(text);
        this.static_cnt = 0;
    }

    public void addStatic() {
        this.static_cnt++;
    }

    public void setReplyToMessageId(long id) {
        this.replyToMessageId = id;
    }

    public void setPriority(double priority) {
        this.priority = priority;
    }

    @Override
    public String getTaskText() {
        StringBuilder ret = new StringBuilder();
        ret.append("在 ").append(this.source_name).append("(").append(this.source).append(") 中, ");
        ret.append(this.role_name).append("(").append(this.role).append(") 发送了若干消息:{\n");
        for (String context : Contexts) {
            ret.append(context).append("\n");
        }
        ret.append("\n};");
        return ret.toString();
    }
}