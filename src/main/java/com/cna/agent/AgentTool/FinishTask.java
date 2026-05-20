package com.cna.agent.AgentTool;

import com.cna.db.FeelingDimensionManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

@Slf4j
public class FinishTask implements DefaultAgentToolUnit {

    private static final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String getName() {
        return "finish_task";
    }

    @Override
    public ObjectNode getToolDefinition() {
        ObjectNode toolDef = mapper.createObjectNode();
        toolDef.put("type", "function");

        ObjectNode function = toolDef.putObject("function");
        function.put("name", getName());
        function.put("description",
                "当任务彻底完成时调用此工具。你必须提供本次任务中涉及的多个语义要点，并对所有要点进行简单的正面/负面感觉评价。");

        ObjectNode params = function.putObject("parameters");
        params.put("type", "object");

        ObjectNode properties = params.putObject("properties");

        // 任务总结
        ObjectNode summaryProp = properties.putObject("summary");
        summaryProp.put("type", "string");
        summaryProp.put("description", "本次任务的核心成果或执行摘要，用一段话概括。");

        // 关键概念数组（与 FeelingDimensionManager 的提取格式兼容）
        ObjectNode conceptsProp = properties.putObject("concepts");
        conceptsProp.put("type", "array");
        conceptsProp.put("description", "任务中涉及的若干个最重要的底层概念或实体，并给出其客观极性判断。");

        ObjectNode items = conceptsProp.putObject("items");
        items.put("type", "object");

        ObjectNode itemProps = items.putObject("properties");
        ObjectNode nameProp = itemProps.putObject("name");
        nameProp.put("type", "string");
        nameProp.put("description", "概念或实体的名称");

        ObjectNode isPositiveProp = itemProps.putObject("is_positive");
        isPositiveProp.put("type", "boolean");
        isPositiveProp.put("description",
                "该概念在本任务中是建设性/正向的（true），还是破坏性/负向的（false）");

        ArrayNode itemRequired = items.putArray("required");
        itemRequired.add("name");
        itemRequired.add("is_positive");

        ArrayNode required = params.putArray("required");
        required.add("summary");
        required.add("concepts");

        return toolDef;
    }

    @Override
    public String execute(JsonNode arguments) {
        try {
            String summary = arguments.path("summary").asText();
            JsonNode conceptsNode = arguments.path("concepts");

            if (summary.isEmpty() || conceptsNode.isMissingNode() || !conceptsNode.isArray()) {
                return "任务已完成，但你提供的总结或概念列表无效，请重试。";
            }

            FeelingDimensionManager fdManager = FeelingDimensionManager.getInstance();
            if (fdManager == null) {
                log.warn("[FinishTask] FeelingDimensionManager 未初始化，概念反馈丢失");
                return "任务已完成。总结：" + summary;
            }

            // 直接从 LLM 的输出构建概念列表，不再转成文本再让 LLM 提取
            List<FeelingDimensionManager.ConceptInput> conceptInputs = new ArrayList<>();
            for (JsonNode item : conceptsNode) {
                String name = item.path("name").asText().trim();
                boolean isPositive = item.path("is_positive").asBoolean(true);
                if (!name.isEmpty()) {
                    conceptInputs.add(new FeelingDimensionManager.ConceptInput(name, isPositive));
                }
            }

            if (!conceptInputs.isEmpty()) {
                fdManager.processExplicitConceptsAsync(conceptInputs);
                log.info("[FinishTask] 已将 {} 个显式概念直接注入感觉中枢（跳过LLM二次提取）。总结：{}", conceptInputs.size(), summary);
            } else {
                log.info("[FinishTask] 概念列表为空，仅记录总结，无维度更新。");
            }

            return "任务已完成并提交反馈。总结：" + summary;
        } catch (Exception e) {
            log.error("[FinishTask] 执行异常", e);
            return "任务完成，但反馈记录失败：" + e.getMessage();
        }
    }

    @Override
    public String getTextRecord() {
        return "调用了finish_task，任务结束并提交了总结与评价";
    }

    @Override
    public boolean isAutoLoad() {
        return true;  // 自动出现在工具箱中
    }

    @Override
    public boolean isAutoMemory() {
        return true;  // 执行记录自动写入短期记忆
    }
}