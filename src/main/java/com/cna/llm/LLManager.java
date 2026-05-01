package com.cna.llm;

import com.cna.Utils;
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

    public static CallResult executeScene(
            String sceneName,
            Map<String, Object> dataModel,
            LLMAdapter llm,
            String SystemPromptPath,
            ArrayNode tools) {

        // 1. 调用上面的方法，将参数和模板融合成最终的 Prompt
        Map<String, Object> data = dataModel;
        //输入一些必要前置参数
        data.put("current_memories", new MemoryManager().getCurrentMemorys(ConfigsManager.CURRENT_MEMORIES_MAXSIZE + ConfigsManager.EMB_MEMORY_SIZE));
        data.put("now_time", Utils.getNowFormatted());

        // 这里渲染出了完美的 Prompt
        String userPrompt = render(sceneName, dataModel);
        log.info("[LLMManager] Prompt [\n{}\n] 渲染完毕，准备提交大模型.", userPrompt);

        // 2. 呼叫大模型并直接返回结果
        if(tools == null){
            CallResult result = new CallResult();
            result.setToolCall(false);
            // 【关键修复】：把 (String) dataModel.get("user") 替换成 userPrompt
            result.setContent(llm.generateStreamResponse(userPrompt, MDManager.read(SystemPromptPath), chunk -> {}));
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