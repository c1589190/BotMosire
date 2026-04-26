package com.cna.AgentTask;

import lombok.Getter;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class QQChatTask implements DefaultAgentTaskUnit{

    UUID uuid;
    @Getter
    Long SenderID;
    List<String> Contexts = new ArrayList<>();
    //储存每一条压入的相关信息
    @Getter
    int static_cnt = 0;
    //在任务更新循环中未被更新的次数

    public QQChatTask(Long SenderID){
        this.uuid = UUID.randomUUID();
        this.SenderID = SenderID;
    }

    public void addContext(String text){
        this.Contexts.add(text);
        this.static_cnt = 0;
    }
    public void addStatic(){
        this.static_cnt++;
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
        for(int i = 0; i < Contexts.size(); i++){
            ret.append(Contexts.get(i));
            ret.append("\n");
        }
        return ret.toString();
    }
}
