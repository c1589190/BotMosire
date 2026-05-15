package com.cna.agent.AgentTasksHandlers;

import com.cna.agent.AgentTask.DefaultAgentTaskUnit;
import com.cna.agent.AgentTask.WebEventTask;
import com.cna.agent.LivingLoop;
import com.cna.config.ConfigsManager;
import com.cna.config.ScenePromptsManager;
import com.cna.llm.LLManager;
import com.fasterxml.jackson.databind.node.ArrayNode;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;

@Slf4j
public class WebEventTaskHandler implements DefaultAgentTaskHandler {

    @Override
    public Class<? extends DefaultAgentTaskUnit> getSupportedTaskClass() {
        return WebEventTask.class; // 声明专门负责处理 WebEventTask
    }

    @Override
    public void handleTask(DefaultAgentTaskUnit task, LivingLoop engine, ArrayNode toolsDefinitionArray) {
        WebEventTask webTask = (WebEventTask) task;

        // 1. 装配提示词所需的数据模型
        Map<String, Object> baseData = new HashMap<>();
        baseData.put("taskText", webTask.getTaskText());

        // 如果你的网页交互也需要检索长期记忆支持，可以保留这行；如果不需要可以注释掉以节省 Embedding 算力
        baseData.put("deep_memories", LLManager.getDeepMemories(webTask.getTaskText(), engine.getEmbLLM(), ConfigsManager.MEMORY_DEPTH));

        // 2. 调用 LivingLoop 的公共引擎执行认知循环
        DefaultAgentTaskUnit retTask = engine.executeCognitiveCycle(
                webTask,
                new ScenePromptsManager(WebEventTask.class.getName()), // 加载对应的提示词模板
                baseData,
                engine.getLargeLLM(), // 默认使用主脑处理
                toolsDefinitionArray,
                "网页交互响应任务"
        );

        // 3. 状态判定
        if (retTask == null) {
            log.info("任务 [{}] 已终结并销毁\n", this.getClass().getName());
            return;
        }

        // 如果未完结（如调用了读文件工具，需要下一轮处理），将其重新推回总线
        engine.pushTask(retTask);
    }
}