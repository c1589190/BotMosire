package com.cna.agent.AgentTask;

import lombok.Getter;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class ChatTask implements DefaultAgentTaskUnit{

    UUID uuid;
    private String source; //消息来源
    private String source_name;
    @Getter
    String role;
    String role_name;
    List<String> Contexts = new ArrayList<>();
    //储存每一条压入的相关信息
    @Getter
    int static_cnt = 0;
    //在任务更新循环中未被更新的次数
    @Getter
    private long replyToMessageId = 0; //被引用回覆的源消息ID，0表示無引用

    public ChatTask(String source, String source_name, String role, String role_name){
        this.source = source;
        this.source_name = source_name;
        this.uuid = UUID.randomUUID();
        this.role = role;
        this.role_name = role_name;
        this.replyToMessageId = 0;
    }

    public ChatTask(String source, String source_name, String role, String role_name, long replyToMessageId){
        this.source = source;
        this.source_name = source_name;
        this.uuid = UUID.randomUUID();
        this.role = role;
        this.role_name = role_name;
        this.replyToMessageId = replyToMessageId;
    }

    public void addContext(String text){
        this.Contexts.add(text);
        this.static_cnt = 0;
    }
    public void addStatic(){
        this.static_cnt++;
    }

    /** 設置被引用回覆的源消息ID */
    public void setReplyToMessageId(long id) {
        this.replyToMessageId = id;
    }

    @Override
    public UUID getUUID() {
        return this.uuid;
    }

    public AgentTaskType getType() {
        return AgentTaskType.QQChat;
    }

    @Override
    public String getTaskText() {
        StringBuilder ret = new StringBuilder();
        ret.append("在 " + this.source_name + "(" + this.source + ") 中, ");
        ret.append(this.role_name + "(" + this.role + ") 发送了若干消息:{\n");
        for(int i = 0; i < Contexts.size(); i++){
            ret.append(Contexts.get(i));
            ret.append("\n");
        }
        ret.append("\n};");
        return ret.toString();
    }
}
