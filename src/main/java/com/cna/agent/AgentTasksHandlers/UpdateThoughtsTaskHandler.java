package com.cna.agent.AgentTasksHandlers;

import com.cna.agent.AgentTask.DefaultAgentTaskUnit;
import com.cna.agent.AgentTask.UpdateThoughtsTask;
import com.cna.agent.AgentTool.ReflectiveCompactionTool;
import com.cna.agent.LivingLoop;
import com.cna.agent.MemoryManager;
import com.cna.config.ConfigsManager;
import com.cna.config.ScenePromptsManager;
import com.cna.db.MDManager;
import com.cna.db.FeelingDimensionManager;
import com.cna.llm.LLMAdapter;
import com.cna.llm.LLManager;
import com.fasterxml.jackson.databind.node.ArrayNode;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
public class UpdateThoughtsTaskHandler implements DefaultAgentTaskHandler {

    @Override
    public Class<? extends DefaultAgentTaskUnit> getSupportedTaskClass() {
        return UpdateThoughtsTask.class; // 认领反思任务
    }

    @Override
    public DefaultAgentTaskUnit handleTask(DefaultAgentTaskUnit task, LivingLoop engine, ArrayNode toolsDefinitionArray) {
        // 首轮确保 ReflectiveCompactionTool 可用；后续轮次由分组系统自动注入
        task.getActivatedToolGroups().add("introspect");
        String targetName = "reflective_memory_compaction";
        boolean alreadyPresent = false;
        for (com.fasterxml.jackson.databind.JsonNode node : toolsDefinitionArray) {
            if (targetName.equals(node.path("function").path("name").asText())) {
                alreadyPresent = true;
                break;
            }
        }
        if (!alreadyPresent) {
            toolsDefinitionArray.add(new ReflectiveCompactionTool().getToolDefinition());
        }

        UpdateThoughtsTask thoughtsTask = (UpdateThoughtsTask) task;

        Map<String, Object> baseData = new HashMap<>();

        // 1. 获取近期需要反思总结的记忆数据
        List<String> rawMemories = MemoryManager.getInstance().getCurrentMemorys(ConfigsManager.CURRENT_MEMORIES_MAXSIZE + ConfigsManager.EMB_MEMORY_SIZE);
        String memoryText = String.join("\n", rawMemories);

        baseData.put("current_memories", rawMemories);
        baseData.put("taskText", thoughtsTask.getTaskText());
        baseData.put("current_interests", MDManager.read("interests.md", ""));
        baseData.put("scheduled", MDManager.read("scheduled.md", ""));

        // ==========================================
        // 2. 潜意识偏好注入：提取全息感知并二分情感极性
        // ==========================================
        FeelingDimensionManager feelingManager = FeelingDimensionManager.getInstance();

        // 设定默认值，防止模板引擎找不到变量报错
        String goodFeelingPrompt = "{}";
        String badFeelingPrompt = "{}";

        if (feelingManager != null) {
            // 将要总结的文本整体丢入感觉中枢，获取全息感知排序
            List<FeelingDimensionManager.DimensionScore> scores = feelingManager.evaluateAllDimensions(memoryText);

            if (scores != null && !scores.isEmpty()) {
                int n = Math.min(3, scores.size()); // 取前 N 名和后 N 名

                // 2.1 极度狂热/共鸣区 (Top N)
                List<String> goodConcepts = new ArrayList<>();
                for (int i = 0; i < n; i++) {
                    goodConcepts.add("\"" + scores.get(i).concept + "\"");
                }
                // 使用 String.join 自动处理中间的逗号，完美输出 {"A", "B"}
                goodFeelingPrompt = "{" + String.join(", ", goodConcepts) + "}";


                // 2.2 极度厌恶/排斥区 (Bottom N)
                List<String> badConcepts = new ArrayList<>();
                int bottomCount = 0;
                // 从列表末尾往前遍历，且确保不会和 Top N 的元素重叠
                for (int i = scores.size() - 1; i >= n && bottomCount < n; i--, bottomCount++) {
                    badConcepts.add("\"" + scores.get(i).concept + "\"");
                }
                badFeelingPrompt = "{" + String.join(", ", badConcepts) + "}";
            }
        }

        // 将组装好的“潜意识人设”放入 baseData
        baseData.put("good_feeling_bias_prompt", goodFeelingPrompt);
        baseData.put("bad_feeling_bias_prompt", badFeelingPrompt);
        // ==========================================


        // 3. 调用 LivingLoop 的公共引擎，使用 largeLLM
        DefaultAgentTaskUnit retTask = engine.executeCognitiveCycle(
                task,
                new ScenePromptsManager(UpdateThoughtsTask.class.getName()),
                baseData,
                new LLMAdapter(ConfigsManager.BRAIN_CONFIG),
                toolsDefinitionArray,
                "系统级反思任务"
        );

        if (retTask == null) {
            log.info("任务" + this.getClass().getName() + "已终结并销毁\n");
            return null; // 任务完成
        }

        // 返回更新后的任务，由消费者循环决定粘性执行还是重新入队
        return retTask;
    }
}