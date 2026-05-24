package com.cna.agent;

import com.cna.config.ConfigsManager;
import com.cna.config.ScenePromptsManager;
import com.cna.db.MemoryDB;
import com.cna.llm.CallResult;
import com.cna.llm.LLMAdapter;
import com.cna.llm.LLManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public class MemoryManager {

    private static volatile MemoryManager INSTANCE;

    public static MemoryManager getInstance() {
        if (INSTANCE == null) {
            synchronized (MemoryManager.class) {
                if (INSTANCE == null) {
                    INSTANCE = new MemoryManager();
                }
            }
        }
        return INSTANCE;
    }

    private final MemoryDB db;
    private final LLMAdapter summaryLLM;
    private final LLMAdapter embLLM;

    private final int EMB_MEMORY_SIZE;
    private final int CURRENT_MEMORYS_MAXSIZE;

    // ==========================================
    // 【新增】：潜意识神经索 (后台折叠专用线程)
    // ==========================================
    private final ExecutorService subconsciousExecutor = Executors.newSingleThreadExecutor();

    // 【新增】：防止精神分裂的锁。如果后台正在折叠，就不允许触发新的折叠
    private final AtomicBoolean isConsolidating = new AtomicBoolean(false);

    private MemoryManager() {
        this.db = new MemoryDB();
        this.summaryLLM = new LLMAdapter(ConfigsManager.BRAIN_CONFIG);
        this.embLLM = new LLMAdapter(ConfigsManager.EMBEDDING_CONFIG);
        this.EMB_MEMORY_SIZE = ConfigsManager.EMB_MEMORY_SIZE;
        this.CURRENT_MEMORYS_MAXSIZE = ConfigsManager.CURRENT_MEMORIES_MAXSIZE;
    }

    // ==========================================
    // 功能 1 & 3: 压入记忆 (非阻塞异步折叠)
    // ==========================================
    public void inputCurrentMemorys(List<String> memories) {
        // 1. 瞬间把记忆落盘，绝不卡顿
        for (String mem : memories) {
            db.insertCurrentMemory(mem);
        }

        // 2. 检查是否溢出水位线
        int currentSize = db.getCurrentMemoryCount();
        if (currentSize > (EMB_MEMORY_SIZE + CURRENT_MEMORYS_MAXSIZE)) {

            // 3. 【核心异步发射】：试图获取潜意识锁
            if (isConsolidating.compareAndSet(false, true)) {
                log.info("[MemoryManager] 记忆水位超标 ({}), 唤醒潜意识，在后台开始折叠...", currentSize);

                // 把沉重的总结和向量计算直接扔给后台线程，主线程瞬间 return，继续干活！
                subconsciousExecutor.submit(() -> {
                    try {
                        consolidateMemory();
                    } catch (Exception e) {
                        log.error("[MemoryManager] 潜意识记忆折叠发生异常", e);
                    } finally {
                        // 无论成功失败，折叠完必须释放锁，允许下次折叠
                        isConsolidating.set(false);
                        log.info("[MemoryManager] 潜意识折叠完毕，锁已释放。");
                    }
                });
            } else {
                // 如果锁被占了（正在折叠），就静悄悄地跳过，反正记忆已经存进 db 了，等下次再折
                log.debug("[MemoryManager] 潜意识正在忙碌，跳过本次折叠触发。");
            }
        }
    }

    public void inputCurrentMemory(String memory){
        List<String> a = new LinkedList<>();
        a.add(memory);
        this.inputCurrentMemorys(a);
    }

    private void consolidateMemory() {
        List<String> oldMemories = db.popOldestCurrentMemories(EMB_MEMORY_SIZE);
        if (oldMemories.isEmpty()) return;

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < oldMemories.size(); i++) {
            sb.append("[").append(i).append("] ").append(oldMemories.get(i)).append("\n");
        }

        Map<String, Object> data = new HashMap<>();
        data.put("text", sb.toString());

        // 1. 获取 Tool 定义
        //ArrayNode extractTool = buildMemoryExtractorTool();

        log.info("[MemoryManager] 正在呼叫 LLM 提炼并压缩 {} 条记忆...", oldMemories.size());

        // 2. 发起请求，传入 Tool
        CallResult result;
        try {
            result = LLManager.executeSceneAsyncWithCache(new ScenePromptsManager(this.getClass().getName()).getSolvingPrompt(), data, summaryLLM, buildMemoryExtractorTool())
                    .get(90, TimeUnit.SECONDS);
        } catch (ExecutionException | InterruptedException | TimeoutException e) {
            throw new RuntimeException(e);
        }

        // 3. 直接拦截 Tool Call
        if (result.isToolCall() && result.getToolCalls() != null && !result.getToolCalls().isEmpty()) {
            LLManager.clearCache();
            try {
                // 精确提取第一个工具调用的 arguments 字符串
                JsonNode firstToolCall = result.getToolCalls().get(0);
                String argumentsJson = firstToolCall.path("function").path("arguments").asText();

                ObjectMapper mapper = new ObjectMapper();
                // 解析 arguments 里面的 JSON 对象
                JsonNode argsNode = mapper.readTree(argumentsJson);

                // 现在可以安全地拿到 points 数组了
                JsonNode pointsNode = argsNode.get("points");

                if (pointsNode != null && pointsNode.isArray()) {
                    log.info("[MemoryManager] LLM 使用 Tool 成功返回了 {} 条记忆", pointsNode.size());

                    for (JsonNode pointNode : pointsNode) {
                        String point = pointNode.asText();
                        if (point.isBlank()) continue;

                        // 向量化并入库
                        double[] vector = LLManager.getTextVector(point, embLLM);
                        if (vector != null && vector.length > 0) {
                            db.insertDeepMemory(vector, point);
                            log.debug("[MemoryManager] 深度记忆存入：{}", point);
                        }
                    }
                } else {
                    log.warn("[MemoryManager] 解析成功，但没有找到 points 数组，返回数据: {}", argumentsJson);
                }
            } catch (Exception e) {
                log.error("[MemoryManager] 解析 Tool 参数失败，原始数据: {}", result.getToolCalls().toString(), e);
            }
        }
    }

    // ==========================================
    // 功能 2: 获取短期记忆
    // ==========================================
    public List<String> getCurrentMemorys(int n) {
        return db.getLatestCurrentMemories(n);
    }

    public List<String> getDeepMemorys(double[] queryVector, int n) {
        List<MemoryDB.DeepMemoryEntry> allMemories = db.getAllDeepMemories();

        // 增加调试日志：看看数据库到底吐出来几条
        log.info("[MemoryManager] 深度记忆库原始数据量: {}, 准备召回 Top: {}", allMemories.size(), n);

        if (allMemories.isEmpty()) {
            log.warn("[MemoryManager] 警告：深度记忆库目前是空的。");
            return new ArrayList<>();
        }

        PriorityQueue<SimilarityRecord> pq = new PriorityQueue<>(
                (a, b) -> Double.compare(b.similarity, a.similarity)
        );

        for (MemoryDB.DeepMemoryEntry entry : allMemories) {
            // 检查向量维度是否一致
            if (entry.vector == null || entry.vector.length != queryVector.length) {
                log.error("[MemoryManager] 维度不匹配！库内维度: {}, 查询维度: {}",
                        (entry.vector != null ? entry.vector.length : "null"), queryVector.length);
                continue;
            }

            double sim = cosineSimilarity(queryVector, entry.vector);
            pq.offer(new SimilarityRecord(entry.content, sim));
        }

        List<String> result = new ArrayList<>();
        // 这里确保即使 pq 里的数量不足 n，也会返回全部
        while (!pq.isEmpty() && result.size() < n) {
            SimilarityRecord record = pq.poll();
            // 建议增加一个极低的相似度过滤（可选），防止完全无关的内容干扰模型
            if (record.similarity > 0.1) {
                result.add(record.content);
            }
        }

        log.info("[MemoryManager] 最终召回记忆数量: {}", result.size());
        return result;
    }

    // ==========================================
    // 功能 5: 文本直搜深度记忆 (桥接方法供 Tool 调用)
    // ==========================================
    public List<String> searchDeepMemoryByText(String queryText, int n) {
        log.info("[MemoryManager] 正在将查询词向量化: {}", queryText);
        // 调用 Embedding 模型将搜索词转化为向量
        double[] queryVector = LLManager.getTextVector(queryText, embLLM);

        if (queryVector == null || queryVector.length == 0) {
            log.error("[MemoryManager] 查询词向量化失败！");
            return new ArrayList<>();
        }

        // 调用你已有的向量召回方法
        return getDeepMemorys(queryVector, n);
    }

    // 余弦相似度数学计算
    private double cosineSimilarity(double[] vectorA, double[] vectorB) {
        if (vectorA.length != vectorB.length) return 0.0;
        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < vectorA.length; i++) {
            dotProduct += vectorA[i] * vectorB[i];
            normA += Math.pow(vectorA[i], 2);
            normB += Math.pow(vectorB[i], 2);
        }
        if (normA == 0.0 || normB == 0.0) return 0.0;
        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    /**
     * 系统停机时，安全关闭潜意识线程
     */
    public void stop() {
        if (subconsciousExecutor != null && !subconsciousExecutor.isShutdown()) {
            subconsciousExecutor.shutdown();
        }
    }

    private static class SimilarityRecord {
        String content;
        double similarity;
        SimilarityRecord(String content, double similarity) {
            this.content = content;
            this.similarity = similarity;
        }
    }

    private ArrayNode buildMemoryExtractorTool() {
        ObjectMapper mapper = new ObjectMapper();
        ArrayNode tools = mapper.createArrayNode();

        ObjectNode tool = mapper.createObjectNode();
        tool.put("type", "function");

        ObjectNode function = mapper.createObjectNode();
        function.put("name", "save_memory_points");
        function.put("description", "将提炼后的多个独立记忆点保存到长期记忆库");

        // 定义 parameters 的 JSON Schema
        ObjectNode parameters = mapper.createObjectNode();
        parameters.put("type", "object");

        ObjectNode properties = mapper.createObjectNode();
        ObjectNode points = mapper.createObjectNode();
        points.put("type", "array");
        points.put("description", "提炼出的独立记忆点列表");
        ObjectNode items = mapper.createObjectNode();
        items.put("type", "string");
        points.set("items", items);

        properties.set("points", points);
        parameters.set("properties", properties);

        // 强制必须包含 points 字段
        ArrayNode required = mapper.createArrayNode();
        required.add("points");
        parameters.set("required", required);

        function.set("parameters", parameters);
        tool.set("function", function);
        tools.add(tool);

        return tools;
    }
}