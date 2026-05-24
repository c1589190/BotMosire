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
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class LLManager {

    private static final Configuration cfg;
    private static final ObjectMapper jsonMapper = new ObjectMapper();

    // LLM 调用专用线程池，完全与 Jetty / LivingLoop 线程隔离
    private static final ExecutorService LLM_EXECUTOR = Executors.newFixedThreadPool(4);

    // ==================== 多轮对话上下文缓存 ====================
    // 按 cacheKey 隔离不同会话的上下文，N 轮后自动清空以节省 LLM 花费

    //private static final Map<String, ContextCacheEntry> contextCache = new ConcurrentHashMap<>();

    /**
     * 上下文缓存在多少轮后自动清空重置，默认 10 轮
     */
    public static int MAX_CONTEXT_CACHE_ROUNDS = 114;

    private static class ContextCacheEntry {
        /** 累积的完整 messages 数组（含 system / user / assistant / tool 等所有消息） */
        ArrayNode messages;
        /** 当前已累积的轮数 */
        AtomicInteger roundCount = new AtomicInteger(0);
    }
    private static ContextCacheEntry cache = new ContextCacheEntry();
    private static final Object CACHE_LOCK = new Object();

    // 静态代码块：系统启动时自动初始化 FreeMarker
    static {
        cfg = new Configuration(Configuration.VERSION_2_3_32);
        // 【核心修改】：因为现在直接传 String 渲染，所以不需要再挂载任何 TemplateLoader 物理路径了！
        cfg.setDefaultEncoding("UTF-8");
        cfg.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
        cfg.setLogTemplateExceptions(false);
    }

    /**
     * 基础功能：直接渲染传入的模板字符串，而不是从文件读
     * @param templateContent 包含 FreeMarker 语法的字符串
     * @param data 变量集合
     * @return 渲染后的文本
     */
    public static String render(String templateContent, Map<String, Object> data) {
        if (templateContent == null || templateContent.isBlank()) {
            return "【系统警告：模板内容为空】";
        }
        try {
            // 【核心修改】：将传入的 String 包装成 StringReader 交给 FreeMarker 在内存中动态渲染
            Template template = new Template("dynamic_template", new StringReader(templateContent), cfg);
            StringWriter out = new StringWriter();
            template.process(data, out);
            return out.toString();
        } catch (Exception e) {
            log.error("[LLManager] 渲染场景模板字符串失败", e);
            return "【系统警告：模板渲染出错，请检查语法】";
        }
    }

    public static ExecutorService getExecutor() {
        return LLM_EXECUTOR;
    }

    // ---------- 新增：异步执行，返回 Future ----------
    /*
    public static CompletableFuture<CallResult> executeSceneAsync(
            String userTemplate,
            Map<String, Object> dataModel,
            LLMAdapter llm,
            ArrayNode tools) {

        // 补全数据（渲染前的轻量操作在主调线程完成没问题）
        if (!dataModel.containsKey("current_memories")) {
            dataModel.put("current_memories",
                    MemoryManager.getInstance().getCurrentMemorys(ConfigsManager.CURRENT_MEMORIES_MAXSIZE));
        }
        dataModel.put("now_time", Utils.getNowPrecise());
        dataModel.put("current_thoughts", MDManager.read("thoughts.md", ""));
        dataModel.put("tools_guide", MDManager.read("prompts/toolsGuide.md", ""));

        String userPrompt = render(userTemplate, dataModel);
        log.info("[LLManager Async] Prompt 渲染完毕，长度: {} chars", userPrompt.length());
        log.trace("[LLManager Async] Prompt 全文: {}", userPrompt);

        return CompletableFuture.supplyAsync(() -> {
            if (tools == null) {
                CallResult result = new CallResult();
                result.setToolCall(false);
                result.setContent(llm.generateStreamResponse(
                        userPrompt,
                        MDManager.read("prompts/CORE.md"),
                        chunk -> {}));
                result.setToolCalls(null);
                return result;
            }
            return llm.generateResponseWithTools(userPrompt,  tools);
        }, LLM_EXECUTOR);
    }

     */

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
            String userTemplate,
            Map<String, Object> dataModel,
            LLMAdapter llm,
            ArrayNode tools) {

        try {
            return executeSceneAsyncWithCache(userTemplate, dataModel, llm, tools)
                    .get(ConfigsManager.LLM_TIMEOUT_TIME, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.error("[LLManager] 场景执行超时 ({} {})", ConfigsManager.LLM_TIMEOUT_TIME, TimeUnit.MILLISECONDS);
            return errorResult("请求超时，请稍后重试或缩短上下文");
        } catch (InterruptedException | ExecutionException e) {
            log.error("[LLManager] 场景执行异常", e);
            return errorResult("系统错误: " + e.getMessage());
        }
    }

    // 快速构造一个错误 CallResult 的辅助方法
    private static CallResult errorResult(String message) {
        CallResult r = new CallResult();
        r.setContent(message);
        r.setToolCall(false);
        r.setToolCalls(null);
        return r;
    }

    // ========== 异步嵌入调用 ==========

    /**
     * 异步获取文本的嵌入向量，不阻塞调用线程
     * @param text 要向量化的文本
     * @param emb  嵌入模型适配器
     * @return 包含 double[] 的 CompletableFuture，可以 get(timeout) 等待结果
     */
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

    /**
     * 同步封装：带超时的异步嵌入调用，方便旧代码快速替换
     * @param text   文本
     * @param emb    嵌入模型
     * @return 向量数组，超时或异常返回空数组
     */
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

    // ==================== 多轮上下文缓存方法 ====================

    /*
    private static ContextCacheEntry getOrCreateEntry(String cacheKey) {
        return contextCache.computeIfAbsent(cacheKey, k -> {
            ContextCacheEntry entry = new ContextCacheEntry();
            entry.messages = jsonMapper.createArrayNode();
            ObjectNode sysMsg = jsonMapper.createObjectNode();
            sysMsg.put("role", "system");
            sysMsg.put("content", MDManager.read("prompts/CORE.md", ""));
            entry.messages.add(sysMsg);
            log.info("[LLManager 缓存] 创建新上下文缓存: cacheKey={}", cacheKey);
            return entry;
        });
    }

     */

    public static CompletableFuture<CallResult> executeSceneAsyncWithCache(
            String userTemplate,
            Map<String, Object> dataModel,
            LLMAdapter llm,
            ArrayNode tools) {
        return CompletableFuture.supplyAsync(() -> {

            // ---- 同步块：检查并初始化/重置缓存 ----
            synchronized (CACHE_LOCK) {
                if (cache.messages == null || cache.messages.isEmpty() || cache.roundCount.get() > MAX_CONTEXT_CACHE_ROUNDS) {

                    // 在第一轮或超轮数时重置缓存
                    cache.messages = jsonMapper.createArrayNode();
                    ObjectNode sysMsg = jsonMapper.createObjectNode();
                    sysMsg.put("role", "system");
                    sysMsg.put("content", MDManager.read("prompts/CORE.md", ""));
                    cache.messages.add(sysMsg);
                    cache.roundCount.set(0);

                    if (!dataModel.containsKey("current_memories")) {
                        dataModel.put("current_memories",
                                MemoryManager.getInstance().getCurrentMemorys(ConfigsManager.CURRENT_MEMORIES_MAXSIZE));
                    }
                    dataModel.put("tools_guide", MDManager.read("prompts/toolsGuide.md", ""));
                }
            }

            // ---- 无锁区：渲染 prompt（不涉及共享状态） ----
            dataModel.put("now_time", Utils.getNowPrecise());
            dataModel.put("current_thoughts", MDManager.read("thoughts.md", ""));
            String userPrompt = render(userTemplate, dataModel);
            log.info("[LLManager 缓存] Prompt 渲染完毕, 长度: {} chars", userPrompt.length());

            // ---- 同步块：读取缓存做深拷贝 ----
            final ArrayNode workingMessages;
            synchronized (CACHE_LOCK) {
                workingMessages = cache.messages.deepCopy();
            }

            ObjectNode userMsgNode = jsonMapper.createObjectNode();
            userMsgNode.put("role", "user");
            userMsgNode.put("content", userPrompt);
            workingMessages.add(userMsgNode);

            // 若无 tools，使用空数组，保持统一调用路径
            ArrayNode toolsParam = (tools != null) ? tools : jsonMapper.createArrayNode();
            CallResult result;
            result = llm.generateResponseWithTools(workingMessages, toolsParam);

            // 仅当 LLM 正常返回时才更新缓存；null 时跳过避免缓存错误上下文
            if (result != null) {
                synchronized (CACHE_LOCK) {
                    if (result.getContextMessages() != null) {
                        // 返回的 contextMessages 已是包含本轮 assistant 的完整消息数组，直接替换缓存
                        cache.messages = result.getContextMessages();
                    } else {
                        ObjectNode assistantMsg = jsonMapper.createObjectNode();
                        assistantMsg.put("role", "assistant");
                        assistantMsg.put("content", result.getContent() != null ? result.getContent() : "");
                        cache.messages.add(userMsgNode);
                        cache.messages.add(assistantMsg);
                    }
                    cache.roundCount.incrementAndGet();
                    log.info("[LLManager] 第 {} 轮完成，消息数: {}", cache.roundCount.get(), cache.messages.size());
                }
            } else {
                log.error("[LLManager] LLM 返回 null，跳过缓存更新");
                return errorResult("LLM 返回空结果");
            }

            return result;
        }, LLM_EXECUTOR);
    }

    /**
     * 清除全局上下文缓存。例如在 FinishTask 调用时重置对话。
     */
    public static void clearCache() {
        synchronized (CACHE_LOCK) {
            cache.messages = null;
            cache.roundCount.set(0);
            log.info("[LLManager] 全局上下文缓存已清除");
        }
    }

    /**
     * 向全局上下文缓存中追加一条 tool 角色消息。
     * 在 LivingLoop 执行完工具后调用，使 LLM 能在下一轮看到工具执行结果，
     * 遵循 OpenAI tool calling 标准协议: system → user → assistant(tool_calls) → tool → tool → ...
     *
     * @param toolCallId  LLM 返回的 tool call id
     * @param toolName    工具名称
     * @param toolResult  工具执行结果字符串
     */
    public static void feedToolResult(String toolCallId, String toolName, String toolResult) {
        synchronized (CACHE_LOCK) {
            if (cache.messages == null || cache.messages.isEmpty()) {
                log.warn("[LLManager] feedToolResult 被调用但缓存为空，callId={}, tool={}", toolCallId, toolName);
                return;
            }
            ObjectNode toolMsg = jsonMapper.createObjectNode();
            toolMsg.put("role", "tool");
            toolMsg.put("tool_call_id", toolCallId);
            toolMsg.put("name", toolName);
            toolMsg.put("content", toolResult);
            cache.messages.add(toolMsg);
            log.info("[LLManager] feedToolResult: tool={}, callId={}, 消息总数: {}", toolName, toolCallId, cache.messages.size());
        }
    }

}
