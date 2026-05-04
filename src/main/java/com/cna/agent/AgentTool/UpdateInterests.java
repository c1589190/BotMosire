package com.cna.agent.AgentTool;

import com.cna.db.MDManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class UpdateInterests implements DefaultAgentToolUnit {

    private final ObjectMapper mapper = new ObjectMapper();
    private static final String TARGET_FILE = "interests.md";

    @Override
    public String getName() {
        return "add_interest_rule"; // 语义改为“增加规则”
    }

    @Override
    public ObjectNode getToolDefinition() {
        ObjectNode tool = mapper.createObjectNode();
        tool.put("type", "function");

        ObjectNode function = tool.putObject("function");
        function.put("name", getName());
        // 说明书：明确告诉模型，这是在丰富雷达库
        function.put("description", "当你发现新的感兴趣话题，或需要增加新的噪音屏蔽规则时调用。这会将规则追加到注意力雷达文件中，底层感知系统会参考所有已记录的规则进行过滤。");

        ObjectNode parameters = function.putObject("parameters");
        parameters.put("type", "object");

        ObjectNode properties = parameters.putObject("properties");

        ObjectNode ruleItem = properties.putObject("rule_item");
        ruleItem.put("type", "string");
        ruleItem.put("description", "新增的兴趣描述或屏蔽规则（例如：‘关注所有关于量子计算的进展’ 或 ‘屏蔽所有带广告标签的消息’）。");

        ArrayNode required = parameters.putArray("required");
        required.add("rule_item");

        return tool;
    }

    @Override
    public String execute(JsonNode arguments) {
        try {
            String ruleItem = arguments.path("rule_item").asText();
            if (ruleItem == null || ruleItem.trim().isEmpty()) {
                return "ERROR: 规则内容不能为空。";
            }

            // 格式化：使用有序列表或引用格式
            String contentToAppend = String.format("\n> 新增关注/规则: %s", ruleItem);

            log.info("[Tool][AddInterest] 更新注意力雷达: {}", ruleItem);

            boolean success = MDManager.append(TARGET_FILE, contentToAppend);

            if (success) {
                return "SUCCESS: 注意力雷达已记录新规则。";
            } else {
                return "ERROR: 物理写入失败。";
            }
        } catch (Exception e) {
            log.error("执行 add_interest_rule 发生异常", e);
            return "ERROR: 执行失败: " + e.getMessage();
        }
    }

    @Override
    public String getTextRecord(){
        return "为注意力雷达新增了过滤规则;";
    }
}