package com.cna.db;

import com.cna.config.ConfigsManager;
import com.cna.db.MemoryDB.FeelingDimension;
import com.cna.llm.CallResult;
import com.cna.llm.LLMAdapter;
import com.cna.llm.LLManager;
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

    private static volatile FeelingDimensionManager instance;

    public static synchronized void init(MemoryDB memoryDB) {
        if (instance == null) {
            instance = new FeelingDimensionManager(memoryDB);
        }
    }

    public static FeelingDimensionManager getInstance() {
        return instance;
    }

    private static final ObjectMapper jsonMapper = new ObjectMapper();
    private static final org.slf4j.Logger feelingLog = org.slf4j.LoggerFactory.getLogger("feeling-log");

    private static final LLMAdapter embLLM = new LLMAdapter(ConfigsManager.EMBEDDING_CONFIG);

    private final MemoryDB memoryDB;
    //private static final double SIMILARITY_THRESHOLD = 0.6;
    private static final double NOVELTY_THRESHOLD = ConfigsManager.NOVELTY_THRESHOLD;
    private static final int HABITUATION_LIMIT = ConfigsManager.FD_HABITUATION_LIMIT;

    // 仅依赖该阈值计算基础感觉权重
    //private static final double BLUNT_WEIGHT = ConfigsManager.FD_BLUNT_WEIGHT;

    public FeelingDimensionManager(MemoryDB memoryDB) {
        this.memoryDB = memoryDB;
    }

    private static final double ALPHA = ConfigsManager.FD_QUALITY_WEIGHT;

    // ==========================================
    // 显式概念直接处理 (FinishTask 专用，无LLM二次提取)
    // ==========================================

    public static class ConceptInput {
        public final String name;
        public final boolean isPositive;

        public ConceptInput(String name, boolean isPositive) {
            this.name = name;
            this.isPositive = isPositive;
        }
    }

    /**
     * 直接处理 LLM 在 finish_task 时提交的显式概念，异步更新维度。
     * 不调用 LLM，直接进行向量匹配与权重结算。
     */
    private final Object dimensionLock = new Object();

    public void processExplicitConceptsAsync(List<ConceptInput> concepts) {
        if (concepts == null || concepts.isEmpty()) return;

        CompletableFuture.runAsync(() -> {
            try {
                int addedCount = 0;
                int updatedCount = 0;

                for (ConceptInput ci : concepts) {
                    String concept = ci.name.trim();
                    if (concept.isEmpty()) continue;
                    double targetPolarity = ci.isPositive ? 1.0 : -1.0;

                    double[] conceptVector = getEmbeddingMock(concept);

                    // 整个"读 → 匹配 → 写"链条原子化，防止 Lost Update
                    synchronized (dimensionLock) {
                        List<FeelingDimension> currentDimensions = memoryDB.getAllFeelingDimensionsSafe(this::getEmbeddingMock);
                        FeelingDimension bestMatch = null;
                        double highestSim = -1.0;

                        for (FeelingDimension dim : currentDimensions) {
                            double sim = cosineSimilarity(conceptVector, dim.vector);
                            if (sim > highestSim) {
                                highestSim = sim;
                                bestMatch = dim;
                            }
                        }

                        if (bestMatch != null && highestSim >= NOVELTY_THRESHOLD) {
                            updatedCount++;
                            int newTriggerCount = memoryDB.hitDimension(bestMatch.id);
                            double newHitWeight = bestMatch.hitWeight * (1 - ALPHA) + targetPolarity * ALPHA;
                            memoryDB.updateDimensionHitWeight(bestMatch.id, newHitWeight);

                            log.info("[Attention-Engine] 概念 [{}] 命中老维度 [{}]。Trigger: {}->{}, Weight: {}->{}",
                                    concept, bestMatch.concept, bestMatch.triggerCount, newTriggerCount,
                                    String.format("%.3f", bestMatch.hitWeight), String.format("%.3f", newHitWeight));
                        } else {
                            memoryDB.insertFeelingDimension(concept, conceptVector, targetPolarity * ALPHA);
                            addedCount++;
                            log.info("[Feeling] 发现新刺激 [{}] 生成新神经节点。初始 Trigger=1, 初始极性={}", concept, targetPolarity);
                        }
                    }
                }

                log.info("[Feeling] 显式概念处理完毕：新节点 {} 个，重塑旧节点 {} 个。", addedCount, updatedCount);
                this.tick();  // 成功处理后触发全局衰减

            } catch (Exception e) {
                log.error("[Feeling] 显式概念处理失败", e);
            }
        }, LLManager.getExecutor());
    }

    private String buildDistillationPrompt(String taskLog) {
        return "现在你要从下面的任务执行日志中提取出若干个最核心、最具体的【人类交互话题、外部技术概念、或者现实客观实体】，并且严格判断它们在本次事件中的唯物极性。\n\n" +
                "⚠️【硬核禁忌边界——严禁提取任何系统底层框架噪音】:\n" +
                "1. 绝对不要提取系统的任何 Java 类名、接口或方法标识。\n" +
                "2. 绝对不要提取系统底层的固有工具名称和内部运行动作。\n" +
                "3. 核心认知纠偏：穿透系统运行痕迹，提炼出具体的问题或话题。并针对此概念的客观发展方向，给出一个true(建设性)或false(破坏性)的布尔断言。\n\n" +
                "【日志输入】\n" + taskLog;
    }

    // ==========================================
    // 感觉评估核心架构
    // ==========================================

    public static class DimensionScore {
        public final String concept;
        public final double similarity;
        public final double hitWeight; // 【新增】：保留原汁原味的极性效价
        public final double InterestScore;

        // 【修改】：构造器加入 hitWeight
        public DimensionScore(String concept, double similarity, double hitWeight, double finalScore) {
            this.concept = concept;
            this.similarity = similarity;
            this.hitWeight = hitWeight;
            this.InterestScore = finalScore;
        }
    }

    public List<DimensionScore> evaluateAllDimensions(String input) {
        double[] inputVector = getEmbeddingMock(input);
        List<FeelingDimension> dimensions = memoryDB.getAllFeelingDimensionsSafe(this::getEmbeddingMock);
        List<DimensionScore> scoredList = new ArrayList<>();

        if (dimensions.isEmpty()) return scoredList;

        for (FeelingDimension dim : dimensions) {
            double sim = cosineSimilarity(inputVector, dim.vector);
            double baseDynamicArousal = calculateBaseWeight(dim.triggerCount);

            // 【核心运用】：综合得分 = 基础相似度 * 活跃程度 * 当前累积效价极性的【绝对值】
            //double finalScore = sim * baseDynamicArousal * Math.abs(dim.hitWeight);
            double finalScore = sim * baseDynamicArousal; //暂时不使用好坏评判权重来判断……

            // 【修改】：将 dim.hitWeight 一起封装出去
            scoredList.add(new DimensionScore(dim.concept, sim, dim.hitWeight, finalScore));
        }

        scoredList.sort((a, b) -> Double.compare(b.InterestScore, a.InterestScore));
        return scoredList;
    }

    /**
     * 【重构：线性脱敏衰减曲线】
     * 规则：
     * 1. 第一次遇到 (triggerCount <= 1)，觉得最新鲜，返回最高权重 1.0
     * 2. 调用次数超过阈值 (triggerCount >= BLUNT_WEIGHT)，彻底听烦了，返回最低权重 0.1
     * 3. 在 1 到 BLUNT_WEIGHT 之间，权重呈完美的线性匀速下降。
     */
    private double calculateBaseWeight(int triggerCount) {

        // 状态 1：最新鲜，刚长出来的概念，或者从水底刚刚浮出水面
        if (triggerCount <= 1) {
            return 1.0;
        }

        // 状态 2：彻底脱敏，听腻了
        if (triggerCount >= HABITUATION_LIMIT) {
            return 1.0 / HABITUATION_LIMIT;
        }

        // 状态 3：线性衰减期
        // 计算当前的疲劳进度百分比 (0.0 到 1.0 之间)
        double progress = (double) (triggerCount - 1) / (HABITUATION_LIMIT);

        return 1.0 - progress;
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
        public final double InterestScore;
        /** 刺激度加成 (0~0.5)，基于 top3 感觉维度的 abs(hitWeight) 均值，用于 Gatekeeper 过滤和优先级排序 */
        public final double stimulusBonus;

        public FeelingEvaluation(String topConcept, double finalScore, double stimulusBonus) {
            this.topConcept = topConcept;
            this.InterestScore = finalScore;
            this.stimulusBonus = stimulusBonus;
        }
    }

    /**
     * 老接口兼容：获取最契合的单个感觉维度（保留特有的随机抽样打印 Top3 逻辑）
     * 现在同时计算 top3 abs(hitWeight) 刺激度加成，叠加到 InterestScore 中
     */
    public FeelingEvaluation evaluateInput(String input) {
        boolean shouldLog = Math.random() < 0.1;

        // 直接使用底座获取所有排序好的节点
        List<DimensionScore> allScores = evaluateAllDimensions(input);

        if (allScores.isEmpty()) {
            if (shouldLog) feelingLog.info("EVAL     | input={} | verdict=NO_DIMS", truncate(input, 30));
            return new FeelingEvaluation("none", 0.0, 0.0);
        }

        DimensionScore bestMatch = allScores.get(0);

        // 计算 topN 的刺激度加成：abs(hitWeight) 求和 / (FEELING_DIMENSION_COUNT * 2)，范围 0~0.5
        int topN = Math.min(ConfigsManager.FEELING_DIMENSION_COUNT, allScores.size());
        double absHitWeightSum = 0.0;
        for (int i = 0; i < topN; i++) {
            absHitWeightSum += Math.abs(allScores.get(i).hitWeight);
        }
        double stimulusBonus = absHitWeightSum / (ConfigsManager.FEELING_DIMENSION_COUNT * 2.0);

        // 将刺激度加成叠加到最终的 InterestScore 上
        double adjustedScore = bestMatch.InterestScore;

        if (shouldLog) {
            StringBuilder top3 = new StringBuilder("[");
            int n = Math.min(3, allScores.size());
            for (int i = 0; i < n; i++) {
                DimensionScore ds = allScores.get(i);
                top3.append(String.format("%s:sim=%.3f×w=%.2f=%.3f", ds.concept, ds.similarity, ds.hitWeight, ds.InterestScore));
                if (i < n - 1) top3.append(", ");
            }
            top3.append("]");
            feelingLog.info("EVAL     | input={} | top3={} | best_score={} | stimulusBonus={} | adjusted={}",
                    truncate(input, 30), top3.toString(),
                    String.format("%.4f", bestMatch.InterestScore),
                    String.format("%.3f", stimulusBonus),
                    String.format("%.4f", adjustedScore));
        }

        return new FeelingEvaluation(bestMatch.concept, adjustedScore, stimulusBonus);
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
        return LLManager.getTextVector(text, embLLM);
    }
}
