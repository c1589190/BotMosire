package com.cna.agent.AgentTool;

import com.cna.Utils;
import com.cna.config.ToolPromptsManager;
import com.cna.db.FeelingDimensionManager;
import com.cna.db.MDManager;
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

        ToolPromptsManager p = new ToolPromptsManager(this.getClass().getName());

        ObjectNode function = toolDef.putObject("function");
        function.put("name", getName());
        function.put("description",
                p.getToolDescription());

        ObjectNode params = function.putObject("parameters");
        params.put("type", "object");

        ObjectNode properties = params.putObject("properties");

        // 任务总结
        ObjectNode summaryProp = properties.putObject("summary");
        summaryProp.put("type", "string");
        summaryProp.put("description", p.getCustomDescription("summary"));

        // 关键概念数组（与 FeelingDimensionManager 的提取格式兼容）
        ObjectNode conceptsProp = properties.putObject("concepts");
        conceptsProp.put("type", "array");
        conceptsProp.put("description", p.getCustomDescription("concepts"));

        ObjectNode items = conceptsProp.putObject("items");
        items.put("type", "object");

        ObjectNode itemProps = items.putObject("properties");
        ObjectNode nameProp = itemProps.putObject("name");
        nameProp.put("type", "string");
        nameProp.put("description", p.getCustomDescription("name"));

        ObjectNode isPositiveProp = itemProps.putObject("is_positive");
        isPositiveProp.put("type", "boolean");
        isPositiveProp.put("description",
                p.getCustomDescription("is_positive"));

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
            // 严格要求 is_positive 必须明确传 boolean，缺失或类型错误就拒收该 concept，避免默认 true 导致 feeling 系统长期偏正向
            List<FeelingDimensionManager.ConceptInput> conceptInputs = new ArrayList<>();
            int rejectedCount = 0;
            for (JsonNode item : conceptsNode) {
                String name = item.path("name").asText().trim();
                JsonNode posNode = item.path("is_positive");
                if (name.isEmpty()) continue;
                if (posNode.isMissingNode() || posNode.isNull() || !posNode.isBoolean()) {
                    log.warn("[FinishTask] 概念 [{}] 缺少有效的 is_positive boolean，已拒收（避免错误偏正向）", name);
                    rejectedCount++;
                    continue;
                }
                conceptInputs.add(new FeelingDimensionManager.ConceptInput(name, posNode.asBoolean()));
            }
            if (rejectedCount > 0) {
                log.info("[FinishTask] 本次拒收 {} 个 is_positive 不合格的概念。", rejectedCount);
            }

            if (!conceptInputs.isEmpty()) {
                fdManager.processExplicitConceptsAsync(conceptInputs);
                log.info("[FinishTask] 已将 {} 个显式概念直接注入感觉中枢（跳过LLM二次提取）。总结：{}", conceptInputs.size(), summary);
            } else {
                log.info("[FinishTask] 概念列表为空，仅记录总结，无维度更新。");
            }

            // 异步追加 summary 到 thoughts.md（替代已取缔的 add_inner_thought）
            String contentToAppend = String.format("\n\n### [%s]\n%s", Utils.getNowPrecise(), summary);
            MDManager.appendAsync("thoughts.md", contentToAppend);

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