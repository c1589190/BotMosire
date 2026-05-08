package com.cna.agent.AgentTool;

import com.cna.config.ConfigsManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
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

        ObjectNode function = tool.putObject("function");
        function.put("name", getName());
        function.put("description",
                "读取指定 URL 网页的文字内容，以 Markdown 格式返回。" +
                "适合在 web_search 找到相关链接后，需要深入阅读某篇文章时调用。" +
                "也可以读取用户直接贴给你的链接。");

        ObjectNode parameters = function.putObject("parameters");
        parameters.put("type", "object");

        ObjectNode properties = parameters.putObject("properties");

        ObjectNode url = properties.putObject("url");
        url.put("type", "string");
        url.put("description", "要读取的完整 URL，必须以 http:// 或 https:// 开头");

        ObjectNode maxChars = properties.putObject("max_chars");
        maxChars.put("type", "integer");
        maxChars.put("description", "最多读取的字符数，默认 4000，最大 8000");

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

        // 通过 Jina AI Reader 将网页转为干净的 Markdown
        String jinaUrl = "https://r.jina.ai/" + url;

        Request.Builder reqBuilder = new Request.Builder()
                .url(jinaUrl)
                .header("Accept", "text/plain,text/markdown")
                .header("X-Return-Format", "markdown")
                // 指示 Jina 不要包含图片（节省 token）
                .header("X-Remove-Selector", "img,script,style,nav,footer,header");

        String jinaKey = ConfigsManager.JINA_API_KEY;
        if (jinaKey != null && !jinaKey.isBlank()) {
            reqBuilder.header("Authorization", "Bearer " + jinaKey);
        }

        try (Response response = httpClient.newCall(reqBuilder.build()).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                return "ERROR: 读取页面失败，HTTP " + response.code() +
                       "。请检查 URL 是否有效或该网站是否允许访问。";
            }

            String content = response.body().string();

            // 清理 Jina 返回的元数据头部（Title: ... URL: ... Markdown Content: 之类的行）
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
            log.error("[ReadWebPage] 读取页面异常: {}", url, e);
            return "ERROR: 读取页面时发生异常: " + e.getMessage();
        }
    }

    @Override
    public String getTextRecord() {
        return lastUrl != null
                ? "调用工具读取了网页内容: [" + lastUrl + "];"
                : "尝试调用网页读取工具;";
    }
}
