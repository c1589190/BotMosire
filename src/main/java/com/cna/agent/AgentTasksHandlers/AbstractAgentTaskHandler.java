package com.cna.agent.AgentTasksHandlers;

import com.cna.agent.AgentTask.DefaultAgentTaskUnit;
import com.cna.agent.LivingLoop;
import com.cna.config.ConfigsManager;
import com.cna.config.ScenePromptsManager;
import com.cna.db.FeelingDimensionManager;
import com.cna.llm.LLMAdapter;
import com.fasterxml.jackson.databind.node.ArrayNode;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;

@Slf4j
public abstract class AbstractAgentTaskHandler implements DefaultAgentTaskHandler {

    @Override
    public void handleTask(DefaultAgentTaskUnit task, LivingLoop engine, ArrayNode toolsDefinitionArray) {
        log.info("[TaskHandler] 正在准备抽象执行流水线: {}", task.getTaskName());

        // 1. 允许子类向工具箱里额外塞入自己专属的 Tool
        appendCustomTools(toolsDefinitionArray);

        // 2. 初始化 BaseData
        Map<String, Object> baseData = new HashMap<>();
        baseData.put("taskText", task.getTaskText()); // 公共属性，所有任务都有

        // 3. 让子类注入自己专属的 BaseData (比如 Chat 任务要历史记录，Schedule 任务要日程文件)
        if (!prepareBaseData(task, baseData, engine)) {
            log.info("[TaskHandler] 子类放弃了本次任务的执行: {}", task.getTaskName());
            return; // 如果子类返回 false (比如 Schedule 读出来是空的)，直接放弃执行
        }

        // 4. 获取子类希望使用的大模型 (默认用 Brain，子类可以覆盖)
        LLMAdapter targetLLM = getTargetLLM(engine);

        // 5. 调用公共引擎，进入执行循环
        DefaultAgentTaskUnit retTask = engine.executeCognitiveCycle(
                task,
                new ScenePromptsManager(task.getClass().getName()),
                baseData,
                targetLLM,
                toolsDefinitionArray,
                getTaskDescription() // 获取子类定义的任务描述
        );

        // 6. 任务结束/闭环后的统一处理
        if (retTask == null) {
            log.info("[TaskHandler] 任务 [{}] 已经圆满终结并销毁。\n", task.getTaskName());
            // TODO: 未来你说的“每次任务结束后的唯物结算（Feeling更新）”就加在这里！所有任务都会自动继承！
            onTaskCompleted(task, engine);
            return;
        }

        // 7. 如果没执行完，塞回队列等下一轮
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

        // 假设 FeelingDimensionManager 使用了单例模式 (或者你可以通过 engine.getFeelingManager() 获取)
        FeelingDimensionManager.getInstance().processTaskLogAsync(fullTaskLog);
    }
}