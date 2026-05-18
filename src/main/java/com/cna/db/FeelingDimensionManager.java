package com.cna.db;

import com.cna.config.ConfigsManager;
import com.cna.db.MemoryDB.FeelingDimension;
import com.cna.llm.CallResult;
import com.cna.llm.LLMAdapter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Slf4j
public class FeelingDimensionManager {

    private static FeelingDimensionManager instance;
    public static void init(MemoryDB memoryDB) {
        instance = new FeelingDimensionManager(memoryDB);
    }
    public static FeelingDimensionManager getInstance() {
        return instance;
    }

    private static final ObjectMapper jsonMapper = new ObjectMapper();
    private static final org.slf4j.Logger feelingLog = org.slf4j.LoggerFactory.getLogger("feeling-log");

    private final MemoryDB memoryDB;
    private static final double SIMILARITY_THRESHOLD = 0.6;
    private static final double NOVELTY_THRESHOLD = ConfigsManager.NOVELTY_THRESHOLD;

    // 仅依赖该阈值计算基础感觉权重
    private static final double BLUNT_WEIGHT = ConfigsManager.FD_BLUNT_WEIGHT;

    public FeelingDimensionManager(MemoryDB memoryDB) {
        this.memoryDB = memoryDB;
    }

    public void processTaskLogAsync(String taskLog) {
        if (taskLog == null || taskLog.trim().isEmpty()) return;

        CompletableFuture.runAsync(() -> {
            try {
                ArrayNode toolsArray = buildExtractionTool();
                LLMAdapter extractionLlm = new LLMAdapter(ConfigsManager.BRAIN_CONFIG);
                String prompt = buildDistillationPrompt(taskLog);

                CallResult result = extractionLlm.generateResponseWithTools(prompt, "", toolsArray);

                if (!result.isToolCall() || result.getToolCalls() == null || result.getToolCalls().isEmpty()) {
                    log.info("[Feeling] 未发现有价值刺激，跳过感觉更新。");
                    return;
                }

                JsonNode toolCall = result.getToolCalls().get(0);
                JsonNode argsNode = jsonMapper.readTree(toolCall.path("function").path("arguments").asText());
                JsonNode conceptsNode = argsNode.path("concepts");

                if (conceptsNode.isMissingNode() || !conceptsNode.isArray() || conceptsNode.isEmpty()) {
                    return;
                }

                List<FeelingDimension> currentDimensions = memoryDB.getAllFeelingDimensions();
                int addedCount = 0;
                int updatedCount = 0;

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

                    // 【核心状态机：基于调用次数 (trigger_count)】
                    if (bestMatch != null && highestSim >= NOVELTY_THRESHOLD) {
                        updatedCount++;
                        int oldTriggerCount = bestMatch.triggerCount;

                        // 1. 物理落盘并获取新值
                        int newTriggerCount = memoryDB.hitDimension(bestMatch.id);

                        // 2. 动态计算本次激活后的虚拟权重
                        //double computedWeight = calculateBaseWeight(newTriggerCount);

                        log.info("[Attention-Engine] 概念 [{}] 命中老维度 [{}] (相似度:{})。TriggerCount {} -> {}",
                                concept, bestMatch.concept, highestSim, oldTriggerCount, newTriggerCount);

                        feelingLog.info("HIT | concept={} | trigger={}→{} | sim={}",
                                bestMatch.concept, oldTriggerCount, newTriggerCount, String.format("%.4f", highestSim));

                        // 同步刷新本地列表缓存
                        currentDimensions.remove(bestMatch);
                        currentDimensions.add(new FeelingDimension(bestMatch.id, bestMatch.concept, bestMatch.vector, bestMatch.hitWeight, newTriggerCount));

                    } else {
                        // 新概念创建，自动采用 hit_weight=1.0, trigger_count=1
                        memoryDB.insertFeelingDimension(concept, conceptVector);
                        addedCount++;
                        log.info("[Feeling] 发现新刺激 [{}]，生成新神经节点。初始 trigger_count = 1", concept);
                        feelingLog.info("NEW      | concept={} | sim_to_nearest={}", concept, String.format("%.4f", highestSim));

                        currentDimensions.add(new FeelingDimension(-1, concept, conceptVector, 1.0, 1));
                    }
                }

                log.info("[Feeling] 本轮提取完毕：新节点 {} 个，激活旧节点 {} 个。", addedCount, updatedCount);

            } catch (Exception e) {
                log.error("[Feeling] 异步处理任务结算与维度更新失败", e);
            }
        });
    }

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
        return "现在你要从下面的任务执行日志中提取出 1 到 3 个最核心、最具体的【人类交互话题、外部技术概念、或者现实客观实体】。\n\n" +
                "⚠️【硬核禁忌边界——严禁提取任何系统底层框架噪音】:\n" +
                "1. 绝对不要提取系统的任何 Java 类名、接口或方法标识（例如：严禁提取 ChatTask, ConsoleChatTask, DefaultAgentTaskUnit, AbstractAgentTaskHandler 等）。\n" +
                "2. 绝对不要提取系统底层的固有工具名称和内部运行动作（例如：严禁提取 send_chat_message, add_inner_thought, executeCognitiveCycle, submit_extracted_concepts 等）。\n" +
                "3. 核心认知纠偏：上述词汇只是系统支撑自身运转的数字化骨架，不是人类在探讨的『客观概念』！你必须穿透这些框架痕迹，提炼出用户和系统在实践中具体解决的问题、使用的外部独立工具或讨论的话题（如：WSL网络端口映射、HOI4游戏战术、黑格尔辩证唯物主义等）。\n\n" +
                "规则：忽略废话、寒暄、纯情绪表达。只关注客观技术、工具、理论模型或具体的行为机制。\n" +
                "动作：提取完成后，必须且仅能调用 `submit_extracted_concepts` 工具来提交结果。如果没有价值，什么都不用做。\n\n" +
                "【日志输入】\n" + taskLog;
    }

    // ==========================================
    // 感觉评估核心架构
    // ==========================================

    public static class DimensionScore {
        public final String concept;
        public final double similarity;
        public final double weight;
        public final double finalScore;

        public DimensionScore(String concept, double similarity, double weight, double finalScore) {
            this.concept = concept;
            this.similarity = similarity;
            this.weight = weight;
            this.finalScore = finalScore;
        }
    }

    /**
     * 获取感觉权重数据
     */
    private double calculateBaseWeight(int triggerCount) {
        if (triggerCount >= BLUNT_WEIGHT) {
            return 1.0;
        }
        // 小于 BLUNT_WEIGHT 输出 triggerCount / BLUNT_WEIGHT
        return triggerCount / BLUNT_WEIGHT;
    }

    /**
     * 底座：获取和当前文本契合的所有感觉维度（默认按最高得分降序）
     */
    public List<DimensionScore> evaluateAllDimensions(String input) {
        double[] inputVector = getEmbeddingMock(input);
        List<FeelingDimension> dimensions = memoryDB.getAllFeelingDimensions();
        List<DimensionScore> scoredList = new ArrayList<>();

        if (dimensions.isEmpty()) return scoredList;

        for (FeelingDimension dim : dimensions) {
            double sim = cosineSimilarity(inputVector, dim.vector);
            double baseDynamicWeight = calculateBaseWeight(dim.triggerCount);
            double finalScore = sim * baseDynamicWeight;

            scoredList.add(new DimensionScore(dim.concept, sim, baseDynamicWeight, finalScore));
        }

        scoredList.sort((a, b) -> Double.compare(b.finalScore, a.finalScore));
        return scoredList;
    }

    /**
     * 通用化的高级格式化提取方法
     *
     * @param input        输入文本
     * @param highestFirst 是否从高到低排序 (true 为最高契合度在前，false 为最低契合度在前，适合找盲点/最无关的感觉)
     * @param limit        需要获取的数量限制
     * @return 返回饱含全量数据 (sim, weight, score) 的维度列表，方便外部灵活格式化
     */
    public List<DimensionScore> getTargetDimensions(String input, boolean highestFirst, int limit) {
        List<DimensionScore> allScores = evaluateAllDimensions(input);

        if (allScores.isEmpty() || limit <= 0) {
            return new ArrayList<>();
        }

        // evaluateAllDimensions 默认已经是最高在前 (降序)
        // 如果外部想要“最低契合度”在前，直接将列表倒置
        if (!highestFirst) {
            Collections.reverse(allScores);
        }

        // 截取指定长度防止越界
        int actualLimit = Math.min(limit, allScores.size());
        return new ArrayList<>(allScores.subList(0, actualLimit));
    }

    // ==========================================
    // 单点拦截 Gatekeeper 专用 (完全兼容老代码)
    // ==========================================

    public static class FeelingEvaluation {
        public final String topConcept;
        public final double finalScore;
        public FeelingEvaluation(String topConcept, double finalScore) {
            this.topConcept = topConcept;
            this.finalScore = finalScore;
        }
    }

    /**
     * 老接口兼容：获取最契合的单个感觉维度（保留特有的随机抽样打印 Top3 逻辑）
     */
    public FeelingEvaluation evaluateInput(String input) {
        boolean shouldLog = Math.random() < 0.1;

        // 直接使用底座获取所有排序好的节点
        List<DimensionScore> allScores = evaluateAllDimensions(input);

        if (allScores.isEmpty()) {
            if (shouldLog) feelingLog.info("EVAL     | input={} | verdict=NO_DIMS", truncate(input, 30));
            return new FeelingEvaluation("none", 0.0);
        }

        DimensionScore bestMatch = allScores.get(0);

        if (shouldLog) {
            StringBuilder top3 = new StringBuilder("[");
            int n = Math.min(3, allScores.size());
            for (int i = 0; i < n; i++) {
                DimensionScore ds = allScores.get(i);
                top3.append(String.format("%s:sim=%.3f×w=%.2f=%.3f", ds.concept, ds.similarity, ds.weight, ds.finalScore));
                if (i < n - 1) top3.append(", ");
            }
            top3.append("]");
            feelingLog.info("EVAL     | input={} | top3={} | best_score={}",
                    truncate(input, 30), top3.toString(), String.format("%.4f", bestMatch.finalScore));
        }

        return new FeelingEvaluation(bestMatch.concept, bestMatch.finalScore);
    }

    private static String truncate(String s, int n) {
        if (s == null) return "";
        s = s.replace("\n", "\\n");
        return s.length() <= n ? s : s.substring(0, n) + "...";
    }

    public void tick() {
        log.debug("[Feeling] 触发全局心跳 (Tick), 所有维度的 trigger_count -1");
        memoryDB.applyGlobalTick();
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
