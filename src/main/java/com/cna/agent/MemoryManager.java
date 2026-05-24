package com.cna.agent;

import com.cna.Utils;
import com.cna.config.ConfigsManager;
import com.cna.config.ScenePromptsManager;
import com.cna.db.MDManager;
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

        // =========================================================
        // 【核心修复】：补全 FreeMarker 模板所需的全局环境变量
        // =========================================================
        data.put("now_time", Utils.getNowPrecise());
        data.put("current_thoughts", MDManager.read("thoughts.md", ""));
        data.put("tools_guide", MDManager.read("prompts/toolsGuide.md", ""));

        log.info("[MemoryManager] 正在呼叫 LLM 提炼并压缩 {} 条记忆...", oldMemories.size());

        ObjectMapper mapper = new ObjectMapper();
        ArrayNode messages = mapper.createArrayNode();

        // 1. 组装 System Prompt
        ObjectNode sysMsg = mapper.createObjectNode();
        sysMsg.put("role", "system");
        sysMsg.put("content", "你是一个专门负责提炼、压缩并保存核心记忆的潜意识引擎。");
        messages.add(sysMsg);

        // 2. 渲染并组装 User Prompt
        String template = new ScenePromptsManager(this.getClass().getName()).getSolvingPrompt();

        // 这里现在可以正常渲染了，因为 data 里面有了 now_time 等变量
        String userPrompt = LLManager.render(template, data);

        ObjectNode userMsg = mapper.createObjectNode();
        userMsg.put("role", "user");
        userMsg.put("content", userPrompt);
        messages.add(userMsg);

        // 3. 直接调用底层的 generateResponseWithTools
        CallResult result = summaryLLM.generateResponseWithTools(messages, buildMemoryExtractorTool());

        // 4. 解析结果
        if (result.isToolCall() && result.getToolCalls() != null && !result.getToolCalls().isEmpty()) {
            try {
                // 精确提取第一个工具调用的 arguments 字符串
                JsonNode firstToolCall = result.getToolCalls().get(0);
                String argumentsJson = firstToolCall.path("function").path("arguments").asText();

                // 解析 arguments 里面的 JSON 对象（容错：LLM 可能将数组的 ] 误写为 }）
                JsonNode argsNode = safeParseArguments(argumentsJson, mapper);
                if (argsNode == null) {
                    log.warn("[MemoryManager] arguments JSON 解析失败（含修复重试），跳过本次折叠。原始: {}", argumentsJson);
                    return;
                }

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
        } else {
            log.warn("[MemoryManager] 模型未返回预期的工具调用，潜意识折叠失败。模型响应: {}", result.getContent());
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

    /**
     * 容错解析 LLM 返回的 arguments JSON。
     * LLM 可能将数组的 ] 误写为 }，导致 Jackson 抛出括号不匹配异常。
     * 此方法先尝试直接解析，失败则自动修复后重试。
     *
     * @param argumentsJson LLM 返回的原始 arguments 字符串
     * @param mapper        ObjectMapper 实例
     * @return 解析成功的 JsonNode，修复后仍失败则返回 null
     */
    private JsonNode safeParseArguments(String argumentsJson, ObjectMapper mapper) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return null;
        }
        // 第一次：直接解析
        try {
            return mapper.readTree(argumentsJson);
        } catch (com.fasterxml.jackson.core.JsonParseException e) {
            log.warn("[MemoryManager] arguments JSON 直接解析失败，尝试自动修复。错误: {}", e.getOriginalMessage());
        } catch (Exception e) {
            log.warn("[MemoryManager] arguments JSON 直接解析出现未知异常，尝试自动修复。错误: {}", e.getMessage());
        }

        // 第二次：修复后重试
        String repaired = repairJsonBrackets(argumentsJson);
        if (repaired == null) {
            return null;
        }
        try {
            return mapper.readTree(repaired);
        } catch (Exception e) {
            log.error("[MemoryManager] arguments JSON 修复后仍解析失败。修复后内容: {}", repaired, e);
            return null;
        }
    }

    /**
     * 修复 JSON 中数组/对象括号不匹配问题。
     * 策略：从后往前扫描，将多余的 } 替换为 ]（针对 \"Unexpected close marker '}'\" 错误）。
     *
     * @param json 原始 JSON 字符串
     * @return 修复后的 JSON，若无法修复则返回 null
     */
    private String repairJsonBrackets(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        // 使用栈来跟踪括号深度，找出不匹配的 }
        StringBuilder sb = new StringBuilder(json);
        // 从后往前扫描，找到第一个导致栈为负的 }
        // 简化策略：统计 ] 和 } 的数量差异，如果 } 多于预期顶级闭合需要，替换多余的 }
        // 更精准的方式：使用计数器模拟解析
        int braceDepth = 0;   // { } 深度
        int bracketDepth = 0; // [ ] 深度
        boolean inString = false;
        boolean escaped = false;

        // 第一遍扫描，找出所有不匹配的 }
        for (int i = 0; i < sb.length(); i++) {
            char c = sb.charAt(i);

            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\' && inString) {
                escaped = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }

            switch (c) {
                case '{':
                    braceDepth++;
                    break;
                case '}':
                    braceDepth--;
                    // 如果 braceDepth < 0，说明这个 } 是多余的，应该改为 ]
                    if (braceDepth < 0) {
                        // 检查当前 bracketDepth 是否 > 0（即我们在某个数组内部）
                        if (bracketDepth > 0) {
                            sb.setCharAt(i, ']');
                            bracketDepth--;
                            braceDepth = 0; // 修复后重置 braceDepth 为 0
                        } else {
                            // 无法修复：不在数组内部却多了 }
                            log.warn("[MemoryManager] JSON 修复失败：多余的 '}' 但不在数组内，位置: {}", i);
                            return null;
                        }
                    }
                    break;
                case '[':
                    bracketDepth++;
                    break;
                case ']':
                    bracketDepth--;
                    if (bracketDepth < 0) {
                        log.warn("[MemoryManager] JSON 修复失败：多余的 ']' ，位置: {}", i);
                        return null;
                    }
                    break;
            }
        }

        // 修复后验证：所有深度应该归零（或允许末尾有未闭合，Jackson 会处理）
        if (braceDepth == 0 && bracketDepth == 0) {
            log.info("[MemoryManager] JSON 括号修复成功。修复后: {}", sb.toString());
            return sb.toString();
        }

        // 如果还有未闭合的括号，尝试在末尾补全
        // 这种情况通常不需要，因为主要问题是多余的 }
        log.warn("[MemoryManager] JSON 修复后仍有未闭合括号: braceDepth={}, bracketDepth={}", braceDepth, bracketDepth);
        return null;
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