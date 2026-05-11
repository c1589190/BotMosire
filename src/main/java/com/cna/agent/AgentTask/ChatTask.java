package com.cna.agent.AgentTask;

import lombok.Getter;
import java.util.ArrayList;
import java.util.List;

public class ChatTask extends AbstractAgentTask {

    private String source;
    private String source_name;
    @Getter
    private String role;
    private String role_name;

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