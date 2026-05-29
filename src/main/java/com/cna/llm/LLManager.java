package com.cna.llm;

import com.cna.Utils;
import com.cna.config.ConfigsManager;
import com.cna.db.MDManager;
import com.cna.agent.MemoryManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateExceptionHandler;
import lombok.extern.slf4j.Slf4j;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class LLManager {

    private static final Configuration cfg;
    private static final ObjectMapper jsonMapper = new ObjectMapper();

    private static final ExecutorService LLM_EXECUTOR = Executors.newFixedThreadPool(4);

    // 全局共享上下文缓存（所有任务共用）
    private static final ContextCacheEntry GLOBAL_CACHE = new ContextCacheEntry();

    /**
     * 全局上下文中 messages 数组的最大长度（元素个数）。
     * 达到此长度时自动全部清空。
     */
    public static int MAX_CONTEXT_CACHE_ROUNDS = ConfigsManager.MAX_CONTEXT_CACHE_ROUNDS;

    private static class ContextCacheEntry {
        ArrayNode messages;
        AtomicInteger roundCount = new AtomicInteger(0);
        boolean wasContextCleared = true; // 首次启动视为上下文已清空，需注入记忆
        final Object lock = new Object();
    }

    static {
        cfg = new Configuration(Configuration.VERSION_2_3_32);
        cfg.setDefaultEncoding("UTF-8");
        cfg.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
        cfg.setLogTemplateExceptions(false);
    }

    public static String render(String templateContent, Map<String, Object> data) {
        if (templateContent == null || templateContent.isBlank()) {
            return "【系统警告：模板内容为空】";
        }
        try {
            Template template = new Template("dynamic_template", new StringReader(templateContent), cfg);
            StringWriter out = new StringWriter();
            template.process(data, out);
            return out.toString();
        } catch (Exception e) {
            log.error("[LLManager] 渲染场景模板字符串失败", e);
            throw new RuntimeException("模板渲染失败: " + e.getMessage(), e);
        }
    }

    public static ExecutorService getExecutor() {
        return LLM_EXECUTOR;
    }

    /**
     * 同步封装：带超时的异步场景执行，方便快速替换原有的 executeScene 调用。
     * 超时或异常时返回一个内容为错误信息的 CallResult（toolCall=false）。
     *
     * @param userTemplate  用户提示词模板
     * @param dataModel     数据模型
     * @param llm           模型适配器
     * @param tools         工具定义

     * @return CallResult，保证非 null
     */
    public static CallResult executeScene(
            UUID taskId,
            String userTemplate,
            Map<String, Object> dataModel,
            LLMAdapter llm,
            ArrayNode tools) {

        // 使用全局共享上下文缓存机制执行
        CompletableFuture<CallResult> future = executeSceneAsyncWithCache(taskId, userTemplate, dataModel, llm, tools);
        try {
            return future.get(ConfigsManager.LLM_TIMEOUT_TIME, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.error("[LLManager] 场景执行超时 ({} ms)，已 cancel future", ConfigsManager.LLM_TIMEOUT_TIME);
            return errorResult("请求超时，请稍后重试或缩短上下文");
        } catch (InterruptedException | ExecutionException e) {
            future.cancel(true);
            log.error("[LLManager] 场景执行异常", e);
            return errorResult("系统错误: " + e.getMessage());
        }
    }

    private static CallResult errorResult(String message) {
        CallResult r = new CallResult();
        r.setContent(message);
        r.setToolCall(false);
        r.setToolCalls(null);
        return r;
    }

    // ========== 异步嵌入调用 ==========
    public static CompletableFuture<double[]> getEmbeddingAsync(String text, LLMAdapter emb) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return emb.getEmbedding(text);
            } catch (Exception e) {
                log.error("[LLManager Async] 嵌入调用失败", e);
                return new double[0];
            }
        }, LLM_EXECUTOR);
    }

    public static double[] getTextVector(String text, LLMAdapter emb) {
        CompletableFuture<double[]> future = getEmbeddingAsync(text, emb);
        try {
            return future.get(ConfigsManager.LLM_TIMEOUT_TIME, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.error("[LLManager] 嵌入调用超时 ({} ms)，已 cancel future", ConfigsManager.LLM_TIMEOUT_TIME);
        } catch (InterruptedException | ExecutionException e) {
            future.cancel(true);
            log.error("[LLManager] 嵌入调用异常", e);
        }
        return new double[0];
    }

    public static List<String> getDeepMemories(String text, LLMAdapter emb, int depth) {
        List<MemoryManager.DeepMemoryResult> results =
                MemoryManager.getInstance().getDeepMemoryResults(getTextVector(text, emb), depth);
        List<String> formatted = new java.util.ArrayList<>();
        for (MemoryManager.DeepMemoryResult r : results) {
            formatted.add(r.toPromptLine());
        }
        return formatted;
    }

    // ==================== 全局共享上下文方法 ====================

    private static com.cna.agent.LivingLoop livingLoop;

    public static void init(com.cna.agent.LivingLoop loop) {
        livingLoop = loop;
    }

    public static CompletableFuture<CallResult> executeSceneAsyncWithCache(
            UUID taskId,
            String userTemplate,
            Map<String, Object> dataModel,
            LLMAdapter llm,
            ArrayNode tools) {
        return CompletableFuture.supplyAsync(() -> {

            synchronized (GLOBAL_CACHE.lock) {
                // 初始化
                if (GLOBAL_CACHE.messages == null || GLOBAL_CACHE.messages.isEmpty()) {
                    GLOBAL_CACHE.messages = jsonMapper.createArrayNode();
                    ObjectNode sysMsg = jsonMapper.createObjectNode();
                    sysMsg.put("role", "system");
                    sysMsg.put("content", MDManager.read("prompts/CORE.md", ""));
                    GLOBAL_CACHE.messages.add(sysMsg);
                    GLOBAL_CACHE.roundCount.set(0);
                    GLOBAL_CACHE.wasContextCleared = true;
                    log.info("[LLManager] 🧠 初始化全局共享上下文");
                }

                // 在回合开始的干净边界做截断：此刻缓存必为完整的 assistant+tool 配对，
                // 截断不会留下孤儿 tool 消息（修复 mid-round 截断导致的 API 400）
                truncateGlobalCacheIfNeeded();

                // 仅在上下文被清空后的首轮注入长期记忆和当前想法
                // 正常运行期间由 GLOBAL_CACHE 承载任务内上下文
                boolean needInjection = GLOBAL_CACHE.wasContextCleared;
                if (needInjection) {
                    GLOBAL_CACHE.wasContextCleared = false;
                }

                if (!dataModel.containsKey("current_memories")) {
                    dataModel.put("current_memories",
                            needInjection
                                    ? MemoryManager.getInstance().getCurrentMemorys(ConfigsManager.CURRENT_MEMORIES_MAXSIZE)
                                    : java.util.Collections.emptyList());
                }
                dataModel.put("current_thoughts",
                        needInjection ? MDManager.read("thoughts.md", "") : "");
                dataModel.put("tools_guide",
                        needInjection ? MDManager.read("prompts/toolsGuide.md", "") : "");

                dataModel.put("now_time", Utils.getNowPrecise());
                dataModel.put("pending_tasks_summary", livingLoop != null ? livingLoop.buildTaskQueueSummary() : "");

                String userPrompt = render(userTemplate, dataModel);
                log.info("[LLManager 全局缓存] Prompt 渲染完毕, 长度: {} chars", userPrompt.length());

                ArrayNode workingMessages = GLOBAL_CACHE.messages.deepCopy();
                ObjectNode userMsgNode = jsonMapper.createObjectNode();
                userMsgNode.put("role", "user");
                userMsgNode.put("content", userPrompt);
                workingMessages.add(userMsgNode);

                ArrayNode toolsParam = (tools != null) ? tools : jsonMapper.createArrayNode();
                CallResult result = llm.generateResponseWithTools(workingMessages, toolsParam);

                if (result != null) {
                    // 将本轮 user 消息加入全局历史
                    GLOBAL_CACHE.messages.add(userMsgNode);

                    // 构造 assistant 消息，必须保留 tool_calls 信息以防后续 tool 消息报错
                    ObjectNode assistantMsg = jsonMapper.createObjectNode();
                    assistantMsg.put("role", "assistant");
                    assistantMsg.put("content", result.getContent() != null ? result.getContent() : "");
                    if (result.isToolCall() && result.getToolCalls() != null && result.getToolCalls().size() > 0) {
                        assistantMsg.set("tool_calls", result.getToolCalls());
                    }
                    GLOBAL_CACHE.messages.add(assistantMsg);

                    GLOBAL_CACHE.roundCount.incrementAndGet();
                    log.info("[LLManager] 全局第 {} 轮思考完成，上下文消息总数: {}",
                            GLOBAL_CACHE.roundCount.get(), GLOBAL_CACHE.messages.size());

                    // 截断改在下一回合开头进行（保证 assistant+tool 配对完整，避免孤儿 tool 消息）
                } else {
                    log.error("[LLManager] 全局上下文 LLM 返回 null，缓存不更新");
                    return errorResult("LLM 返回空结果");
                }
                return result;
            }
        }, LLM_EXECUTOR);
    }

    public static void feedToolResult(UUID taskId, String toolCallId, String toolName, String toolResult) {
        synchronized (GLOBAL_CACHE.lock) {
            if (GLOBAL_CACHE.messages == null || GLOBAL_CACHE.messages.isEmpty()) {
                log.warn("[LLManager] feedToolResult: 全局缓存为空，跳过压入");
                return;
            }
            // 清洗控制字符，防止污染 JSON（保留 \t \n \r 三个合法空白字符）
            String sanitized = toolResult != null ? toolResult.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]", "") : "";
            // 细化格式：让 LLM 仅从缓存就能看到完整的工具调用上下文（工具名 + 结果）,
            // 不再依赖模板层 turnsAddition 的冗余文本摘要
            String enriched = "[" + toolName + "]\n" + (sanitized.isEmpty() ? "(无返回内容)" : sanitized);
            ObjectNode toolMsg = jsonMapper.createObjectNode();
            toolMsg.put("role", "tool");
            toolMsg.put("tool_call_id", toolCallId);
            toolMsg.put("name", toolName);
            toolMsg.put("content", enriched);
            GLOBAL_CACHE.messages.add(toolMsg);
            log.info("[LLManager] feedToolResult -> 全局缓存压入工具结果: tool={}, callId={}, 消息总数: {}",
                    toolName, toolCallId, GLOBAL_CACHE.messages.size());

            // 不在此处截断：回合中途截断会删掉 assistant 却留下孤儿 tool 消息 → API 400。
            // 截断统一在下一回合 executeSceneAsyncWithCache 开头进行。
        }
    }

    private static void truncateGlobalCacheIfNeeded() {
        int size = GLOBAL_CACHE.messages.size();
        if (size > MAX_CONTEXT_CACHE_ROUNDS) {
            double ratio = ConfigsManager.CONTEXT_RETENTION_RATIO;

            ArrayNode newMessages = jsonMapper.createArrayNode();
            // 始终保留 system 消息
            newMessages.add(GLOBAL_CACHE.messages.get(0));

            if (ratio > 0) {
                // ratio 控制摘要的总长度上限。ratio=0.3 → 3000字, ratio=1.0 → 10000字
                int maxSummaryChars = (int) (ratio * 10000);
                StringBuilder sb = new StringBuilder(Math.min(maxSummaryChars, 1024));
                sb.append("[上下文回顾] 以下是你更早的内部活动记录（已压缩）：\n");
                for (int i = 1; i < size && sb.length() < maxSummaryChars; i++) {
                    JsonNode msg = GLOBAL_CACHE.messages.get(i);
                    String role = msg.path("role").asText("");
                    if (!"user".equals(role) && !"assistant".equals(role)) continue;
                    String content = msg.path("content").asText("");
                    if (content.isBlank()) continue;
                    String shortContent = content.length() > 150
                            ? content.substring(0, 150) + "…"
                            : content;
                    sb.append('[').append(role).append("] ").append(shortContent).append('\n');
                }

                if (sb.length() > 60) {
                    ObjectNode ctxMsg = jsonMapper.createObjectNode();
                    ctxMsg.put("role", "user");
                    ctxMsg.put("content", sb.toString());
                    newMessages.add(ctxMsg);
                }
            }
            // ratio == 0 → 仅保留 system，不注入摘要

            GLOBAL_CACHE.messages = newMessages;
            GLOBAL_CACHE.roundCount.set(0);
            GLOBAL_CACHE.wasContextCleared = true;
            log.info("[LLManager] 全局缓存消息数 {} 超过上限 {}，已截断(ratio={}) → 新消息数 {}",
                    size, MAX_CONTEXT_CACHE_ROUNDS, String.format("%.0f%%", ratio * 100), newMessages.size());
        }
    }

    public static void clearCache() {
        synchronized (GLOBAL_CACHE.lock) {
            GLOBAL_CACHE.messages = null;
            GLOBAL_CACHE.roundCount.set(0);
            log.info("[LLManager] 🗑️ 全局共享上下文已被主动清空");
        }
    }
}
