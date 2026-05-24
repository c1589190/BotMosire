package com.cna.llm;

import com.cna.Utils;
import com.cna.config.ConfigsManager;
import com.cna.db.MDManager;
import com.cna.agent.MemoryManager;
import com.fasterxml.jackson.databind.node.ArrayNode;
import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateExceptionHandler;
import lombok.extern.slf4j.Slf4j;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

@Slf4j
public class LLManager {

    private static final Configuration cfg;

    // LLM 调用专用线程池，完全与 Jetty / LivingLoop 线程隔离
    private static final ExecutorService LLM_EXECUTOR = Executors.newFixedThreadPool(4);

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
    public static CompletableFuture<CallResult> executeSceneAsync(
            String userTemplate,
            Map<String, Object> dataModel,
            LLMAdapter llm,
            ArrayNode tools) {

        // 补全数据（渲染前的轻量操作在主调线程完成没问题）
        // 全用 containsKey 检查，允许 Handler 在 prepareBaseData 时覆盖这些值
        if (!dataModel.containsKey("current_memories")) {
            dataModel.put("current_memories",
                    MemoryManager.getInstance().getCurrentMemorys(ConfigsManager.CURRENT_MEMORIES_MAXSIZE));
        }
        if (!dataModel.containsKey("now_time")) {
            dataModel.put("now_time", Utils.getNowPrecise());
        }
        if (!dataModel.containsKey("current_thoughts")) {
            dataModel.put("current_thoughts", MDManager.read("thoughts.md", ""));
        }
        if (!dataModel.containsKey("tools_guide")) {
            dataModel.put("tools_guide", MDManager.read("prompts/toolsGuide.md", ""));
        }

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
            return llm.generateResponseWithTools(userPrompt, MDManager.read("prompts/CORE.md"), tools);
        }, LLM_EXECUTOR);
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
            String userTemplate,
            Map<String, Object> dataModel,
            LLMAdapter llm,
            ArrayNode tools) {

        // 提前持有 future 引用，超时时可以 cancel 释放线程槽
        // 注意：cancel(true) 只发出中断信号，对 OkHttp blocking call 不会立刻断开 socket，
        // 必须配合 LLMConfig.readTimeoutSec 设置合理值，避免线程槽被独占到 socket 超时为止。
        CompletableFuture<CallResult> future = executeSceneAsync(userTemplate, dataModel, llm, tools);
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
        return MemoryManager.getInstance().getDeepMemorys(getTextVector(text, emb), depth);
    }
}