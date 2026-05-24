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

    // LLM 调用专用线程池，完全与 Jetty / LivingLoop 线程隔离
    private static final ExecutorService LLM_EXECUTOR = Executors.newFixedThreadPool(4);

    // ==================== 全局共享上下文缓存（所有任务共用） ====================
    private static final ContextCacheEntry GLOBAL_CACHE = new ContextCacheEntry();

    /**
     * 全局上下文中 messages 数组的最大长度（元素个数）。
     * 达到此长度时自动截断：保留 system 消息（第一条）及前 50% 的其他消息。
     */
    public static int MAX_CONTEXT_CACHE_ROUNDS = ConfigsManager.MAX_CONTEXT_CACHE_ROUNDS;

    private static class ContextCacheEntry {
        /** 累积的完整 messages 数组（含 system / user / assistant / tool 等所有消息） */
        ArrayNode messages;
        /** 当前已累积的轮数（仅用于统计，不再用于重置判断） */
        AtomicInteger roundCount = new AtomicInteger(0);
        /** 全局缓存锁，所有上下文修改操作必须持有此锁 */
        final Object lock = new Object();
    }

    // 静态代码块：系统启动时自动初始化 FreeMarker
    static {
        cfg = new Configuration(Configuration.VERSION_2_3_32);
        cfg.setDefaultEncoding("UTF-8");
        cfg.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
        cfg.setLogTemplateExceptions(false);
    }

    /**
     * 基础功能：直接渲染传入的模板字符串，而不是从文件读
     */
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
     * 同步封装：带超时的异步场景执行。
     * taskId 保留以兼容旧接口，内部不再使用。
     */
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

    // ========== 异步嵌入调用 (无状态，无需修改) ==========

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

    /**
     * 执行场景，基于全局共享上下文。
     * taskId 保留以兼容旧接口，实际不再区分任务。
     */
    public static CompletableFuture<CallResult> executeSceneAsyncWithCache(
            UUID taskId,
            String userTemplate,
            Map<String, Object> dataModel,
            LLMAdapter llm,
            ArrayNode tools) {
        return CompletableFuture.supplyAsync(() -> {

            // 整个上下文相关操作在全局锁内串行执行，保证顺序与一致性
            synchronized (GLOBAL_CACHE.lock) {

                // 1. 初始化全局缓存（仅首次）
                if (GLOBAL_CACHE.messages == null || GLOBAL_CACHE.messages.isEmpty()) {
                    GLOBAL_CACHE.messages = jsonMapper.createArrayNode();
                    ObjectNode sysMsg = jsonMapper.createObjectNode();
                    sysMsg.put("role", "system");
                    sysMsg.put("content", MDManager.read("prompts/CORE.md", ""));
                    GLOBAL_CACHE.messages.add(sysMsg);
                    GLOBAL_CACHE.roundCount.set(0);
                    log.info("[LLManager] 🧠 初始化全局共享上下文");
                }

                // 2. 准备数据模型（注入记忆等）
                if (!dataModel.containsKey("current_memories")) {
                    dataModel.put("current_memories",
                            MemoryManager.getInstance().getCurrentMemorys(ConfigsManager.CURRENT_MEMORIES_MAXSIZE));
                }
                dataModel.put("tools_guide", MDManager.read("prompts/toolsGuide.md", ""));
                dataModel.put("now_time", Utils.getNowPrecise());
                dataModel.put("current_thoughts", MDManager.read("thoughts.md", ""));

                // 3. 渲染用户 prompt
                String userPrompt = render(userTemplate, dataModel);
                log.info("[LLManager 全局缓存] Prompt 渲染完毕, 长度: {} chars", userPrompt.length());

                // 4. 基于当前全局上下文构造本次 LLM 输入（深拷贝）
                ArrayNode workingMessages = GLOBAL_CACHE.messages.deepCopy();
                ObjectNode userMsgNode = jsonMapper.createObjectNode();
                userMsgNode.put("role", "user");
                userMsgNode.put("content", userPrompt);
                workingMessages.add(userMsgNode);

                ArrayNode toolsParam = (tools != null) ? tools : jsonMapper.createArrayNode();
                CallResult result = llm.generateResponseWithTools(workingMessages, toolsParam);

                // 5. 更新全局缓存
                if (result != null) {
                    // 将本轮 user 消息追加到全局历史
                    GLOBAL_CACHE.messages.add(userMsgNode);

                    // 构造 assistant 消息并追加
                    ObjectNode assistantMsg = jsonMapper.createObjectNode();
                    assistantMsg.put("role", "assistant");
                    assistantMsg.put("content", result.getContent() != null ? result.getContent() : "");
                    GLOBAL_CACHE.messages.add(assistantMsg);

                    GLOBAL_CACHE.roundCount.incrementAndGet();
                    log.info("[LLManager] 全局第 {} 轮思考完成，上下文消息总数: {}",
                            GLOBAL_CACHE.roundCount.get(), GLOBAL_CACHE.messages.size());

                    // 6. 检查是否超过消息上限，截断
                    truncateGlobalCacheIfNeeded();
                } else {
                    log.error("[LLManager] 全局上下文 LLM 返回 null，缓存不更新");
                    return errorResult("LLM 返回空结果");
                }

                return result;
            }
        }, LLM_EXECUTOR);
    }

    /**
     * 向全局上下文缓存中追加一条 tool 角色消息，并在必要时截断。
     * taskId 保留以兼容旧接口，实际不再区分任务。
     */
    public static void feedToolResult(UUID taskId, String toolCallId, String toolName, String toolResult) {
        synchronized (GLOBAL_CACHE.lock) {
            if (GLOBAL_CACHE.messages == null || GLOBAL_CACHE.messages.isEmpty()) {
                log.warn("[LLManager] feedToolResult: 全局缓存为空，跳过压入");
                return;
            }
            ObjectNode toolMsg = jsonMapper.createObjectNode();
            toolMsg.put("role", "tool");
            toolMsg.put("tool_call_id", toolCallId);
            toolMsg.put("name", toolName);
            toolMsg.put("content", toolResult);
            GLOBAL_CACHE.messages.add(toolMsg);
            log.info("[LLManager] feedToolResult -> 全局缓存压入工具结果: tool={}, callId={}, 消息总数: {}",
                    toolName, toolCallId, GLOBAL_CACHE.messages.size());

            truncateGlobalCacheIfNeeded();
        }
    }

    /**
     * 截断全局缓存：当 messages 长度超过 MAX_CONTEXT_CACHE_ROUNDS 时，
     * 保留 system 消息（索引0），然后保留剩余消息的前一半，删除后一半。
     * 必须在 GLOBAL_CACHE.lock 内调用。
     */
    private static void truncateGlobalCacheIfNeeded() {
        int size = GLOBAL_CACHE.messages.size();
        if (size > MAX_CONTEXT_CACHE_ROUNDS) {
            // 保留 system（第一条），剩余消息保留前一半
            int keepRemaining = (size - 1) / 2;  // 整数除法向下取整
            int newSize = 1 + keepRemaining;
            ArrayNode truncated = jsonMapper.createArrayNode();
            truncated.add(GLOBAL_CACHE.messages.get(0)); // system 消息
            for (int i = 1; i <= keepRemaining; i++) {
                truncated.add(GLOBAL_CACHE.messages.get(i));
            }
            int removed = size - newSize;
            GLOBAL_CACHE.messages = truncated;
            log.info("[LLManager] 全局缓存消息数 {} 超过上限 {}，截断保留前 {} 条，删除后 {} 条",
                    size, MAX_CONTEXT_CACHE_ROUNDS, newSize, removed);
        }
    }

    /**
     * 清除全局缓存（供外部主动重置时使用，如系统重置）。
     * taskId 参数保留以兼容旧接口，但不再按任务清理。
     */
    public static void clearTaskCache(UUID taskId) {
        synchronized (GLOBAL_CACHE.lock) {
            GLOBAL_CACHE.messages = null;
            GLOBAL_CACHE.roundCount.set(0);
            log.info("[LLManager] 🗑️ 全局共享上下文已被主动清空");
        }
    }
}