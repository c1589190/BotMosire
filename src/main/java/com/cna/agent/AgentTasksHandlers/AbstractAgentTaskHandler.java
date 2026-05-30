package com.cna.agent.AgentTasksHandlers;

import com.cna.agent.AgentTask.DefaultAgentTaskUnit;
import com.cna.agent.FeelingResonanceAnalyzer;
import com.cna.agent.LivingLoop;
import com.cna.agent.MemoryManager;
import com.cna.config.ConfigsManager;
import com.cna.config.ScenePromptsManager;
import com.cna.llm.LLMAdapter;
import com.fasterxml.jackson.databind.node.ArrayNode;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;

@Slf4j
public abstract class AbstractAgentTaskHandler implements DefaultAgentTaskHandler {

    @Override
    public DefaultAgentTaskUnit handleTask(DefaultAgentTaskUnit task, LivingLoop engine, ArrayNode toolsDefinitionArray) {
        log.info("[TaskHandler] 正在准备抽象执行流水线: {}, 优先级 {}", task.getTaskName(), task.getPriority());

        appendCustomTools(toolsDefinitionArray);

        Map<String, Object> baseData = new HashMap<>();
        baseData.put("taskText", task.getTaskText());
        baseData.put("current_feelings", "");

        if (!prepareBaseData(task, baseData, engine)) {
            log.info("[TaskHandler] 子类放弃了本次任务的执行: {}", task.getTaskName());
            return null; // 任务终止
        }

        // 【谐振分析】：对任务文本进行感觉谐振分析，注入 Prompt + 缓存结果
        try {
            com.cna.db.FeelingDimensionManager fdm = com.cna.db.FeelingDimensionManager.getInstance();
            com.cna.db.FeelingHypergraphManager hgm = com.cna.db.FeelingHypergraphManager.getInstance();
            if (fdm != null && hgm != null) {
                FeelingResonanceAnalyzer analyzer = new FeelingResonanceAnalyzer(
                        fdm, hgm, MemoryManager.getInstance());
                FeelingResonanceAnalyzer.ResonanceAnalysisResult resonance =
                        analyzer.analyze(task.getTaskText());
                if (resonance != null) {
                    baseData.put("feeling_resonance", resonance.llmPromptBlock);
                    baseData.put("feeling_resonance_result", resonance); // 供 finish_task 回馈
                    log.info("[TaskHandler] 感觉谐振分析已注入 Prompt (有违和: {})", resonance.hasDissonance());

                    // 积累违和感到好奇心列表
                    if (resonance.hasDissonance()) {
                        com.cna.agent.CuriosityListManager clm = com.cna.agent.CuriosityListManager.getInstance();
                        if (clm != null) {
                            clm.accumulateFromResonance(resonance, task.getTaskText());
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[TaskHandler] 感觉谐振分析失败，跳过: {}", e.getMessage());
        }

        LLMAdapter targetLLM = getTargetLLM(engine);

        DefaultAgentTaskUnit retTask = engine.executeCognitiveCycle(
                task,
                new ScenePromptsManager(task.getClass().getName()),
                baseData,
                targetLLM,
                toolsDefinitionArray,
                getTaskDescription()
        );

        if (retTask == null) {
            log.info("[TaskHandler] 任务 [{}] 已经圆满终结并销毁。\n", task.getTaskName());
            // 每次任务结束后的唯物结算，开始更新动量模型
            //onTaskCompleted(task, engine);
            return null; // 任务完成
        }

        // 返回更新后的任务，由消费者循环决定是继续粘性执行还是重新入队
        return retTask;
    }

    // =================================================================
    // 以下是开放给子类去实现或覆盖（Override）的 Hook 方法
    // =================================================================

    /**
     * 必须实现：组装该任务专属的数据
     * @return 如果返回 false，系统会直接掐断该任务的执行
     */
    protected abstract boolean prepareBaseData(DefaultAgentTaskUnit task, Map<String, Object> baseData, LivingLoop engine);

    /**
     * 必须实现：用一句话描述这个任务，用于日志打印
     */
    protected abstract String getTaskDescription();

    /**
     * 可选实现：如果任务需要特殊的工具，在这里 add 进去
     */
    protected void appendCustomTools(ArrayNode toolsDefinitionArray) {
        // 默认啥也不加
    }

    /**
     * 可选实现：指定运行该任务的模型，默认返回大型脑模型
     */
    protected LLMAdapter getTargetLLM(LivingLoop engine) {
        return new LLMAdapter(ConfigsManager.BRAIN_CONFIG);
    }

    /**
     * 【重写了默认实现】：任务彻底终结后的回调钩子，全自动更新感觉维度
     * 弃用！
     */
    protected void onTaskCompleted(DefaultAgentTaskUnit completedTask, LivingLoop engine) {
        log.info("[TaskHandler] 正在将完结任务抛入感觉中枢进行潜意识结算...");

        // 拼接任务初始文本和执行过程中的想法与工具反馈
        String fullTaskLog = completedTask.getTaskText() + "\n" + completedTask.getTurnsAddition();

        //FeelingDimensionManager.getInstance().processTaskLogAsync(fullTaskLog);
    }
}