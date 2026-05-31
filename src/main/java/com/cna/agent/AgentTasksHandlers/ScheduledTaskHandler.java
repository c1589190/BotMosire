package com.cna.agent.AgentTasksHandlers;

import com.cna.agent.AgentTask.DefaultAgentTaskUnit;
import com.cna.agent.AgentTask.ScheduledTask;
import com.cna.agent.AgentTool.ReflectiveCompactionTool;
import com.cna.agent.LivingLoop;
import com.cna.config.ConfigsManager;
import com.cna.db.MDManager;
import com.cna.llm.LLMAdapter;
import com.cna.llm.LLManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.util.Map;

@Deprecated
public class ScheduledTaskHandler extends AbstractAgentTaskHandler {

    @Override
    public Class<? extends DefaultAgentTaskUnit> getSupportedTaskClass() {
        return ScheduledTask.class;
    }

    @Override
    protected void appendCustomTools(ArrayNode toolsDefinitionArray) {
        // 确保 introspect 组的 ReflectiveCompactionTool 在首轮就可用
        // 后续轮次由消费者循环根据 task.activatedToolGroups 自动注入
        String targetName = "reflective_memory_compaction";
        for (JsonNode node : toolsDefinitionArray) {
            if (targetName.equals(node.path("function").path("name").asText())) {
                return;
            }
        }
        toolsDefinitionArray.add(new ReflectiveCompactionTool().getToolDefinition());
    }

    @Override
    protected boolean prepareBaseData(DefaultAgentTaskUnit task, Map<String, Object> baseData, LivingLoop engine) {
        task.getActivatedToolGroups().add("introspect");

        String scheduledContent = MDManager.read("scheduled.md", "");
        if (scheduledContent.isEmpty()) {
            return false; // 文件为空直接拒绝执行！父类会自动拦截，防浪费 Token
        }
        baseData.put("scheduled", scheduledContent);
        baseData.put("deep_memories", LLManager.getDeepMemories(scheduledContent, engine.getEmbLLM(), ConfigsManager.MEMORY_DEPTH));
        return true;
    }

    @Override
    protected LLMAdapter getTargetLLM(LivingLoop engine) {
        // 覆盖默认模型，指定使用 Scheduler 模型
        return new LLMAdapter(ConfigsManager.BRAIN_CONFIG);
    }

    @Override
    protected String getTaskDescription() {
        return "定时计划调度任务";
    }
}