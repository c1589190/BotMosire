package com.cna.agent.AgentTool;

import com.cna.config.ToolPromptsManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

/**
 * 每轮 Action 必须调用的结算工具。
 *
 * 用于反馈本轮先验经验的有用性。即使没有经验需要打分，
 * 也必须调用此工具（传空数组），作为本轮认知周期的正式终结信号。
 * 如果未调用，系统会将相同提示词重新发送给 LLM 要求重算。
 */
@Slf4j
public class FinishAction implements DefaultAgentToolUnit {

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String getName() {
        return "finish_action";
    }

    @Override
    public ObjectNode getToolDefinition() {
        ObjectNode tool = mapper.createObjectNode();
        tool.put("type", "function");

        ToolPromptsManager p = new ToolPromptsManager(this.getClass().getName());

        ObjectNode function = tool.putObject("function");
        function.put("name", getName());
        function.put("description", p.getToolDescription());

        ObjectNode parameters = function.putObject("parameters");
        parameters.put("type", "object");

        ObjectNode properties = parameters.putObject("properties");

        // experience_scoring: 经验打分数组
        ObjectNode scoring = properties.putObject("experience_scoring");
        scoring.put("type", "array");
        scoring.put("description", p.getCustomDescription("experience_scoring"));
        ObjectNode items = scoring.putObject("items");
        items.put("type", "object");
        ObjectNode itemProps = items.putObject("properties");
        ObjectNode expId = itemProps.putObject("experience_id");
        expId.put("type", "integer");
        expId.put("description", "先验经验的 ID");
        ObjectNode score = itemProps.putObject("score");
        score.put("type", "integer");
        score.put("description", "打分：1=有帮助，0=中性，-1=没帮助");

        // next_action_text: 可选，LLM 自主决定是否创建后续认知准备单元
        ObjectNode nextAction = properties.putObject("next_action_text");
        nextAction.put("type", "string");
        nextAction.put("description",
                "（可选）如果你认为本轮还有未完成的事项需要后续处理，在这里填写后续动作的描述文本。"
                + "系统会将其作为新的 CognitivePrepareUnit 注入准备池，在后续 tick 中被选中继续处理。"
                + "留空或不填则本轮完全结算，不创建后续单元。");

        // action_feelings: 可选，LLM 自主输出本轮涉及的所有感觉维度
        ObjectNode actionFeelings = properties.putObject("action_feelings");
        actionFeelings.put("type", "array");
        actionFeelings.put("description", p.getCustomDescription("action_feelings"));
        ObjectNode afItems = actionFeelings.putObject("items");
        afItems.put("type", "object");
        ObjectNode afProps = afItems.putObject("properties");
        ObjectNode afDimId = afProps.putObject("dim_id");
        afDimId.put("type", "integer");
        afDimId.put("description", p.getCustomDescription("action_feelings.dim_id"));
        ObjectNode afConcept = afProps.putObject("concept");
        afConcept.put("type", "string");
        afConcept.put("description", p.getCustomDescription("action_feelings.concept"));
        ObjectNode afEmbText = afProps.putObject("embedding_text");
        afEmbText.put("type", "string");
        afEmbText.put("description", p.getCustomDescription("action_feelings.embedding_text"));
        ObjectNode afRelation = afProps.putObject("relation");
        afRelation.put("type", "string");
        afRelation.put("description", p.getCustomDescription("action_feelings.relation"));

        ArrayNode required = parameters.putArray("required");
        required.add("experience_scoring");

        return tool;
    }

    @Override
    public String execute(JsonNode arguments) {
        try {
            JsonNode scorings = arguments.path("experience_scoring");
            int count = scorings.isArray() ? scorings.size() : 0;
            if (count == 0) {
                log.info("[FinishAction] 本轮无经验需要打分");
                return "SUCCESS: 本轮认知周期已结算（无经验打分）。";
            }

            // 记录打分结果
            StringBuilder sb = new StringBuilder();
            for (JsonNode s : scorings) {
                int id = s.path("experience_id").asInt(-1);
                int sc = s.path("score").asInt(0);
                if (id >= 0) {
                    sb.append("  exp#").append(id).append(" score=").append(sc).append("\n");
                }
            }
            log.info("[FinishAction] 经验打分结算:\n{}", sb.toString());
            return "SUCCESS: 本轮认知周期已结算，" + count + " 条经验已打分。";
        } catch (Exception e) {
            log.error("[FinishAction] 执行异常", e);
            return "ERROR: " + e.getMessage();
        }
    }

    @Override
    public String getTextRecord() {
        return "调用了 finish_action 结算本轮认知周期;";
    }
}
