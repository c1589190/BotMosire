package com.cna.db; // 建议后期移到 com.cna.manager

import com.cna.config.ConfigsManager;
import com.cna.db.MemoryDB.FeelingDimension;
import com.cna.llm.CallResult;
import com.cna.llm.LLMAdapter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.CompletableFuture;

@Slf4j
public class FeelingDimensionManager {

    // ========== 单例模式支持 (方便 AbstractHandler 调用) ==========
    private static FeelingDimensionManager instance;
    public static void init(MemoryDB memoryDB) {
        instance = new FeelingDimensionManager(memoryDB);
    }
    public static FeelingDimensionManager getInstance() {
        return instance;
    }
    // ==========================================================

    private static final ObjectMapper jsonMapper = new ObjectMapper();

    private final MemoryDB memoryDB;
    private static final double SIMILARITY_THRESHOLD = 0.6;
    private static final double NOVELTY_THRESHOLD = 0.8;
    private static final float DECAY_CONSTANT = 0.1f;

    public FeelingDimensionManager(MemoryDB memoryDB) {
        this.memoryDB = memoryDB;
    }

    /**
     * 【重构升级】：使用 Tool Calling 安全提取概念数组
     */
    public void processTaskLogAsync(String taskLog) {
        if (taskLog == null || taskLog.trim().isEmpty()) return;

        CompletableFuture.runAsync(() -> {
            try {
                // 1. 组装强制模型吐出数组的 JSON 工具约束
                ArrayNode toolsArray = buildExtractionTool();
                LLMAdapter extractionLlm = new LLMAdapter(ConfigsManager.BRAIN_CONFIG);
                String prompt = buildDistillationPrompt(taskLog);

                // 2. 调用非流式 Tool Calling API (注意这里 contextMemories 传空，避免被系统预设污染)
                CallResult result = extractionLlm.generateResponseWithTools(prompt, "", toolsArray);

                // 3. 安全解析工具调用返回
                if (!result.isToolCall() || result.getToolCalls() == null || result.getToolCalls().isEmpty()) {
                    log.info("[Feeling] 任务流中未发现有价值的唯物刺激，或模型判定无价值，跳过感觉更新。");
                    return;
                }

                // 拿到第一条工具调用
                JsonNode toolCall = result.getToolCalls().get(0);
                String argumentsStr = toolCall.path("function").path("arguments").asText();
                JsonNode argsNode = jsonMapper.readTree(argumentsStr);

                // 提取出来的数组
                JsonNode conceptsNode = argsNode.path("concepts");
                if (conceptsNode.isMissingNode() || !conceptsNode.isArray() || conceptsNode.isEmpty()) {
                    log.info("[Feeling] 提取的数组为空，结束结算。");
                    return;
                }

                List<FeelingDimension> currentDimensions = memoryDB.getAllFeelingDimensions();
                int addedCount = 0;
                int updatedCount = 0;

                // 4. 逐个比对关键词
                for (JsonNode conceptItem : conceptsNode) {
                    String concept = conceptItem.asText().trim();
                    if (concept.isEmpty()) continue;

                    double[] conceptVector = getEmbeddingMock(concept);
                    FeelingDimension bestMatch = null;
                    double highestSim = -1.0;

                    for (FeelingDimension dim : currentDimensions) {
                        double sim = cosineSimilarity(conceptVector, dim.vector);
                        if (sim > highestSim) {
                            highestSim = sim;
                            bestMatch = dim;
                        }
                    }

                    // 5. 核心逻辑：距离判断 (相似度判断)
                    if (bestMatch != null && highestSim >= NOVELTY_THRESHOLD) {
                        float weightToAdd = (float) highestSim;
                        memoryDB.addWeightToDimension(bestMatch.id, weightToAdd);
                        updatedCount++;
                        log.info("[Feeling] 概念 [{}] 与老维度 [{}] 重合(相似度:{}), 执行加权。", concept, bestMatch.concept, highestSim);
                    } else {
                        memoryDB.insertFeelingDimension(concept, conceptVector, 1.0f);
                        addedCount++;
                        log.info("[Feeling] 概念 [{}] 是全新唯物刺激(最高相似度:{}), 长出新突触！", concept, highestSim);
                        currentDimensions.add(new FeelingDimension(-1, concept, conceptVector, 1.0f));
                    }
                }

                log.info("[Feeling] 本轮结算完毕：新长出突触 {} 个，反哺旧维度 {} 个。", addedCount, updatedCount);

            } catch (Exception e) {
                log.error("[Feeling] 异步处理任务结算与维度更新失败", e);
            }
        });
    }

