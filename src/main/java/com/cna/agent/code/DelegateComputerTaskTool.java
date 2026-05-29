package com.cna.agent.code;

import com.cna.agent.AgentTool.DefaultAgentToolUnit;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 主脑工具箱中唯一的「那扇门」：把文件/代码/电脑操作类任务委派给隔离的 {@link CodeAgent}。
 * <p>
 * 主脑只看到这一个工具，子执行体的几十个工具与上下文都被关在门后，意识流（GLOBAL_CACHE）保持干净。
 * 子任务在专属单线程 executor 上执行，带超时；超时则 cancel，避免拖死认知消费线程。
 */
@Slf4j
public class DelegateComputerTaskTool implements DefaultAgentToolUnit {

    private static final ObjectMapper mapper = new ObjectMapper();

    // 子任务最长执行时间（含多轮工具循环）。computer-use 可能较慢，给宽一点。
    private static final long TASK_TIMEOUT_SECONDS = 180;

    private final ExecutorService executor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "code-agent");
                t.setDaemon(true);
                return t;
            });

    // 懒加载：未被调用时不构建 LLMAdapter / 工具箱
    private volatile CodeAgent codeAgent;

    private CodeAgent agent() {
        if (codeAgent == null) {
            synchronized (this) {
                if (codeAgent == null) {
                    codeAgent = new CodeAgent();
                }
            }
        }
        return codeAgent;
    }

    @Override
    public String getName() {
        return "delegate_computer_task";
    }

    @Override
    public ObjectNode getToolDefinition() {
        ObjectNode toolDef = mapper.createObjectNode();
        toolDef.put("type", "function");

        ObjectNode function = toolDef.putObject("function");
        function.put("name", getName());
        function.put("description",
                "把文件/代码/电脑操作类任务委派给专职执行体处理（如查看、读写、整理、压缩 workspace 文件等）。"
                        + "请把完整需求一次性写进 task_description，执行体会用真实工具完成并返回结果摘要。");

        ObjectNode params = function.putObject("parameters");
        params.put("type", "object");
        ObjectNode properties = params.putObject("properties");

        ObjectNode taskProp = properties.putObject("task_description");
        taskProp.put("type", "string");
        taskProp.put("description", "交给执行体的完整任务描述，越具体越好（包含目标文件/目录、期望产出等）。");

        ArrayNode required = params.putArray("required");
        required.add("task_description");

        return toolDef;
    }

    @Override
    public String execute(JsonNode arguments) {
        String task = arguments.path("task_description").asText("");
        if (task.isBlank()) {
            return "ERROR: 必须提供 task_description。";
        }

        log.info("[Delegate] 委派子任务: {}", task);
        Future<String> future = executor.submit(() -> agent().execute(task));
        try {
            return future.get(TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.error("[Delegate] 子任务超时 ({}s)，已取消", TASK_TIMEOUT_SECONDS);
            return "[失败] 子任务执行超时（" + TASK_TIMEOUT_SECONDS + "秒），已中止。";
        } catch (Exception e) {
            future.cancel(true);
            log.error("[Delegate] 子任务执行异常", e);
            return "[失败] 子任务执行异常: " + e.getMessage();
        }
    }

    @Override
    public String getTextRecord() {
        return "委派了一个文件/电脑操作子任务";
    }

    @Override
    public boolean isAutoMemory() {
        return true;
    }
}
