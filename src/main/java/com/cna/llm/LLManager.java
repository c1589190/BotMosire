package com.cna.llm;

import com.cna.config.ConfigsManager;
import com.cna.db.MDManager;
import com.cna.db.MemoryManager;
import com.fasterxml.jackson.databind.node.ArrayNode;
import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateExceptionHandler;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

@Slf4j
public class LLManager {

    private static final Configuration cfg;

    // 静态代码块：系统启动时自动初始化 FreeMarker
    static {
        cfg = new Configuration(Configuration.VERSION_2_3_32);
        try {
            cfg.setClassForTemplateLoading(LLManager.class, "/prompts");
            cfg.setDefaultEncoding("UTF-8");
            // 出错时直接抛出异常，不要在模板里乱填错误信息
            cfg.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
            cfg.setLogTemplateExceptions(false);
        } catch (Exception e) {
            log.error("[PromptManager] FreeMarker 初始化失败", e);
        }
    }

    /**
     * 基础功能：仅仅渲染模板并返回 String
     * * @param sceneName 场景名 (对应的文件名，如 "LivingLoop_CognitiveCycle_getInterest.md")
     * @param data 变量集合 (Map<String, Object>)
     * @return 渲染后的文本
     */
    public static String render(String sceneName, Map<String, Object> data) {
        try {
            Template template = cfg.getTemplate(sceneName + ".md");
            StringWriter out = new StringWriter();
            template.process(data, out);
            //log.debug("prompt:{\n"+out.toString()+"\n");
            return out.toString();
        } catch (Exception e) {
            log.error("[LLManager] 渲染场景模板失败: {}", sceneName, e);
            return "【系统警告：模板渲染出错】";
        }
    }

    /**
     * 【核心高阶功能】：一站式场景执行器
     * 传入场景 -> 渲染 Prompt -> 呼叫大模型 -> 直接返回分析结果
     *
     * @param sceneName 场景文件名
     * @param dataModel 传入的参数 (如 unknownInputs 列表)
     * @param llm       指定该场景使用的模型适配器 (小模型还是大模型)

     * @param tools     允许使用的工具 (传 null 则为纯文本对话)
     * @return 统一的 ToolCallResult
     */
    public static CallResult executeScene(
            String sceneName,
            Map<String, Object> dataModel,
            LLMAdapter llm,
            String SystemPromptPath,
            ArrayNode tools) {

        // 1. 调用上面的方法，将参数和模板融合成最终的 Prompt
        Map<String, Object> data = dataModel;
        data.put("current_memories", new MemoryManager().getCurrentMemorys(ConfigsManager.CURRENT_MEMORIES_MAXSIZE + ConfigsManager.EMB_MEMORY_SIZE));
        String userPrompt = render(sceneName, dataModel);
        //log.debug("[LLMManager] 场景 [{}] 渲染完毕，准备提交大模型...", sceneName);

        // 2. 呼叫大模型并直接返回结果
        // 如果 tools 为空或 null，generateResponseWithTools 内部应该自动按常规文本请求处理
        if(tools == null){
            CallResult result = new CallResult();
            result.setToolCall(false);
            result.setContent(llm.generateStreamResponse((String) dataModel.get("user"), MDManager.read(SystemPromptPath), chunk -> {}));
            result.setToolCalls(null);
            return result;
        }
        return llm.generateResponseWithTools(userPrompt, MDManager.read(SystemPromptPath), tools);
    }

    private static double[] getTextVector(String s, LLMAdapter emb){
        return emb.getEmbedding(s);
    }
    public static List<String> getDeepMemories(String text, LLMAdapter emb, int depth){
        return new MemoryManager().getDeepMemorys(getTextVector(text, emb), depth);
    }
}