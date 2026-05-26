package com.cna.llm;

import com.cna.Utils;
import com.cna.config.ConfigsManager;
import com.cna.db.MDManager;
import com.cna.agent.MemoryManager;
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
    public static int MAX_CONTEXT_CACHE_ROUNDS = 1024;

    private static class ContextCacheEntry {
        ArrayNode messages;
        AtomicInteger roundCount = new AtomicInteger(0);
        volatile boolean wasContextCleared = true; // 首次启动视为上下文已清空，需注入记忆
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

    public static CallResult executeScene(
            UUID taskId,
            String userTemplate,
            Map<String, Object> dataModel,
            LLMAdapter llm,
            ArrayNode tools) {

        try {
            return executeSceneAsyncWithCache(taskId, userTemplate, dataModel, llm, tools)
                    .get(ConfigsManager.LLM_TIMEOUT_TIME, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.error("[LLManager] 场景执行超时 ({} {})", ConfigsManager.LLM_TIMEOUT_TIME, TimeUnit.MILLISECONDS);
            return errorResult("请求超时，请稍后重试或缩短上下文");
        } catch (InterruptedException | ExecutionException e) {
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
        try {
            return getEmbeddingAsync(text, emb).get(ConfigsManager.LLM_TIMEOUT_TIME, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.error("[LLManager] 嵌入调用超时 ({} {})", ConfigsManager.LLM_TIMEOUT_TIME, TimeUnit.MILLISECONDS);
        } catch (InterruptedException | ExecutionException e) {
            log.error("[LLManager] 嵌入调用异常", e);
        }
        return new double[0];
    }

    public static List<String> getDeepMemories(String text, LLMAdapter emb, int depth) {
        return MemoryManager.getInstance().getDeepMemorys(getTextVector(text, emb), depth);
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
                    log.info("[LLManager] 🧠 初始化全局共享上下文");
                }

                // 仅在上下文被清空后的首轮注入 current_memories，帮助 LLM 回忆历史
                // 正常运行期间由 turnsAddition 承载任务内上下文
                if (!dataModel.containsKey("current_memories")) {
                    if (GLOBAL_CACHE.wasContextCleared) {
                        dataModel.put("current_memories",
                                MemoryManager.getInstance().getCurrentMemorys(ConfigsManager.CURRENT_MEMORIES_MAXSIZE));
                        GLOBAL_CACHE.wasContextCleared = false;
                    } else {
                        dataModel.put("current_memories", java.util.Collections.emptyList());
                    }
                }
                dataModel.put("tools_guide", MDManager.read("prompts/toolsGuide.md", ""));
                dataModel.put("now_time", Utils.getNowPrecise());
                dataModel.put("current_thoughts", MDManager.read("thoughts.md", ""));
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

                    // 截断检查
                    truncateGlobalCacheIfNeeded();
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
            ObjectNode toolMsg = jsonMapper.createObjectNode();
            toolMsg.put("role", "tool");
            toolMsg.put("tool_call_id", toolCallId);
            toolMsg.put("name", toolName);
            toolMsg.put("content", sanitized);
            GLOBAL_CACHE.messages.add(toolMsg);
            log.info("[LLManager] feedToolResult -> 全局缓存压入工具结果: tool={}, callId={}, 消息总数: {}",
                    toolName, toolCallId, GLOBAL_CACHE.messages.size());

            truncateGlobalCacheIfNeeded();
        }
    }

    private static void truncateGlobalCacheIfNeeded() {
        int size = GLOBAL_CACHE.messages.size();
        if (size > MAX_CONTEXT_CACHE_ROUNDS) {
            GLOBAL_CACHE.messages = jsonMapper.createArrayNode();
            GLOBAL_CACHE.roundCount.set(0);
            GLOBAL_CACHE.wasContextCleared = true;
            log.info("[LLManager] 全局缓存消息数 {} 超过上限 {}，已全部清空，标记需重新注入记忆", size, MAX_CONTEXT_CACHE_ROUNDS);
        }
    }

    public static void clearTaskCache(UUID taskId) {
  //      synchronized (GLOBAL_CACHE.lock) {
//            GLOBAL_CACHE.messages = null;
  //         GLOBAL_CACHE.roundCount.set(0);
    //        log.info("[LLManager] 🗑️ 全局共享上下文已被主动清空");
//        }
    }
}
