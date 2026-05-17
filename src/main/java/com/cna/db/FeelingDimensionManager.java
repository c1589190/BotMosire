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

import java.util.ArrayList;
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
    private static final org.slf4j.Logger feelingLog = org.slf4j.LoggerFactory.getLogger("feeling-log");

    private final MemoryDB memoryDB;
    private static final double SIMILARITY_THRESHOLD = 0.6;
    private static final double NOVELTY_THRESHOLD = ConfigsManager.NOVELTY_THRESHOLD;
    private static final double DECAY_CONSTANT = ConfigsManager.DECAY_CONSTANT;

    // 注意力机制弹性参数配置（统一转换为最高精度的 double 进行逻辑判定）
    private static final int HABITUATION_LIMIT = ConfigsManager.FD_HABITUATION_LIMIT;
    private static final double BLUNT_WEIGHT = ConfigsManager.FD_BLUNT_WEIGHT; // 注意力脱敏重置下限
    private static final double MAX_WEIGHT = ConfigsManager.FD_MAX_WEIGHT;     // 注意力兴奋上限保护

    // 【重构核心】：审美疲劳期单次调用的衰减比例（除数）。
    // 例如设为 1.25，意味着每轰炸一次，注意力直接缩水到原来的 80% (1 / 1.25)
    private static final double SATIATION_DECAY_RATIO = 1.25;

    public FeelingDimensionManager(MemoryDB memoryDB) {
        this.memoryDB = memoryDB;
    }

    /**
     * 【类人注意力·自适应比例相除版】：异步解析完结任务，精确进行高精度状态机迭代
     */
    public void processTaskLogAsync(String taskLog) {
        if (taskLog == null || taskLog.trim().isEmpty()) return;

        CompletableFuture.runAsync(() -> {
            try {
                // 1. 组装强制模型吐出数组的 JSON 工具约束
                ArrayNode toolsArray = buildExtractionTool();
                LLMAdapter extractionLlm = new LLMAdapter(ConfigsManager.BRAIN_CONFIG);
                String prompt = buildDistillationPrompt(taskLog);

                // 2. 调用非流式 Tool Calling 接口
                CallResult result = extractionLlm.generateResponseWithTools(prompt, "", toolsArray);

                // 3. 安全解析工具调用返回
                if (!result.isToolCall() || result.getToolCalls() == null || result.getToolCalls().isEmpty()) {
                    log.info("[Feeling] 任务流中未发现有价值的唯物刺激，或模型判定无价值，跳过感觉更新。");
                    return;
                }

                JsonNode toolCall = result.getToolCalls().get(0);
                String argumentsStr = toolCall.path("function").path("arguments").asText();
                JsonNode argsNode = jsonMapper.readTree(argumentsStr);

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

                    // 5. 核心逻辑：类人注意力状态机（高精度双精度重构，无损兼容原版 MemoryDB）
                    if (bestMatch != null && highestSim >= NOVELTY_THRESHOLD) {
                        updatedCount++;

                        int currentCount = bestMatch.triggerCount;
                        // 提取时瞬间拉高到 double，杜绝后续微观运算的精度蒸发
                        double currentWeight = bestMatch.weight;

                        double finalLocalWeight;
                        int finalLocalCount;

                        if (currentCount < HABITUATION_LIMIT) {
                            // 【状态 A：新鲜拓展期】正常线性加权反哺，控顶在 MAX_WEIGHT
                            double weightToAdd = highestSim;
                            if (currentWeight + weightToAdd > MAX_WEIGHT) {
                                weightToAdd = MAX_WEIGHT - currentWeight;
                                if (weightToAdd < 0) weightToAdd = 0.0;
                            }

                            finalLocalWeight = currentWeight + weightToAdd;
                            finalLocalCount = currentCount + 1;

                            // 物理落盘：在调用边界将 double 精确转换为原方法需要的 float
                            memoryDB.addWeightToDimension(bestMatch.id, (float) weightToAdd);
                            log.info("[Attention-Engine] 概念 [{}] 命中老维度 [{}] (相似度:{%.4f})，处于拓展期。权重加成后: %.4f, 触发计数: {}/{}",
                                    concept, bestMatch.concept, highestSim, finalLocalWeight, finalLocalCount, HABITUATION_LIMIT);
                            feelingLog.info("BOOST    | concept={} | weight={}→{} | sim={} | count={}/{} | novelty_thr={}",
                                    bestMatch.concept,
                                    String.format("%.4f", currentWeight), String.format("%.4f", finalLocalWeight),
                                    String.format("%.4f", highestSim),
                                    finalLocalCount, HABITUATION_LIMIT, NOVELTY_THRESHOLD);
                        } else {
                            // 【状态 B：审美疲劳期】高频调用超过上限，开启【比例相除衰减机制】
                            // 计算衰减后的绝对目标值
                            double targetWeight = currentWeight / SATIATION_DECAY_RATIO;
                            // 逆运算求出需要灌入数据库 hit_weight 加法公式中的负数差值（Delta）
                            double weightDelta = targetWeight - currentWeight;

                            finalLocalWeight = targetWeight;
                            finalLocalCount = currentCount + 1;

                            log.warn("[Attention-Engine] ⚠️ 审美疲劳：概念 [{}] 连续轰炸老维度 [{}]。激活比例降级指数，目标新权重: %.4f, 连续频次: {}",
                                    concept, bestMatch.concept, finalLocalWeight, finalLocalCount);

                            // 【状态 C：脱敏触底，彻底重置复活】
                            if (finalLocalWeight <= BLUNT_WEIGHT) {
                                finalLocalWeight = BLUNT_WEIGHT;
                                finalLocalCount = 0; // 计数归零！解开疲劳锁，迎接下一次新鲜感

                                // 原版 bluntDimension 接收 double，完美高精度对接
                                memoryDB.bluntDimension(bestMatch.id, BLUNT_WEIGHT);
                                log.info("[Attention-Engine] 🔥 核心突触 [{}] 注意力彻底枯竭安全触底。完成全面无感脱敏，状态重置，随时准备再次复活！", bestMatch.concept);
                                feelingLog.warn("RESET    | concept={} | weight={}→{} | count={}→0 | blunt_thr={}",
                                        bestMatch.concept,
                                        String.format("%.4f", currentWeight), String.format("%.4f", BLUNT_WEIGHT),
                                        currentCount, BLUNT_WEIGHT);
                            } else {
                                // 未触底，通过负数 Delta 注入原版加过的方法，实现无损相除扣分
                                memoryDB.addWeightToDimension(bestMatch.id, (float) weightDelta);
                                feelingLog.warn("FATIGUE  | concept={} | weight={}→{} | count={} | ratio={} | habit_limit={}",
                                        bestMatch.concept,
                                        String.format("%.4f", currentWeight), String.format("%.4f", finalLocalWeight),
                                        finalLocalCount, SATIATION_DECAY_RATIO, HABITUATION_LIMIT);
                            }
                        }

                        // 动态擦除老快照，防止单轮批处理后续相似词汇拿到脏数据
                        currentDimensions.remove(bestMatch);
                        currentDimensions.add(new FeelingDimension(bestMatch.id, bestMatch.concept, bestMatch.vector, (float) finalLocalWeight, finalLocalCount));

                    } else {
                        // 新概念初始给 1.0 权重
                        memoryDB.insertFeelingDimension(concept, conceptVector, 1.0f);
                        addedCount++;
                        log.info("[Feeling] 概念 [{}] 属于未知刺激(最高相似度:{%.4f}), 顺利生长出新维度节点。", concept, highestSim);
                        feelingLog.info("NEW      | concept={} | weight=1.00 | sim_to_nearest={} | novelty_thr={}",
                                concept, String.format("%.4f", highestSim), NOVELTY_THRESHOLD);
                        currentDimensions.add(new FeelingDimension(-1, concept, conceptVector, 1.0f, 1));
                    }
                }

                log.info("[Feeling] 本轮感觉大网交配提炼完毕：新长出突触 {} 个，自适应状态迭代旧维度 {} 个。", addedCount, updatedCount);

            } catch (Exception e) {
                log.error("[Feeling] 异步处理任务结算与维度更新失败", e);
            }
        });
    }

    // ==========================================
    // 内部工具与原有机制维持 (完全不动)
    // ==========================================

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

    public FeelingEvaluation evaluateInput(String input) {
        double[] inputVector = getEmbeddingMock(input);
        List<FeelingDimension> dimensions = memoryDB.getAllFeelingDimensions();
        boolean shouldLog = Math.random() < 0.1;

        if (dimensions.isEmpty()) {
            if (shouldLog) {
                feelingLog.info("EVAL     | input={} | verdict=NO_DIMS", truncate(input, 30));
            }
            return new FeelingEvaluation("none", 0.0f);
        }

        List<double[]> scored = new ArrayList<>(dimensions.size());
        for (int i = 0; i < dimensions.size(); i++) {
            FeelingDimension dim = dimensions.get(i);
            double sim = cosineSimilarity(inputVector, dim.vector);
            double finalScore = sim * dim.weight;
            scored.add(new double[]{i, sim, finalScore});
        }
        scored.sort((a, b) -> Double.compare(b[2], a[2]));

        FeelingDimension bestMatch = dimensions.get((int) scored.get(0)[0]);
        double highestFinalScore = scored.get(0)[2];

        if (shouldLog) {
            StringBuilder top3 = new StringBuilder("[");
            int n = Math.min(3, scored.size());
            for (int i = 0; i < n; i++) {
                FeelingDimension d = dimensions.get((int) scored.get(i)[0]);
                double s = scored.get(i)[1];
                double f = scored.get(i)[2];
                top3.append(String.format("%s:sim=%.3f×w=%.2f=%.3f", d.concept, s, d.weight, f));
                if (i < n - 1) top3.append(", ");
            }
            top3.append("]");
            feelingLog.info("EVAL     | input={} | top3={} | best_score={}",
                    truncate(input, 30), top3.toString(), String.format("%.4f", highestFinalScore));
        }

        log.debug("[Feeling] 最佳匹配维度: {}, 最终得分: {}", bestMatch.concept, highestFinalScore);
        return new FeelingEvaluation(bestMatch.concept, (float) highestFinalScore);
    }

    private static String truncate(String s, int n) {
        if (s == null) return "";
        s = s.replace("\n", "\\n");
        return s.length() <= n ? s : s.substring(0, n) + "...";
    }

    public void tick() {
        log.info("[Feeling] 触发全局记忆衰减 (Tick), 扣除常数: {}", DECAY_CONSTANT);
        int forgottenNodes = memoryDB.applyGlobalDecay(DECAY_CONSTANT);
        feelingLog.info("DECAY    | amount={} | forgotten={} | note=trigger_count_also_-1",
                DECAY_CONSTANT, forgottenNodes);
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