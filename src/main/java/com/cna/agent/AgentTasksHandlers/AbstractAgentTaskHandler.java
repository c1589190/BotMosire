package com.cna.agent.AgentTasksHandlers;

import com.cna.agent.AgentTask.DefaultAgentTaskUnit;
import com.cna.agent.LivingLoop;
import com.cna.config.ConfigsManager;
import com.cna.config.ScenePromptsManager;
import com.cna.db.FeelingDimensionManager;
import com.cna.db.FeelingDimensionManager.DimensionScore;
import com.cna.llm.LLMAdapter;
import com.fasterxml.jackson.databind.node.ArrayNode;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
public abstract class AbstractAgentTaskHandler implements DefaultAgentTaskHandler {

    @Override
    public void handleTask(DefaultAgentTaskUnit task, LivingLoop engine, ArrayNode toolsDefinitionArray) {
        log.info("[TaskHandler] 正在准备抽象执行流水线: {}", task.getTaskName());

        appendCustomTools(toolsDefinitionArray);

        String currentContext = task.getTaskText() + "\n" + task.getTurnsAddition();
        List<DimensionScore> topFeelings = FeelingDimensionManager.getInstance().getTargetDimensions(currentContext, true, 3);

        StringBuilder feelingsBuilder = new StringBuilder();
        if (topFeelings.isEmpty()) {
            feelingsBuilder.append("当前无明显的潜意识概念共鸣。");
        } else {
            for (DimensionScore score : topFeelings) {
                String polarityText = score.hitWeight >= 0 ? "正面、良好" : "负面、差劲";
                feelingsBuilder.append(String.format("\"%s\",(记忆深度权重: %.2f ,语义映像权重: %.2f )  你对它的映像是 %s 的;\n",
                        score.concept, score.finalScore, score.hitWeight, polarityText));
            }
        }
        String currentFeelingsStr = feelingsBuilder.toString().trim();
        log.info("[TaskHandler] 当前任务阶段触发潜意识数据快照: \n{}", currentFeelingsStr);

        Map<String, Object> baseData = new HashMap<>();
        baseData.put("taskText", task.getTaskText());
        baseData.put("turnsAddition", task.getTurnsAddition());
        baseData.put("current_feelings", currentFeelingsStr);

        if (!prepareBaseData(task, baseData, engine)) {
            log.info("[TaskHandler] 子类放弃了本次任务的执行: {}", task.getTaskName());
            return;
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
            onTaskCompleted(task, engine);
            return;
        }

        engine.pushTask(retTask);
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
     */
    protected void onTaskCompleted(DefaultAgentTaskUnit completedTask, LivingLoop engine) {
        log.info("[TaskHandler] 正在将完结任务抛入感觉中枢进行潜意识结算...");

        // 拼接任务初始文本和执行过程中的想法与工具反馈
        String fullTaskLog = completedTask.getTaskText() + "\n" + completedTask.getTurnsAddition();

        FeelingDimensionManager.getInstance().processTaskLogAsync(fullTaskLog);
    }
}