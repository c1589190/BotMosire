package com.cna.agent;

import com.cna.Utils;
import com.cna.config.ConfigsManager;
import com.cna.config.ScenePromptsManager;
import com.cna.db.FeelingDimensionManager;
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
        inputCurrentMemorys(memories, List.of());
    }

    public void inputCurrentMemorys(List<String> memories, List<String> sources) {
        FeelingDimensionManager fdm = FeelingDimensionManager.getInstance();

        // 1. 瞬间把记忆落盘，绝不卡顿
        for (String mem : memories) {
            db.insertCurrentMemory(appendFeelingTags(mem, fdm), sources);
        }

        // 2. 检查是否溢出水位线
        int currentSize = db.getCurrentMemoryCount();
        if (currentSize > (EMB_MEMORY_SIZE + CURRENT_MEMORYS_MAXSIZE)) {
            // 3. 【核心异步发射】：试图获取潜意识锁
            if (isConsolidating.compareAndSet(false, true)) {
                log.info("[MemoryManager] 记忆水位超标 ({}), 唤醒潜意识，在后台开始折叠...", currentSize);
                subconsciousExecutor.submit(() -> {
                    try {
                        consolidateMemory();
                    } catch (Exception e) {
                        log.error("[MemoryManager] 潜意识记忆折叠发生异常", e);
                    } finally {
                        isConsolidating.set(false);
                        log.info("[MemoryManager] 潜意识折叠完毕，锁已释放。");
                    }
                });
            } else {
                log.debug("[MemoryManager] 潜意识正在忙碌，跳过本次折叠触发。");
            }
        }
    }

    public void inputCurrentMemory(String memory){
        List<String> a = new LinkedList<>();
        a.add(memory);
        this.inputCurrentMemorys(a);
    }

    public void inputCurrentMemory(String memory, List<String> sources){
        List<String> a = new LinkedList<>();
        a.add(memory);
        this.inputCurrentMemorys(a, sources);
    }

    /**
     * 为单条记忆追加感觉维度标签和总好坏判断。
     * 若感觉维度系统不可用，返回原始记忆不做修改。
     */
    private String appendFeelingTags(String memory, FeelingDimensionManager fdm) {
        if (fdm == null) return memory;

        try {
            List<FeelingDimensionManager.DimensionScore> topDimensions =
                    fdm.getTargetDimensions(memory, true, ConfigsManager.FEELING_DIMENSION_COUNT);

            if (topDimensions.isEmpty()) return memory;

            StringBuilder sb = new StringBuilder(memory);
            sb.append(" [感觉: ");

            double totalPolarity = 0.0;
            for (int i = 0; i < topDimensions.size(); i++) {
                FeelingDimensionManager.DimensionScore ds = topDimensions.get(i);
                if (i > 0) sb.append(" ");
                String polaritySign = ds.hitWeight >= 0 ? "+" : "-";
                sb.append(String.format("\"%s\"(%s,%.2f)", ds.concept, polaritySign, ds.hitWeight));
                // 加权总和 = hitWeight极性 * InterestScore(含新鲜度权重)
                totalPolarity += ds.hitWeight * ds.InterestScore;
            }
            sb.append("]");

            if (totalPolarity > 0) {
                sb.append(" [总:好]");
            } else if (totalPolarity < 0) {
                sb.append(" [总:坏]");
            } else {
                sb.append(" [总:中性]");
            }

            return sb.toString();
        } catch (Exception e) {
            log.warn("[MemoryManager] 感觉维度标记失败，回退为原始记忆", e);
            return memory;
        }
    }

    private void consolidateMemory() {
        List<MemoryDB.CurrentMemoryEntry> oldMemories = db.popOldestCurrentMemories(EMB_MEMORY_SIZE);
        if (oldMemories.isEmpty()) return;

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < oldMemories.size(); i++) {
            MemoryDB.CurrentMemoryEntry entry = oldMemories.get(i);
            sb.append("[").append(i).append("] ").append(entry.content);
            if (entry.sources != null && !entry.sources.isEmpty()) {
                sb.append(" [来源: ").append(String.join(", ", entry.sources)).append("]");
            }
            sb.append("\n");
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
                        String point;
                        List<String> pointSources = new ArrayList<>();

                        if (pointNode.isObject()) {
                            // 新格式: { content: "...", sources: [...] }
                            point = pointNode.path("content").asText();
                            JsonNode srcNode = pointNode.path("sources");
                            if (srcNode.isArray()) {
                                for (JsonNode s : srcNode) {
                                    String src = s.asText();
                                    if (src != null && !src.isBlank()) pointSources.add(src);
                                }
                            }
                        } else {
                            // 兼容旧格式: 纯字符串
                            point = pointNode.asText();
                        }

                        if (point.isBlank()) continue;

                        // 向量化并入库
                        double[] vector = LLManager.getTextVector(point, embLLM);
                        if (vector != null && vector.length > 0) {
                            db.insertDeepMemory(vector, point, pointSources);
                            log.debug("[MemoryManager] 深度记忆存入：{} (来源: {})", point, pointSources);
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
        return db.getLatestCurrentMemoryContents(n);
    }

    /** 带 ID 和来源的深度记忆条目，供 Prompt 注入和来源追溯使用 */
    public record DeepMemoryResult(int id, String content, java.util.List<String> sources) {
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("[DM-").append(id).append("] ").append(content);
            if (sources != null && !sources.isEmpty()) {
                sb.append(" (来源: ").append(String.join(", ", sources)).append(")");
            }
            return sb.toString();
        }

        /** 仅含 ID 和内容，不含来源标注（用于 Prompt 注入，LLM 通过 DM-N 引用） */
        public String toPromptLine() {
            return "[DM-" + id + "] " + content;
        }
    }

    /**
     * 纯语义向量召回（无来源过滤），返回带 ID 的结果。
     */
    public List<DeepMemoryResult> getDeepMemoryResults(double[] queryVector, int n) {
        return getDeepMemoryResults(queryVector, n, null);
    }

    /**
     * 来源感知的向量召回：匹配 sourceFilter 的条目获得 boost (×1.5)。
     * @param queryVector 查询向量
     * @param n Top-N
     * @param sourceFilter 来源过滤列表，为 null 或空则不做来源 boost
     */
    public List<DeepMemoryResult> getDeepMemoryResults(double[] queryVector, int n, List<String> sourceFilter) {
        List<MemoryDB.DeepMemoryEntry> allMemories = db.getAllDeepMemories();

        log.info("[MemoryManager] 深度记忆库原始数据量: {}, 准备召回 Top: {}, sourceFilter: {}",
                allMemories.size(), n, sourceFilter);

        if (allMemories.isEmpty()) {
            log.warn("[MemoryManager] 警告：深度记忆库目前是空的。");
            return new ArrayList<>();
        }

        boolean hasFilter = sourceFilter != null && !sourceFilter.isEmpty();

        PriorityQueue<SimilarityRecord> pq = new PriorityQueue<>(
                (a, b) -> Double.compare(b.similarity, a.similarity)
        );

        for (MemoryDB.DeepMemoryEntry entry : allMemories) {
            if (entry.vector == null || entry.vector.length != queryVector.length) {
                log.error("[MemoryManager] 维度不匹配！库内维度: {}, 查询维度: {}",
                        (entry.vector != null ? entry.vector.length : "null"), queryVector.length);
                continue;
            }

            double sim = cosineSimilarity(queryVector, entry.vector);

            // 来源 boost: 匹配则 ×1.5
            if (hasFilter && entry.sources != null) {
                for (String filterSrc : sourceFilter) {
                    if (entry.sources.contains(filterSrc)) {
                        sim *= 1.5;
                        break; // 任一来源匹配即 boost，不重复乘
                    }
                }
            }

            pq.offer(new SimilarityRecord(entry.id, entry.content, entry.sources, sim));
        }

        List<DeepMemoryResult> result = new ArrayList<>();
        while (!pq.isEmpty() && result.size() < n) {
            SimilarityRecord record = pq.poll();
            if (record.similarity > 0.1) {
                result.add(new DeepMemoryResult(record.id, record.content, record.sources));
            }
        }

        log.info("[MemoryManager] 最终召回记忆数量: {}", result.size());
        return result;
    }

    /**
     * 兼容旧接口：仅返回内容字符串列表（无 ID、无来源）。
     */
    @Deprecated
    public List<String> getDeepMemorys(double[] queryVector, int n) {
        List<DeepMemoryResult> results = getDeepMemoryResults(queryVector, n, null);
        List<String> contents = new ArrayList<>();
        for (DeepMemoryResult r : results) {
            contents.add(r.content);
        }
        return contents;
    }

    /**
     * 带来源过滤的旧接口兼容。
     */
    public List<String> getDeepMemorys(double[] queryVector, int n, List<String> sourceFilter) {
        List<DeepMemoryResult> results = getDeepMemoryResults(queryVector, n, sourceFilter);
        List<String> contents = new ArrayList<>();
        for (DeepMemoryResult r : results) {
            contents.add(r.content);
        }
        return contents;
    }

    // ==========================================
    // 功能 5: 文本直搜深度记忆 (桥接方法供 Tool 调用)
    // ==========================================
    public List<String> searchDeepMemoryByText(String queryText, int n) {
        return searchDeepMemoryByText(queryText, n, null);
    }

    public List<String> searchDeepMemoryByText(String queryText, int n, List<String> sourceFilter) {
        log.info("[MemoryManager] 正在将查询词向量化: {}", queryText);
        double[] queryVector = LLManager.getTextVector(queryText, embLLM);

        if (queryVector == null || queryVector.length == 0) {
            log.error("[MemoryManager] 查询词向量化失败！");
            return new ArrayList<>();
        }

        return getDeepMemorys(queryVector, n, sourceFilter);
    }

    /** 返回带 ID 的结果列表（供 LLManager 格式化注入 Prompt） */
    public List<DeepMemoryResult> searchDeepMemoryResultsByText(String queryText, int n, List<String> sourceFilter) {
        log.info("[MemoryManager] 正在将查询词向量化: {}", queryText);
        double[] queryVector = LLManager.getTextVector(queryText, embLLM);

        if (queryVector == null || queryVector.length == 0) {
            log.error("[MemoryManager] 查询词向量化失败！");
            return new ArrayList<>();
        }

        return getDeepMemoryResults(queryVector, n, sourceFilter);
    }

    /**
     * 将任务来源信息追加到指定深度记忆中（LLM 标记为"有用"的记忆）。
     */
    public void enrichDeepMemorySources(List<Integer> ids, List<String> taskSources) {
        if (ids == null || ids.isEmpty() || taskSources == null || taskSources.isEmpty()) return;
        for (int id : ids) {
            db.enrichDeepMemorySources(id, taskSources);
        }
        log.info("[MemoryManager] 已为 {} 条深度记忆追加来源 {}", ids.size(), taskSources);
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
        int id;
        String content;
        List<String> sources;
        double similarity;
        SimilarityRecord(int id, String content, List<String> sources, double similarity) {
            this.id = id;
            this.content = content;
            this.sources = sources;
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
        function.put("description", "将提炼后的多个独立记忆点保存到长期记忆库，每个记忆点需标注来源");

        // 定义 parameters 的 JSON Schema
        ObjectNode parameters = mapper.createObjectNode();
        parameters.put("type", "object");

        ObjectNode properties = mapper.createObjectNode();

        // points: array of objects { content: string, sources: string[] }
        ObjectNode points = mapper.createObjectNode();
        points.put("type", "array");
        points.put("description", "提炼出的独立记忆点列表，每项为对象");

        ObjectNode items = mapper.createObjectNode();
        items.put("type", "object");

        ObjectNode itemProps = mapper.createObjectNode();

        ObjectNode contentProp = mapper.createObjectNode();
        contentProp.put("type", "string");
        contentProp.put("description", "提炼出的独立记忆点文本");
        itemProps.set("content", contentProp);

        ObjectNode sourcesProp = mapper.createObjectNode();
        sourcesProp.put("type", "array");
        sourcesProp.put("description", "该记忆点的来源标识符列表（如 qqid:xxx, qq_group:xxx, webaddress_xxx, system:internal）。无法确定时填 [\"unknown\"]，不能为空数组");
        ObjectNode sourcesItems = mapper.createObjectNode();
        sourcesItems.put("type", "string");
        sourcesProp.set("items", sourcesItems);
        itemProps.set("sources", sourcesProp);

        items.set("properties", itemProps);

        ArrayNode itemRequired = mapper.createArrayNode();
        itemRequired.add("content");
        itemRequired.add("sources");
        items.set("required", itemRequired);

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