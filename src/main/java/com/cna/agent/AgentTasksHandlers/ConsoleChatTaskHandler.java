package com.cna.agent.AgentTasksHandlers;

import com.cna.agent.AgentTask.ConsoleChatTask;
import com.cna.agent.AgentTask.DefaultAgentTaskUnit;
import com.cna.agent.AgentTool.ReflectiveCompactionTool;
import com.cna.agent.LivingLoop;
import com.cna.config.ConfigsManager;
import com.cna.llm.LLMAdapter;
import com.cna.llm.LLManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.util.Map;

@Deprecated
public class ConsoleChatTaskHandler extends AbstractAgentTaskHandler {

    @Override
    public Class<? extends DefaultAgentTaskUnit> getSupportedTaskClass() {
        return ConsoleChatTask.class;
    }

    @Override
    protected void appendCustomTools(ArrayNode toolsDefinitionArray) {
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

        // 自动继承了 taskText，只需要加专属的 deep_memories
        baseData.put("deep_memories", LLManager.getDeepMemories(task.getTaskText(), new LLMAdapter(ConfigsManager.EMBEDDING_CONFIG), ConfigsManager.MEMORY_DEPTH));
        return true; // 允许执行
    }

    @Override
    protected String getTaskDescription() {
        return "系统终端聊天任务";
    }
}