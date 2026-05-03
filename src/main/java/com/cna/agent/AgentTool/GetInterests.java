package com.cna.agent.AgentTool;

import com.cna.db.MDManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class GetInterests implements DefaultAgentToolUnit {

    private final ObjectMapper mapper = new ObjectMapper();

    // 标记该工具是否在当前周期内被真正调用过，用于完善记忆记录
    private boolean isCalled = false;

    public GetInterests() {
    }

    @Override
    public String getName() {
        return "get_interests";
    }

    @Override
    public ObjectNode getToolDefinition() {
        ObjectNode tool = mapper.createObjectNode();
        tool.put("type", "function");

        ObjectNode function = tool.putObject("function");
        function.put("name", getName());
        function.put("description", "当你需要查看当前的注意力雷达、兴趣列表或过滤规则时调用此工具。系统会自动为你展示 interests.md 文件中的最新内容。");

        ObjectNode parameters = function.putObject("parameters");
        parameters.put("type", "object");

        // 该工具不需要大模型提供任何参数，因此 properties 为空
        parameters.putObject("properties");

        return tool;
    }

    @Override
    public String execute(JsonNode arguments) {
        try {
            this.isCalled = true;
            log.info("[Tool][GetInterests] 大模型主动申请查阅当前兴趣列表 (interests.md)");

            // 直接利用项目中现有的 MDManager 读取文件内容
            String interestsContent = MDManager.read("interests.md", "");

            if (interestsContent == null || interestsContent.trim().isEmpty()) {
                return "SYSTEM_FEEDBACK: 当前 interests.md 文件为空，没有设定任何特定的兴趣或注意力过滤规则。";
            }

            return "【以下是当前的兴趣列表与注意力规则内容】:\n" + interestsContent;

        } catch (Exception e) {
            log.error("执行 get_interests 发生异常", e);
            return "ERROR: 获取兴趣列表失败，底层物理异常: " + e.getMessage();
        }
    }

    @Override
    public String getTextRecord() {
        if (!this.isCalled) {
            return "尝试通过工具查看兴趣列表，但是这个工具之前并没有被调用过;";
        } else {
            return "调用工具，查看了当前的兴趣雷达规则 (interests.md);";
        }
    }
}