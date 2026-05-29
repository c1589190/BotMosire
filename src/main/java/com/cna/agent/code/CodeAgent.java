package com.cna.agent.code;

import com.cna.agent.AgentTool.DefaultAgentToolUnit;
import com.cna.agent.AgentTool.io.ListFiles;
import com.cna.agent.AgentTool.io.ReadFile;
import com.cna.agent.AgentTool.io.WriteFile;
import com.cna.config.ConfigsManager;
import com.cna.db.MDManager;
import com.cna.llm.CallResult;
import com.cna.llm.LLMAdapter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 隔离的子执行体：负责文件 / 代码 / 电脑操作类任务。
 * <p>
 * 关键设计（务必保持）：
 * <ul>
 *   <li>自维护一份局部 {@code ArrayNode messages}，<b>直呼 {@link LLMAdapter#generateResponseWithTools}</b>，
 *       <b>绝不</b>经过 {@code LLManager.executeScene}（那是主脑 GLOBAL_CACHE 的入口）。
 *       因此本执行体的上下文与主脑意识流完全隔离，跑完只把一段摘要交回。</li>
 *   <li>有界循环 + 三道护栏：最大轮次、最大错误数、连续无工具调用判定。</li>
 *   <li>finish_task 由循环按工具名拦截，使用 {@link CodeAgentFinishTask}（与主脑 FinishTask 隔离）。</li>
 * </ul>
 */
@Slf4j
public class CodeAgent {

    private static final ObjectMapper mapper = new ObjectMapper();

    private static final int MAX_ITERATIONS = 12;
    private static final int MAX_ERRORS = 4;
    private static final int MAX_NO_TOOL = 3;

    private final LLMAdapter llm;
    private final Map<String, DefaultAgentToolUnit> toolbox = new LinkedHashMap<>();
    private final McpToolHub mcpHub;

    public CodeAgent() {
        // P1 先复用 BRAIN_CONFIG；日后可换成专属 / 备用模型（见 feature-model-escalation-backup-api）。
        this.llm = new LLMAdapter(ConfigsManager.BRAIN_CONFIG);

        register(new ListFiles());
        register(new ReadFile());
        register(new WriteFile());
        register(new CodeZipFolder());
        register(new CodeSearchFiles());
        register(new CodeGrepText());
        register(new CodeAgentFinishTask());

        // 浏览器自动化（Playwright MCP，stdio 子进程长驻）。Node/npx 缺失或启动失败时
        // hub 会优雅降级返回空列表，子执行体仍保有文件能力。Chromium 仅在真正导航时才由 MCP 拉起。
        this.mcpHub = new McpToolHub(mapper);
        if (ConfigsManager.CODE_AGENT_BROWSER_ENABLED) {
            for (DefaultAgentToolUnit t : mcpHub.connectNpx("playwright", "@playwright/mcp@latest", "--headless")) {
                register(t);
            }
        }
        // 桌面操控（Windows-MCP，UIA 树）。默认关闭；启用时排除最高风险工具
        // （PowerShell/Registry 可任意改系统、FileSystem 会绕过 workspace 沙盒、Process 可杀进程）。
        if (ConfigsManager.CODE_AGENT_DESKTOP_ENABLED) {
            for (DefaultAgentToolUnit t : mcpHub.connectUvx("windows", "windows-mcp",
                    "serve", "--transport", "stdio",
                    "--exclude-tools", "PowerShell,Registry,FileSystem,Process")) {
                register(t);
            }
        }
    }

    /** 关闭子执行体持有的 MCP 子进程（hub 另有 JVM 关闭钩子兜底）。 */
    public void shutdown() {
        if (mcpHub != null) mcpHub.shutdown();
    }

    private void register(DefaultAgentToolUnit tool) {
        toolbox.put(tool.getName(), tool);
    }

    /**
     * 执行一个子任务，返回交回主脑的结果摘要。保证非 null。
     */
    public String execute(String taskDescription) {
        String systemPrompt = MDManager.read("prompts/CODE_AGENT_CORE.md",
                "你是 BotMosire 的文件/代码操作子代理，用工具完成任务后调用 finish_task(success, summary)。");

        ArrayNode messages = mapper.createArrayNode();
        messages.add(textMsg("system", systemPrompt));
        messages.add(textMsg("user", taskDescription));

        ArrayNode tools = buildToolDefs();

        int errorCount = 0;
        int noToolCount = 0;
        String lastContent = "";

        for (int iter = 0; iter < MAX_ITERATIONS; iter++) {
            CallResult result;
            try {
                result = llm.generateResponseWithTools(messages, tools);
            } catch (Exception e) {
                log.error("[CodeAgent] LLM 调用异常 ({}/{})", ++errorCount, MAX_ERRORS, e);
                if (errorCount >= MAX_ERRORS) return fail("LLM 调用多次失败: " + e.getMessage());
                continue;
            }
            if (result == null) {
                log.warn("[CodeAgent] LLM 返回 null ({}/{})", ++errorCount, MAX_ERRORS);
                if (errorCount >= MAX_ERRORS) return fail("LLM 多次返回空结果");
                continue;
            }

            // 采用包含本轮 assistant 的完整上下文，继续往下拼（局部，不碰 GLOBAL_CACHE）
            if (result.getContextMessages() != null) {
                messages = result.getContextMessages();
            }
            if (result.getContent() != null && !result.getContent().isBlank()) {
                lastContent = result.getContent();
            }

            JsonNode toolCalls = result.getToolCalls();
            boolean hasToolCalls = result.isToolCall() && toolCalls != null && toolCalls.isArray() && !toolCalls.isEmpty();

            if (!hasToolCalls) {
                noToolCount++;
                if (noToolCount >= MAX_NO_TOOL) {
                    return fail("模型连续未调用工具，无法确认真实结果。最后回复：" + lastContent);
                }
                messages.add(textMsg("user",
                        "你刚刚没有调用工具。请用工具推进任务；若已完成或无法继续，必须调用 finish_task(success, summary)。"));
                continue;
            }
            noToolCount = 0;

            // 先把本轮所有非 finish 工具执行完并回喂（保持 assistant↔tool 配对完整），最后再判断是否结束
            boolean finished = false;
            boolean finishOk = true;
            String finishSummary = null;

            for (JsonNode tc : toolCalls) {
                String name = tc.path("function").path("name").asText("");
                String argsStr = tc.path("function").path("arguments").asText("");
                String callId = tc.path("id").asText("");
                JsonNode args = parseArgs(argsStr);

                if ("finish_task".equals(name)) {
                    finished = true;
                    finishOk = args.path("success").asBoolean(true);
                    finishSummary = args.path("summary").asText("");
                    messages.add(toolMsg(callId, name, "(已收到结束信号)"));
                    continue;
                }

                String toolResult;
                DefaultAgentToolUnit tool = toolbox.get(name);
                if (tool == null) {
                    toolResult = "工具 \"" + name + "\" 不存在。";
                } else {
                    try {
                        toolResult = tool.execute(args);
                    } catch (Exception e) {
                        log.error("[CodeAgent] 工具执行异常: {}", name, e);
                        toolResult = "工具执行异常: " + e.getMessage();
                    }
                }
                log.info("[CodeAgent] 工具 [{}] -> {}", name, toolResult);
                messages.add(toolMsg(callId, name, toolResult));
            }

            if (finished) {
                String summary = (finishSummary == null || finishSummary.isBlank()) ? "(无总结)" : finishSummary;
                log.info("[CodeAgent] 子任务结束 success={} summary={}", finishOk, summary);
                return (finishOk ? "[成功] " : "[失败] ") + summary;
            }
        }

        return fail("达到最大轮次 " + MAX_ITERATIONS + "，任务未完成。最后回复：" + lastContent);
    }

    private ArrayNode buildToolDefs() {
        ArrayNode arr = mapper.createArrayNode();
        for (DefaultAgentToolUnit tool : toolbox.values()) {
            arr.add(tool.getToolDefinition());
        }
        return arr;
    }

    private JsonNode parseArgs(String argsStr) {
        try {
            if (argsStr == null || argsStr.isBlank()) return mapper.createObjectNode();
            return mapper.readTree(argsStr);
        } catch (Exception e) {
            try {
                return mapper.readTree(LLMAdapter.repairInnerJsonQuotes(argsStr));
            } catch (Exception ignored) {
                return mapper.createObjectNode();
            }
        }
    }

    private ObjectNode textMsg(String role, String content) {
        ObjectNode msg = mapper.createObjectNode();
        msg.put("role", role);
        msg.put("content", content);
        return msg;
    }

    private ObjectNode toolMsg(String callId, String name, String content) {
        ObjectNode msg = mapper.createObjectNode();
        msg.put("role", "tool");
        msg.put("tool_call_id", callId);
        msg.put("name", name);
        msg.put("content", content);
        return msg;
    }

    private String fail(String reason) {
        log.warn("[CodeAgent] 子任务失败: {}", reason);
        return "[失败] " + reason;
    }
}
