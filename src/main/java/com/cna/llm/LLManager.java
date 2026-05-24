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

    // ==================== 多轮对话上下文缓存 (基于任务 UUID 隔离) ====================
    // 使用 ConcurrentHashMap，以任务的 UUID 为键，实现任务间的记忆完全隔离
    private static final Map<UUID, ContextCacheEntry> taskContextCache = new ConcurrentHashMap<>();

    /**
     * 上下文缓存在多少轮后自动清空重置，默认 1024 轮
     */
    public static int MAX_CONTEXT_CACHE_ROUNDS = 1024;

    private static class ContextCacheEntry {
        /** 累积的完整 messages 数组（含 system / user / assistant / tool 等所有消息） */
        ArrayNode messages;
        /** 当前已累积的轮数 */
        AtomicInteger roundCount = new AtomicInteger(0);
        /** 为每个任务分配一把独立的锁，取代原本的全局大锁，实现完全并发处理 */
        final Object lock = new Object();
    }

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
            throw new RuntimeException("模板渲染失败: " + e.getMessage(), e);
        }
    }

    public static ExecutorService getExecutor() {
        return LLM_EXECUTOR;
    }

    /**
     * 同步封装：带超时的异步场景执行，方便快速替换原有的 executeScene 调用。
     * 超时或异常时返回一个内容为错误信息的 CallResult（toolCall=false）。
     * 【核心修改】：增加 UUID taskId 参数，以精确匹配任务上下文。
     *
     * @param taskId        任务的唯一标识符
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

        try {
            return executeSceneAsyncWithCache(taskId, userTemplate, dataModel, llm, tools)
                    .get(ConfigsManager.LLM_TIMEOUT_TIME, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.error("[LLManager] 任务 {} 场景执行超时 ({} {})", taskId, ConfigsManager.LLM_TIMEOUT_TIME, TimeUnit.MILLISECONDS);
            return errorResult("请求超时，请稍后重试或缩短上下文");
        } catch (InterruptedException | ExecutionException e) {
            log.error("[LLManager] 任务 {} 场景执行异常", taskId, e);
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

    // ========== 异步嵌入调用 (不涉及多轮对话状态，无需修改) ==========

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

    // ==================== 多轮上下文缓存方法 ====================

    /**
     * 【核心修改】：接收 taskId，从 ConcurrentHashMap 动态分配或获取独立记忆空间。
     */
    public static CompletableFuture<CallResult> executeSceneAsyncWithCache(
            UUID taskId,
            String userTemplate,
            Map<String, Object> dataModel,
            LLMAdapter llm,
            ArrayNode tools) {
        return CompletableFuture.supplyAsync(() -> {

            // 1. 获取该任务专属的缓存条目，如果没有则自动创建一个新的
            ContextCacheEntry cache = taskContextCache.computeIfAbsent(taskId, k -> {
                log.info("[LLManager] 🧠 为新任务 [{}] 开辟了全新的独立思维空间", taskId);
                return new ContextCacheEntry();
            });

            // 2. ---- 同步块：仅锁定当前任务的缓存，不影响其他线程的任务 ----
            synchronized (cache.lock) {
                if (cache.messages == null || cache.messages.isEmpty() || cache.roundCount.get() > MAX_CONTEXT_CACHE_ROUNDS) {

                    // 在第一轮或超轮数时重置该任务的缓存
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
                }
            }

            // 3. ---- 无锁区：渲染 prompt（完全不涉及共享状态，支持极高并发） ----
            dataModel.put("tools_guide", MDManager.read("prompts/toolsGuide.md", ""));
            dataModel.put("now_time", Utils.getNowPrecise());
            dataModel.put("current_thoughts", MDManager.read("thoughts.md", ""));
            String userPrompt = render(userTemplate, dataModel);
            log.info("[LLManager 缓存] 任务 {} Prompt 渲染完毕, 长度: {} chars", taskId, userPrompt.length());

            // 4. ---- 同步块：读取该任务缓存做深拷贝 ----
            final ArrayNode workingMessages;
            synchronized (cache.lock) {
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

            // 5. ---- 仅当 LLM 正常返回时才更新该任务的专属缓存 ----
            if (result != null) {
                synchronized (cache.lock) {
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
                    log.info("[LLManager] 任务 {} 第 {} 轮思考完成，目前上下文深度: {}", taskId, cache.roundCount.get(), cache.messages.size());
                }
            } else {
                log.error("[LLManager] 任务 {} LLM 返回 null，跳过该任务的缓存更新", taskId);
                return errorResult("LLM 返回空结果");
            }

            return result;
        }, LLM_EXECUTOR);
    }

    /**
     * 【核心修改】：精准清除指定任务的全局上下文缓存。
     * 例如在 FinishTask 调用时，或任务被丢弃时重置该任务对话，防止内存泄漏。
     */
    public static void clearTaskCache(UUID taskId) {
        if (taskId != null) {
            ContextCacheEntry removed = taskContextCache.remove(taskId);
            if (removed != null) {
                log.info("[LLManager] 🗑️ 任务 {} 的独立上下文缓存已彻底销毁释放", taskId);
            }
        }
    }

    /**
     * 向指定任务的上下文缓存中追加一条 tool 角色消息。
     * 在 LivingLoop 执行完工具后调用，使 LLM 能在下一轮看到工具执行结果，
     * 遵循 OpenAI tool calling 标准协议: system → user → assistant(tool_calls) → tool → tool → ...
     *
     * @param taskId      任务的唯一标识符
     * @param toolCallId  LLM 返回的 tool call id
     * @param toolName    工具名称
     * @param toolResult  工具执行结果字符串
     */
    public static void feedToolResult(UUID taskId, String toolCallId, String toolName, String toolResult) {
        if (taskId == null) return;

        ContextCacheEntry cache = taskContextCache.get(taskId);
        if (cache == null) {
            log.warn("[LLManager] ⚠️ feedToolResult 被调用但未找到任务 {} 的缓存，可能是任务已被销毁", taskId);
            return;
        }

        synchronized (cache.lock) {
            if (cache.messages == null || cache.messages.isEmpty()) {
                log.warn("[LLManager] feedToolResult: 任务 {} 的 messages 为空，跳过压入", taskId);
                return;
            }
            ObjectNode toolMsg = jsonMapper.createObjectNode();
            toolMsg.put("role", "tool");
            toolMsg.put("tool_call_id", toolCallId);
            toolMsg.put("name", toolName);
            toolMsg.put("content", toolResult);
            cache.messages.add(toolMsg);
            log.info("[LLManager] feedToolResult -> 任务 {} 压入动作结果: tool={}, callId={}, 消息总数: {}",
                    taskId, toolName, toolCallId, cache.messages.size());
        }
    }

}