package com.cna.agent.AgentTask;

import lombok.Getter;

public class WebEventTask extends AbstractAgentTask {

    @Getter
    private final String ipAddress;
    @Getter
    private final String rawJson;

    public WebEventTask(String ipAddress, String rawJson) {
        super(); // 调用父类构造器，自动分配 UUID 并初始化基础状态

        // 网页事件通常需要即时响应，优先级设为 2.0 (数字越小优先级越高，比 ChatTask 的默认 3.0 更优先)
        this.priority = 2.0;

        this.ipAddress = ipAddress;
        this.rawJson = rawJson;
    }

    @Override
    public String getTaskText() {
        // 实现接口要求的描述方法，供 Handler 提取并喂给大模型
        return "来自网页前端的交互事件:\n" +
                "来源IP: " + this.ipAddress + "\n" +
                "事件数据(JSON): {\n" + this.rawJson + "\n}";
    }
}