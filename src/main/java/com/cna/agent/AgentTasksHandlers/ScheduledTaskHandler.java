package com.cna.agent.AgentTasksHandlers;

import com.cna.agent.AgentTask.DefaultAgentTaskUnit;
import com.cna.agent.AgentTask.ScheduledTask;
import com.cna.agent.AgentTool.ReflectiveCompactionTool;
import com.cna.agent.LivingLoop;
import com.cna.config.ConfigsManager;
import com.cna.config.ScenePromptsManager;
import com.cna.db.MDManager;
import com.cna.llm.LLMAdapter;
import com.fasterxml.jackson.databind.node.ArrayNode;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;

@Slf4j
public class ScheduledTaskHandler implements DefaultAgentTaskHandler {

    @Override
    public Class<? extends DefaultAgentTaskUnit> getSupportedTaskClass() {
        return ScheduledTask.class; // 认领定时任务
    }

    @Override
    public void handleTask(DefaultAgentTaskUnit task, LivingLoop engine, ArrayNode toolsDefinitionArray) {

        //增加复写固态记忆文件的Tool
        toolsDefinitionArray.add(new ReflectiveCompactionTool().getToolDefinition());

        // 读取外部日程文件
        String scheduledContent = MDManager.read("scheduled.md", "");

        if (scheduledContent.isEmpty()) {
            log.info("========== 当前没有定时任务需要处理 ==========\n");
            return; // 文件为空直接跳过，防止浪费 Token
        }

        Map<String, Object> baseData = new HashMap<>();
        baseData.put("scheduled", scheduledContent);

        // 调用 LivingLoop 的公共引擎，注意这里传入的是 engine.getSchedulerLLM()
        engine.executeCognitiveCycle(
                new ScenePromptsManager(ScheduledTask.class.getName()),
                baseData,
                new LLMAdapter(ConfigsManager.BRAIN_CONFIG),
                toolsDefinitionArray,
                "定时计划任务"
        );

        log.info("========== 定时任务处理完毕 ==========\n");
    }
}