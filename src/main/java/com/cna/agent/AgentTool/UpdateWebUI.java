package com.cna.agent.AgentTool;

import com.cna.config.ToolPromptsManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;

import java.io.IOException;

@Slf4j
public class UpdateWebUI implements DefaultAgentToolUnit {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final OkHttpClient client = new OkHttpClient();

    private static final String WEB_SERVER_URL = "http://127.0.0.1:8080/api/llm/command";

    private String lastAction = null;
    private String lastTarget = null;

    @Override
    public String getName() {
        return "update_web_ui";
    }

    @Override
    public ObjectNode getToolDefinition() {
        ObjectNode tool = mapper.createObjectNode();
        tool.put("type", "function");

        // 使用提示词管理器获取所有描述
        ToolPromptsManager p = new ToolPromptsManager(this.getClass().getName());

        ObjectNode function = tool.putObject("function");
        function.put("name", getName());
        function.put("description", p.getToolDescription());

        ObjectNode parameters = function.putObject("parameters");
        parameters.put("type", "object");
        ObjectNode properties = parameters.putObject("properties");

        // action 参数
        ObjectNode actionNode = properties.putObject("action");
        actionNode.put("type", "string");
        actionNode.put("description", p.getCustomDescription("action"));
        // 保留枚举约束，进一步限制 LLM 的输出
        ArrayNode enumNode = actionNode.putArray("enum");
        enumNode.add("update_html").add("append_html").add("eval_js");

        // target 参数
        ObjectNode targetNode = properties.putObject("target");
        targetNode.put("type", "string");
        targetNode.put("description", p.getCustomDescription("target"));

        // html 参数
        ObjectNode htmlNode = properties.putObject("html");
        htmlNode.put("type", "string");
        htmlNode.put("description", p.getCustomDescription("html"));

        // code 参数
        ObjectNode codeNode = properties.putObject("code");
        codeNode.put("type", "string");
        codeNode.put("description", p.getCustomDescription("code"));

        ArrayNode required = parameters.putArray("required");
        required.add("action");

        return tool;
    }

    @Override
    public String execute(JsonNode arguments) {
        String action = arguments.path("action").asText(null);
        String target = arguments.path("target").asText("");
        String html = arguments.path("html").asText("");
        String code = arguments.path("code").asText("");

        if (action == null || action.isBlank()) {
            return "ERROR: action 不能为空，必须是 update_html, append_html 或 eval_js。";
        }

        // 阻止对页面基础结构节点整体替换，那会覆盖 <head> 中的轮询脚本造成死循环
        if ("update_html".equals(action) || "append_html".equals(action)) {
            String t = target.toLowerCase().trim();
            if (t.isEmpty() || t.equals("body") || t.equals("html") || t.equals("head")) {
                return "ERROR: 禁止对 body/html/head 整体使用 " + action + " 操作，" +
                       "这会覆盖页面的通信轮询代码造成功能中断。请使用具体的 ID 选择器（如 #app）来操作内容容器。";
            }
        }

        this.lastAction = action;
        this.lastTarget = target;

        ObjectNode payload = mapper.createObjectNode();
        payload.put("action", action);
        payload.put("target", target);
        payload.put("html", html);
        payload.put("code", code);

        RequestBody body = RequestBody.create(
                payload.toString(),
                MediaType.parse("application/json; charset=utf-8")
        );

        Request request = new Request.Builder()
                .url(WEB_SERVER_URL)
                .post(body)
                .build();

        try (Response response = client.newCall(request).execute()) {
            String respBody = response.body() != null ? response.body().string() : "";
            if (response.isSuccessful()) {
                log.info("[UpdateWebUI] 成功向前端投递指令: action={}", action);
                return "SUCCESS: 前端 UI 更新指令已执行。改动已实时推送并持久化。";
            } else {
                log.error("[UpdateWebUI] 投递失败，状态码: {}, 详情: {}", response.code(), respBody);
                return "ERROR: Web服务器拒绝了请求。提示: " + respBody + " (请检查 target 选择器是否正确)";
            }
        } catch (IOException e) {
            log.error("[UpdateWebUI] 调用 Web 接口发生网络异常", e);
            return "ERROR: 无法连接到本地 Web 服务器: " + e.getMessage();
        }
    }

    @Override
    public String getTextRecord() {
        if (lastAction == null) {
            return "尝试更新网页UI，但未成功执行;";
        }
        if ("update_html".equals(lastAction) || "append_html".equals(lastAction)) {
            return "调用工具安全地向网页的 [" + lastTarget + "] 节点注入了新的 HTML 结构，并永久保存;";
        } else {
            return "调用工具向网页前端注入并执行了一段临时的 JavaScript 代码;";
        }
    }
}