package com.cna.agent.AgentTool;

import com.cna.Utils;
import com.cna.config.ToolPromptsManager;
import com.cna.db.FeelingDimensionManager;
import com.cna.db.MDManager;
import com.cna.agent.FeelingDissonanceResolver;
import com.cna.agent.FeelingResonanceAnalyzer;
import com.cna.agent.MemoryManager;
import com.cna.db.MemoryDB;
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

    /**
     * 当前正在执行的任务的来源标识符，由 LivingLoop 在工具执行前注入。
     */
    public static final ThreadLocal<List<String>> CURRENT_TASK_SOURCES = new ThreadLocal<>();

    /**
     * 当前任务的谐振分析结果，由 prepareBaseData 注入，供 finish_task 违和结算使用。
     */
    public static final ThreadLocal<com.cna.agent.FeelingResonanceAnalyzer.ResonanceAnalysisResult>
            CURRENT_RESONANCE_RESULT = new ThreadLocal<>();

    /**
     * LLM 在 finish_task 时指定的下一个优先执行任务的展示编号。
     * 0 或不填 = 默认调度。由 LivingLoop 在任务终结后消费并清理。
     */
    public static final ThreadLocal<Integer> NEXT_TASK_DISPLAY_ID = new ThreadLocal<>();

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
                p.getToolDescription() + "。当本轮使用了 query_deep_memory 并找到了有帮助的记忆时，请在 useful_memory_ids 中列出那些 [DM-N] 编号，系统会将其与当前任务来源关联，方便未来更好地定位这些记忆。");

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

        // 有用的深层记忆 ID 列表
        ObjectNode usefulIdsProp = properties.putObject("useful_memory_ids");
        usefulIdsProp.put("type", "array");
        usefulIdsProp.put("description", "可选：本轮对话中觉得有用、帮你更好理解/回复的深度记忆编号列表（即 [DM-N] 中的 N），填写整数。如果没有用到深层记忆或不觉得有帮助，传空数组 []");
        ObjectNode idItems = usefulIdsProp.putObject("items");
        idItems.put("type", "integer");

        // 违和感更新
        ObjectNode dissonanceProp = properties.putObject("dissonance_updates");
        dissonanceProp.put("type", "array");
        dissonanceProp.put("description", "可选：对违和感觉的分析更新列表。每项包含 dim_id(维度ID)、new_notes(追加的分析)、resolved(是否已解决)、resolution_concept(解决后新建的概念)、is_positive(新概念极性)");
        ObjectNode dissItems = dissonanceProp.putObject("items");
        dissItems.put("type", "object");
        ObjectNode dissItemProps = dissItems.putObject("properties");
        ObjectNode dimIdProp = dissItemProps.putObject("dim_id");
        dimIdProp.put("type", "integer");
        ObjectNode notesProp = dissItemProps.putObject("new_notes");
        notesProp.put("type", "string");
        ObjectNode resolvedProp = dissItemProps.putObject("resolved");
        resolvedProp.put("type", "boolean");
        ObjectNode conceptProp = dissItemProps.putObject("resolution_concept");
        conceptProp.put("type", "string");
        ObjectNode posProp = dissItemProps.putObject("is_positive");
        posProp.put("type", "boolean");

        // 好奇心疑问（NEW）
        ObjectNode cqProp = properties.putObject("curiosity_questions");
        cqProp.put("type", "array");
        cqProp.put("description", "可选：当你对感觉谐振分析中发现的违和感有具体困惑时，以疑问句形式写下你的问题。每项包含 source_dim_id(触发违和的感觉维度ID)、question(你的疑问句)。这些疑问会在后续轮次中持续提醒你思考。");
        ObjectNode cqItems = cqProp.putObject("items");
        cqItems.put("type", "object");
        ObjectNode cqItemProps = cqItems.putObject("properties");
        ObjectNode srcDimIdProp = cqItemProps.putObject("source_dim_id");
        srcDimIdProp.put("type", "integer");
        srcDimIdProp.put("description", "触发违和感的感觉维度ID（在感觉谐振分析的违和感条目中查找 dim_id）");
        ObjectNode questionProp = cqItemProps.putObject("question");
        questionProp.put("type", "string");
        questionProp.put("description", "你对该违和感的具体困惑，以疑问句形式表达");

        // 下一个优先执行的任务
        ObjectNode nextTaskProp = properties.putObject("next_task_display_id");
        nextTaskProp.put("type", "integer");
        nextTaskProp.put("description", "可选：指定任务完成后，你希望优先执行的下一个任务的 [#N] 编号。如果你根据当前任务的发现、队列中其他任务的内容和优先级判断某个任务更值得立即执行，在此指定它的编号。填 0 或不填则使用默认优先级调度。请主动检查任务队列并做出选择——这是你掌控自己工作流的机会。");

        ArrayNode required = params.putArray("required");
        required.add("summary");
        required.add("concepts");

        params.put("additionalProperties", false);
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

            // 捕获 LLM 指定的下一个优先任务
            int nextTaskId = arguments.path("next_task_display_id").asInt(0);
            if (nextTaskId > 0) {
                NEXT_TASK_DISPLAY_ID.set(nextTaskId);
                log.info("[FinishTask] LLM 指定下一个优先执行任务 [#{}]", nextTaskId);
            } else {
                NEXT_TASK_DISPLAY_ID.remove();
            }

            FeelingDimensionManager fdManager = FeelingDimensionManager.getInstance();
            if (fdManager == null) {
                log.warn("[FinishTask] FeelingDimensionManager 未初始化，概念反馈丢失");
                // 仍然处理 useful_memory_ids
                processUsefulMemoryIds(arguments);
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

            // 处理 LLM 标记为"有用"的深度记忆
            int enrichedCount = processUsefulMemoryIds(arguments);

            // 【新增】处理违和感更新
            String dissonanceResult = processDissonanceUpdates(arguments);

            StringBuilder resultMsg = new StringBuilder("任务已完成并提交反馈。总结：" + summary);
            if (enrichedCount > 0) {
                resultMsg.append("，已将 ").append(enrichedCount).append(" 条深度记忆与当前来源关联。");
            }
            if (!dissonanceResult.isEmpty()) {
                resultMsg.append(" ").append(dissonanceResult);
            }
            return resultMsg.toString();
        } catch (Exception e) {
            log.error("[FinishTask] 执行异常", e);
            return "任务完成，但反馈记录失败：" + e.getMessage();
        }
    }

    /**
     * 解析 useful_memory_ids 参数，并调用 MemoryManager 将当前任务来源追加到对应深度记忆中。
     * @return 成功关联的记忆条数
     */
    private int processUsefulMemoryIds(JsonNode arguments) {
        try {
            JsonNode idsNode = arguments.path("useful_memory_ids");
            if (idsNode.isMissingNode() || !idsNode.isArray() || idsNode.isEmpty()) {
                return 0;
            }

            List<Integer> ids = new ArrayList<>();
            for (JsonNode n : idsNode) {
                if (n.isInt()) {
                    ids.add(n.asInt());
                }
            }

            if (ids.isEmpty()) return 0;

            List<String> taskSources = CURRENT_TASK_SOURCES.get();
            if (taskSources == null || taskSources.isEmpty()) {
                log.info("[FinishTask] LLM 标记了 {} 条有用记忆 {}，但当前任务无来源信息可追加", ids.size(), ids);
                return 0;
            }

            MemoryManager.getInstance().enrichDeepMemorySources(ids, taskSources);
            log.info("[FinishTask] LLM 标记了 {} 条有用记忆 {}，已追加来源 {}", ids.size(), ids, taskSources);
            return ids.size();
        } catch (Exception e) {
            log.error("[FinishTask] 处理 useful_memory_ids 失败", e);
            return 0;
        }
    }

    /**
     * 解析 dissonance_updates 和 curiosity_questions 参数，
     * 分别调用 FeelingDissonanceResolver 结算违和、CuriosityListManager 注册疑问。
     */
    private String processDissonanceUpdates(JsonNode arguments) {
        try {
            StringBuilder summary = new StringBuilder();

            // === Part A: dissonance_updates ===
            JsonNode dissNode = arguments.path("dissonance_updates");
            List<FeelingDissonanceResolver.DissonanceUpdate> updates = new ArrayList<>();
            java.util.Set<Integer> curiosityResolvedDims = new java.util.LinkedHashSet<>();
            java.util.Map<Integer, String> curiosityNotes = new java.util.HashMap<>();

            if (!dissNode.isMissingNode() && dissNode.isArray() && !dissNode.isEmpty()) {
                for (JsonNode item : dissNode) {
                    int dimId = item.path("dim_id").asInt(-1);
                    if (dimId < 0) continue;
                    String newNotes = item.path("new_notes").asText("");
                    boolean resolved = item.path("resolved").asBoolean(false);
                    String resolutionConcept = item.path("resolution_concept").asText("");
                    boolean isPositive = item.path("is_positive").asBoolean(true);
                    updates.add(new FeelingDissonanceResolver.DissonanceUpdate(
                            dimId, newNotes, resolved, resolutionConcept, isPositive));

                    if (item.path("curiosity_resolved").asBoolean(false)) {
                        curiosityResolvedDims.add(dimId);
                        curiosityNotes.put(dimId, newNotes);
                    }
                }
            }

            // === Part B: curiosity_questions (NEW) ===
            JsonNode cqNode = arguments.path("curiosity_questions");
            if (!cqNode.isMissingNode() && cqNode.isArray() && !cqNode.isEmpty()) {
                com.cna.agent.CuriosityListManager clm = com.cna.agent.CuriosityListManager.getInstance();
                if (clm != null) {
                    for (JsonNode item : cqNode) {
                        int sourceDimId = item.path("source_dim_id").asInt(-1);
                        if (sourceDimId < 0) continue;
                        String question = item.path("question").asText("");
                        if (question.isBlank()) continue;

                        // 从谐振分析结果中找对应的 dissonantDimIds
                        java.util.List<Integer> dissonantDimIds = new java.util.ArrayList<>();
                        com.cna.agent.FeelingResonanceAnalyzer.ResonanceAnalysisResult resonance =
                                CURRENT_RESONANCE_RESULT.get();
                        if (resonance != null && resonance.groups != null) {
                            for (var g : resonance.groups) {
                                if (g.sourceDimId == sourceDimId && g.hasDissonance()) {
                                    for (var m : g.getDissonant()) {
                                        dissonantDimIds.add(m.dimId);
                                    }
                                    break;
                                }
                            }
                        }
                        clm.registerCuriosityQuestion(sourceDimId, question, dissonantDimIds);
                    }
                    summary.append("curiosity_questions 已处理, ");
                }
            }

            if (updates.isEmpty() && curiosityResolvedDims.isEmpty()) {
                return summary.isEmpty() ? "" : summary.toString().replaceAll(", $", "");
            }

            // 获取谐振分析结果中的不违和维度
            com.cna.agent.FeelingResonanceAnalyzer.ResonanceAnalysisResult resonance =
                    CURRENT_RESONANCE_RESULT.get();
            List<Integer> consonantDimIds = new java.util.ArrayList<>();
            java.util.Set<Integer> allInvolved = new java.util.LinkedHashSet<>();
            if (resonance != null && resonance.groups != null) {
                for (var g : resonance.groups) {
                    for (var m : g.getConsonant()) consonantDimIds.add(m.dimId);
                }
                allInvolved = resonance.allInvolvedDimIds;
            }

            com.cna.db.FeelingDimensionManager fdm = FeelingDimensionManager.getInstance();
            com.cna.db.FeelingHypergraphManager hgm = com.cna.db.FeelingHypergraphManager.getInstance();
            MemoryDB db = new MemoryDB();
            FeelingDissonanceResolver resolver = new FeelingDissonanceResolver(
                    fdm, hgm, db,
                    new com.cna.llm.LLMAdapter(com.cna.config.ConfigsManager.EMBEDDING_CONFIG));

            String result = resolver.processDissonanceUpdates(updates, consonantDimIds, allInvolved);
            log.info("[FinishTask] {}", result);
            summary.append(result);

            // 好奇心消解
            for (int dimId : curiosityResolvedDims) {
                com.cna.agent.CuriosityListManager clm = com.cna.agent.CuriosityListManager.getInstance();
                if (clm != null) {
                    clm.resolveEntriesByDim(dimId, curiosityNotes.getOrDefault(dimId, ""));
                }
            }

            return summary.toString();
        } catch (Exception e) {
            log.error("[FinishTask] 处理 dissonance_updates 失败", e);
            return "";
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
