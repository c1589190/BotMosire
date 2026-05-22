package com.cna.agent.AgentInput;

import com.cna.Utils;
import lombok.Getter;

import java.util.UUID;

/**
 * 网页前端事件输入单元
 * 用于接收来自前端的任意自定义 JSON 数据（如按钮点击、表单提交等）
 */
public class WebEventInput implements DefaultAgentInputUnit {
    private final UUID uuid;

    @Getter
    private final String rawJson; // 前端传来的原始 JSON 字符串

    @Getter
    private final String ipAddress; // 来源 IP，可用于区分不同用户的浏览器

    public WebEventInput(String rawJson, String ipAddress) {
        this.uuid = UUID.randomUUID();
        this.rawJson = rawJson;
        this.ipAddress = ipAddress;
    }

    @Override
    public String getInputText() {
        StringBuilder ret = new StringBuilder();
        ret.append(Utils.getNowPrecise()).append(",");
        ret.append("来源于 Web前端面板 (IP: ").append(ipAddress).append(") ");
        ret.append("触发了自定义事件: {\n").append(this.rawJson).append("\n};");
        return ret.toString();
    }

    @Override
    public UUID getUUID() {
        return uuid;
    }
}