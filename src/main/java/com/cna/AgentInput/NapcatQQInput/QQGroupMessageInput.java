package com.cna.AgentInput.NapcatQQInput;

import com.cna.AgentInput.AgentInputType;
import com.cna.AgentInput.DefaultAgentInputUnit;
import lombok.Getter;

import java.util.UUID;

public class QQGroupMessageInput implements DefaultAgentInputUnit {
    private UUID uuid;
    private String Time;
    private String GroupID;
    private String GroupName;
    @Getter
    private String SenderID;
    private String SenderName;
    private String Context;
    public QQGroupMessageInput(String Time, String GroupID, String GroupName, String SenderID, String SenderName, String Context){
        this.uuid = UUID.randomUUID();
        this.Time = Time;
        this.GroupID = GroupID;
        this.GroupName = GroupName;
        this.SenderID = SenderID;
        this.SenderName = SenderName;
        this.Context = Context;
    }
    @Override
    public String getInputText() {
        StringBuilder ret = new StringBuilder();
        ret.append("在").append(this.Time).append(", ");
        ret.append("群聊").append(this.GroupName).append("(群号").append(this.GroupID).append(")中, ");
        ret.append(this.SenderName).append("(QQ号").append(this.SenderID).append(") 发送了消息:{\"").append(this.Context).append("\"};");
        return ret.toString();
    }
    @Override
    public UUID getUUID() {
        return this.uuid;
    }
    @Override
    public AgentInputType getType() {return AgentInputType.QQGroupMessage;}
}
