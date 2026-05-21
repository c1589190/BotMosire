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

    /**
     * 【核心修改】：直接接收 System 和 User 的提示词字符串 (完美解耦)
     * * @param systemPrompt 系统提示词 (例如从 CORE.md 读出的纯文本)
     * @param userTemplate 用户提示词模板 (包含 ${...} 占位符的纯文本)
     * @param dataModel    需要注入的参数变量
     * @param llm          使用的模型适配器
     * @param tools        大模型可用的工具箱
     */

    /*
    public static CallResult executeScene(
            String userTemplate,
            Map<String, Object> dataModel,
            LLMAdapter llm,
            ArrayNode tools) {

        Map<String, Object> data = dataModel;
        // 注入一些必要前置参数
        if(!data.containsKey("current_memories")) {
            data.put("current_memories", MemoryManager.getInstance().getCurrentMemorys(ConfigsManager.CURRENT_MEMORIES_MAXSIZE));
        }
        data.put("now_time", Utils.getNowFormatted());
        data.put("current_thoughts", MDManager.read("thoughts.md", ""));
        data.put("tools_guide", MDManager.read("prompts/toolsGuide.md",""));

        // 在内存中渲染出完美的 Prompt
        String userPrompt = render(userTemplate, dataModel);
        log.debug("[LLMManager] Prompt [\n{}\n] 渲染完毕，准备提交大模型.", userPrompt);

        // 呼叫大模型并直接返回结果
        if (tools == null) {
            CallResult result = new CallResult();
            result.setToolCall(false);
            // 此时 systemPrompt 也是由 Handler 直接从外部传入的
            result.setContent(llm.generateStreamResponse(userPrompt, MDManager.read("prompts/CORE.md"), chunk -> {}));
            result.setToolCalls(null);
            return result;
        }
        return llm.generateResponseWithTools(userPrompt, MDManager.read("prompts/CORE.md"), tools);
    }

     */

    // ---------- 新增：异步执行，返回 Future ----------
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
        dataModel.put("now_time", Utils.getNowFormatted());
        dataModel.put("current_thoughts", MDManager.read("thoughts.md", ""));
        dataModel.put("tools_guide", MDManager.read("prompts/toolsGuide.md", ""));

        String userPrompt = render(userTemplate, dataModel);
        log.debug("[LLManager Async] Prompt 渲染完毕，长度: {} chars", userPrompt.length());

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
}