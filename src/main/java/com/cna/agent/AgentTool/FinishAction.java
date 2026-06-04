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
 * 用于结算本轮认知周期：反馈经验有用性、记录推理过程、激活感觉维度、
 * 规划后续行动、调节注意力。必须作为本轮最后一个工具调用。
 * 如果未调用，系统会将相同提示词重新发送给 LLM 要求重算。
 *
 * ★ V4 元数据载体：所有非工具调用的元数据（thoughts/feelings/scoring/boosts）
 *   均通过此工具的参数传递，不再依赖 LLM 输出纯 JSON 文本。
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

        // thoughts: 必填，LLM 的推理过程
        ObjectNode thoughts = properties.putObject("thoughts");
        thoughts.put("type", "string");
        thoughts.put("description",
                "你的内部推理过程。展示你的推理链条、关联先验经验、说明决策理由。"
                + "保持简洁但不简陋，通常 50~300 字。");

        // experience_scoring: 经验打分数组
        ObjectNode scoring = properties.putObject("experience_scoring");
        scoring.put("type", "array");
        scoring.put("description", p.getCustomDescription("experience_scoring"));
        ObjectNode items = scoring.putObject("items");
        items.put("type", "object");
        items.put("additionalProperties", false);
        ObjectNode itemProps = items.putObject("properties");
        ObjectNode expId = itemProps.putObject("experience_id");
        expId.put("type", "integer");
        expId.put("description", "先验经验的 ID");
        ObjectNode score = itemProps.putObject("score");
        score.put("type", "integer");
        score.put("description", "打分：1=有帮助，0=中性，-1=没帮助");

        // next_actions: 必填，LLM 为自己规划的后续行动任务列表
        ObjectNode nextActions = properties.putObject("next_actions");
        nextActions.put("type", "array");
        nextActions.put("description",
                "本轮认知周期结束后，你为自己规划的后续行动任务列表。每项是一个独立任务，"
                + "系统会为每项创建一个 CognitivePrepareUnit 注入准备池，"
                + "由池的选择机制决定执行顺序。必须至少包含 1 项（即使只是\"继续监控\"）。");
        ObjectNode naItems = nextActions.putObject("items");
        naItems.put("type", "object");
        naItems.put("additionalProperties", false);
        ObjectNode naProps = naItems.putObject("properties");
        ObjectNode naText = naProps.putObject("text");
        naText.put("type", "string");
        naText.put("description", "后续任务描述。清晰说明需要做什么、为什么需要做。这句话会成为下一轮 ActionText 的核心。");
        ObjectNode naPriority = naProps.putObject("priority");
        naPriority.put("type", "number");
        naPriority.put("description", "该任务的优先级系数（0.1~1.0），影响 SE 初始值。1.0=高优先级，0.5=默认，0.1=低优先级。不填则默认 0.5。");
        ArrayNode naRequired = naItems.putArray("required");
        naRequired.add("text");

        // action_feelings: 可选，LLM 自主输出本轮涉及的所有感觉维度
        ObjectNode actionFeelings = properties.putObject("action_feelings");
        actionFeelings.put("type", "array");
        actionFeelings.put("description", p.getCustomDescription("action_feelings"));
        ObjectNode afItems = actionFeelings.putObject("items");
        afItems.put("type", "object");
        afItems.put("additionalProperties", false);
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

        // stimulated_feelings: 本轮激活的感觉维度
        ObjectNode stimFeelings = properties.putObject("stimulated_feelings");
        stimFeelings.put("type", "array");
        stimFeelings.put("description",
                "本轮行动激活或联想到的感觉维度列表。每项包含 concept（5-15字语义标签）"
                + "和 embedding_text（更详细的描述文本，用于生成向量嵌入）。"
                + "无特别值得记录的感觉时传空数组 []。");
        ObjectNode sfItems = stimFeelings.putObject("items");
        sfItems.put("type", "object");
        sfItems.put("additionalProperties", false);
        ObjectNode sfProps = sfItems.putObject("properties");
        ObjectNode sfConcept = sfProps.putObject("concept");
        sfConcept.put("type", "string");
        sfConcept.put("description", "简短的语义标签（5-15字）");
        ObjectNode sfEmbText = sfProps.putObject("embedding_text");
        sfEmbText.put("type", "string");
        sfEmbText.put("description", "更详细的描述文本，包含上下文细节，用于生成向量嵌入");

        // new_prepare_unit: 后续准备单元
        ObjectNode newUnit = properties.putObject("new_prepare_unit");
        newUnit.put("type", "object");
        newUnit.put("description",
                "（可选）如果本轮有未完成的事项，创建新的认知准备单元。"
                + "包含 text（事项描述）和 sourceIds（来源 ID 数组）。无需时传 null。");
        ObjectNode nuProps = newUnit.putObject("properties");
        ObjectNode nuText = nuProps.putObject("text");
        nuText.put("type", "string");
        nuText.put("description", "需要后续处理的事项描述");
        ObjectNode nuSources = nuProps.putObject("sourceIds");
        nuSources.put("type", "array");
        nuSources.put("description", "来源标识符列表");
        ObjectNode nuSourcesItems = nuSources.putObject("items");
        nuSourcesItems.put("type", "string");

        // experience_annotations: 可选，对已有经验追加评价
        ObjectNode expAnnotations = properties.putObject("experience_annotations");
        expAnnotations.put("type", "array");
        expAnnotations.put("description",
                "（可选）对已有经验的追加评价。如果本轮让你对某条历史经验有了新的认识，"
                + "可以在此追加评注。这些评注会追加到对应经验的条目中，影响后续检索。"
                + "无需时传空数组 []。");
        ObjectNode eaItems = expAnnotations.putObject("items");
        eaItems.put("type", "object");
        ObjectNode eaProps = eaItems.putObject("properties");
        ObjectNode eaExpId = eaProps.putObject("experience_id");
        eaExpId.put("type", "integer");
        eaExpId.put("description", "要追加评价的经验 ID");
        ObjectNode eaAnnotation = eaProps.putObject("annotation");
        eaAnnotation.put("type", "string");
        eaAnnotation.put("description", "追加的评价文本，说明你为什么这样评价它、它现在是否仍然适用等。简洁即可，1-2句话。");

        // continue_weight_boosts: 注意力调节
        ObjectNode boosts = properties.putObject("continue_weight_boosts");
        boosts.put("type", "array");
        boosts.put("description",
                "给准备池中特定单元增加持续权重。每项包含 unit_uuid（单元UUID）"
                + "和 boost（推荐 0.3~1.0）。不确定时传空数组 []。");
        ObjectNode bItems = boosts.putObject("items");
        bItems.put("type", "object");
        ObjectNode bProps = bItems.putObject("properties");
        ObjectNode bUuid = bProps.putObject("unit_uuid");
        bUuid.put("type", "string");
        bUuid.put("description", "准备池中目标单元的 UUID");
        ObjectNode bBoost = bProps.putObject("boost");
        bBoost.put("type", "number");
        bBoost.put("description", "权重增加值，推荐 0.3~1.0");

        ArrayNode required = parameters.putArray("required");
        required.add("thoughts");
        required.add("experience_scoring");
        required.add("next_actions");

        parameters.put("additionalProperties", false);
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
