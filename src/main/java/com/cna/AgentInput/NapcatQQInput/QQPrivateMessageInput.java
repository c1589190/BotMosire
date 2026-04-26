package com.cna.AgentInput.NapcatQQInput;

import com.cna.AgentInput.AgentInputType;
import com.cna.AgentInput.DefaultAgentInputUnit;
import lombok.Getter;

import java.util.UUID;

public class QQPrivateMessageInput implements DefaultAgentInputUnit {
    private UUID uuid;
    private String Time;
    @Getter
    private String SenderID;
    private String SenderName;
    private String Context;
    public QQPrivateMessageInput(String Time, String SenderID, String SenderName, String Context){
        this.Time = Time;
        this.SenderID = SenderID;
        this.SenderName = SenderName;
        this.Context = Context;
    }
    @Override
    public String getInputText() {
        StringBuilder ret = new StringBuilder();
        ret.append("在").append(this.Time).append(", ");
        ret.append(this.SenderName).append("(QQ号").append(this.SenderID).append(") 在私聊中向你发送了消息:{\"").append(this.Context).append("\"};");
        return ret.toString();
    }
    @Override
    public UUID getUUID() {
        return this.uuid;
    }
    @Override
    public AgentInputType getType() {return AgentInputType.QQPrivateMessage;}
}