    // ==========================================
    // 内部工具装配器
    // ==========================================

    /**
     * 构造用于强制提取概念的虚拟 Tool JSON Schema
     */
    private ArrayNode buildExtractionTool() {
        ArrayNode tools = jsonMapper.createArrayNode();
        ObjectNode toolWrapper = tools.addObject();
        toolWrapper.put("type", "function");
        ObjectNode funcNode = toolWrapper.putObject("function");

        funcNode.put("name", "submit_extracted_concepts");
        funcNode.put("description", "提交从任务日志中提取的核心底层技术概念或客观实体数组。如果没有值得提取的东西，请不要调用此工具。");

        ObjectNode paramsNode = funcNode.putObject("parameters");
        paramsNode.put("type", "object");

        ObjectNode propsNode = paramsNode.putObject("properties");
        ObjectNode conceptsArrayNode = propsNode.putObject("concepts");
        conceptsArrayNode.put("type", "array");
        conceptsArrayNode.put("description", "提取出来的底层概念列表，限制为 1 到 3 个。");

        ObjectNode itemsNode = conceptsArrayNode.putObject("items");
        itemsNode.put("type", "string");

        ArrayNode requiredNode = paramsNode.putArray("required");
        requiredNode.add("concepts");

        return tools;
    }

    private String buildDistillationPrompt(String taskLog) {
        return "现在你要从下面的任务执行日志中提取出 1 到 3 个最核心、最具体的底层技术概念或客观实体。\n" +
                "规则：忽略废话、寒暄、纯情绪表达。只关注客观技术、工具、理论模型或具体的行为机制。\n" +
                "动作：提取完成后，必须且仅能调用 `submit_extracted_concepts` 工具来提交结果。如果没有价值，什么都不用做。\n\n" +
                "【日志输入】\n" + taskLog;
    }

    public FeelingEvaluation evaluateInput(String input) {
        double[] inputVector = getEmbeddingMock(input);
        List<FeelingDimension> dimensions = memoryDB.getAllFeelingDimensions();
        if (dimensions.isEmpty()) return new FeelingEvaluation("none", 0.0f);

        FeelingDimension bestMatch = null;
        double highestFinalScore = -1.0;

        for (FeelingDimension dim : dimensions) {
            double sim = cosineSimilarity(inputVector, dim.vector);
            double finalScore = sim * dim.weight;
            if (finalScore > highestFinalScore) {
                highestFinalScore = finalScore;
                bestMatch = dim;
            }
        }
        log.debug("[Feeling] 最佳匹配维度: {}, 最终得分: {}", bestMatch.concept, highestFinalScore);
        return new FeelingEvaluation(bestMatch.concept, (float) highestFinalScore);
    }

    public void tick() {
        log.info("[Feeling] 触发全局记忆衰减 (Tick), 扣除常数: {}", DECAY_CONSTANT);
        int forgottenNodes = memoryDB.applyGlobalDecay(DECAY_CONSTANT);
        if (forgottenNodes > 0) {
            log.info("[Feeling] 衰减完成。系统物理遗忘了 {} 个枯萎的神经维度。", forgottenNodes);
        }
    }

    public static class FeelingEvaluation {
        public final String topConcept;
        public final float finalScore;
        public FeelingEvaluation(String topConcept, float finalScore) {
            this.topConcept = topConcept;
            this.finalScore = finalScore;
        }
    }

    private double cosineSimilarity(double[] vectorA, double[] vectorB) {
        double dotProduct = 0.0, normA = 0.0, normB = 0.0;
        for (int i = 0; i < vectorA.length; i++) {
            dotProduct += vectorA[i] * vectorB[i];
            normA += Math.pow(vectorA[i], 2);
            normB += Math.pow(vectorB[i], 2);
        }
        if (normA == 0.0 || normB == 0.0) return 0.0;
        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private double[] getEmbeddingMock(String text) {
        return new LLMAdapter(ConfigsManager.EMBEDDING_CONFIG).getEmbedding(text);
    }
}