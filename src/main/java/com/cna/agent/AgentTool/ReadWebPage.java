package com.cna.agent.AgentTool;

import com.cna.config.ConfigsManager;
import com.cna.config.ToolPromptsManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.util.concurrent.TimeUnit;

@Slf4j
public class ReadWebPage implements DefaultAgentToolUnit {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();

    private static final int DEFAULT_MAX_CHARS = 4000;
    private static final int HARD_MAX_CHARS    = 8000;

    private String lastUrl = null;

    @Override
    public String getName() {
        return "read_webpage";
    }

    @Override
    public ObjectNode getToolDefinition() {
        ObjectNode tool = mapper.createObjectNode();
        tool.put("type", "function");

        ToolPromptsManager p = new ToolPromptsManager(this.getClass().getName());

        ObjectNode function = tool.putObject("function");
        function.put("name", getName());
        function.put("description", p.getToolDescription());

        ObjectNode parameters = function.putObject("parameters");
        parameters.put("type", "object");

        ObjectNode properties = parameters.putObject("properties");

        ObjectNode url = properties.putObject("url");
        url.put("type", "string");
        url.put("description", p.getCustomDescription("url"));

        ObjectNode maxChars = properties.putObject("max_chars");
        maxChars.put("type", "integer");
        maxChars.put("description", p.getCustomDescription("max_chars"));

        parameters.putArray("required").add("url");
        return tool;
    }

    @Override
    public String execute(JsonNode arguments) {
        String url = arguments.path("url").asText("").trim();
        if (url.isBlank()) return "ERROR: URL 不能为空。";
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://" + url;
        }

        int maxChars = Math.min(
                Math.max(arguments.path("max_chars").asInt(DEFAULT_MAX_CHARS), 500),
                HARD_MAX_CHARS
        );
        this.lastUrl = url;

        log.info("[ReadWebPage] 读取页面: {} (最大{}字符)", url, maxChars);

        String jinaKey = ConfigsManager.JINA_API_KEY;
        if (jinaKey != null && !jinaKey.isBlank()) {
            String result = readWithJina(url, maxChars, jinaKey);
            if (!result.startsWith("ERROR")) return result;
            log.warn("[ReadWebPage] Jina 读取失败，尝试 MetaSo 备用");
        }

        String metasoKey = ConfigsManager.METASO_API_KEY;
        if (metasoKey != null && !metasoKey.isBlank()) {
            String result = readWithMetaso(url, maxChars, metasoKey);
            if (!result.startsWith("ERROR")) return result;
            log.warn("[ReadWebPage] MetaSo 读取也失败");
        }

        // 最后尝试不用 API Key 的 Jina（免费额度有限）
        String result = readWithJina(url, maxChars, null);
        if (!result.startsWith("ERROR")) return result;

        return "ERROR: 所有读取方式均失败，请稍后重试。";
    }

    // ── Jina AI Reader ──────────────────────────────────────────────────

    private String readWithJina(String url, int maxChars, String apiKey) {
        String jinaUrl = "https://r.jina.ai/" + url;

        Request.Builder reqBuilder = new Request.Builder()
                .url(jinaUrl)
                .header("Accept", "text/plain,text/markdown")
                .header("X-Return-Format", "markdown")
                .header("X-Remove-Selector", "img,script,style,nav,footer,header");

        if (apiKey != null && !apiKey.isBlank()) {
            reqBuilder.header("Authorization", "Bearer " + apiKey);
        }

        try (Response response = httpClient.newCall(reqBuilder.build()).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                return "ERROR: Jina HTTP " + response.code();
            }

            String content = response.body().string();

            int mdStart = content.indexOf("Markdown Content:");
            if (mdStart > 0) {
                content = content.substring(mdStart + "Markdown Content:".length()).stripLeading();
            }

            if (content.length() > maxChars) {
                content = content.substring(0, maxChars)
                        + "\n\n[... 内容已截断，原始共 " + content.length() + " 字符。"
                        + "如需更多内容，可增大 max_chars 参数重新调用。]";
            }

            return "【页面内容】" + url + "\n\n" + content;

        } catch (Exception e) {
            log.error("[ReadWebPage] Jina 异常: {}", url, e);
            return "ERROR: " + e.getMessage();
        }
    }

    // ── MetaSo Reader API (备用) ───────────────────────────────────────

    private String readWithMetaso(String url, int maxChars, String apiKey) {
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("url", url);

            MediaType mediaType = MediaType.parse("text/plain");
            RequestBody requestBody = RequestBody.create(mediaType, body.toString());

            Request request = new Request.Builder()
                    .url("https://metaso.cn/api/v1/reader")
                    .method("POST", requestBody)
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("Accept", "text/plain")
                    .addHeader("Content-Type", "application/json")
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    log.warn("[ReadWebPage] MetaSo HTTP {}", response.code());
                    return "ERROR: MetaSo HTTP " + response.code();
                }

                String content = response.body().string();
                if (content.isBlank()) {
                    return "ERROR: MetaSo 返回空内容";
                }

                if (content.length() > maxChars) {
                    content = content.substring(0, maxChars)
                            + "\n\n[... 内容已截断，原始共 " + content.length() + " 字符。"
                            + "如需更多内容，可增大 max_chars 参数重新调用。]";
                }

                return "【页面内容】" + url + "\n\n" + content;
            }
        } catch (Exception e) {
            log.error("[ReadWebPage] MetaSo 异常: {}", url, e);
            return "ERROR: " + e.getMessage();
        }
    }

    @Override
    public String getTextRecord() {
        return lastUrl != null
                ? "调用工具读取了网页内容: [" + lastUrl + "];"
                : "尝试调用网页读取工具;";
    }
}