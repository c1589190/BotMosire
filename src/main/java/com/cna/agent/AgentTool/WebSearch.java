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
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
public class WebSearch implements DefaultAgentToolUnit {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final OkHttpClient searchClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build();

    private String lastQuery = null;

    @Override
    public String getName() {
        return "web_search";
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

        ObjectNode query = properties.putObject("query");
        query.put("type", "string");
        query.put("description", p.getCustomDescription("query"));

        ObjectNode count = properties.putObject("count");
        count.put("type", "integer");
        count.put("description", p.getCustomDescription("count"));

        parameters.putArray("required").add("query");
        return tool;
    }

    @Override
    public String execute(JsonNode arguments) {
        String query = arguments.path("query").asText("").trim();
        if (query.isBlank()) return "ERROR: 搜索关键词不能为空。";

        int count = Math.min(Math.max(arguments.path("count").asInt(5), 1), 8);
        this.lastQuery = query;

        log.info("[WebSearch] 搜索: {} (数量={})", query, count);

        String apiKey = ConfigsManager.SEARCH_API_KEY;
        if (apiKey != null && !apiKey.isBlank()) {
            String result = searchWithBrave(query, count, apiKey);
            if (!result.startsWith("ERROR")) return result;
            log.warn("[WebSearch] Brave 搜索失败，尝试 MetaSo 备用");
        }

        String metasoKey = ConfigsManager.METASO_API_KEY;
        if (metasoKey != null && !metasoKey.isBlank()) {
            String result = searchWithMetaso(query, count, metasoKey);
            if (!result.startsWith("ERROR")) return result;
            log.warn("[WebSearch] MetaSo 搜索失败，降级至 DuckDuckGo");
        }

        return searchWithDuckDuckGo(query, count);
    }

    // ── Brave Search API ─────────────────────────────────────────────────

    private String searchWithBrave(String query, int count, String apiKey) {
        try {
            String url = "https://api.search.brave.com/res/v1/web/search?q="
                    + URLEncoder.encode(query, StandardCharsets.UTF_8)
                    + "&count=" + count
                    + "&search_lang=zh-hant&country=TW";

            Request request = new Request.Builder()
                    .url(url)
                    .header("Accept", "application/json")
                    .header("Accept-Encoding", "gzip")
                    .header("X-Subscription-Token", apiKey)
                    .build();

            try (Response response = searchClient.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    log.warn("[WebSearch] Brave HTTP {}", response.code());
                    return "ERROR: Brave HTTP " + response.code();
                }
                JsonNode root = mapper.readTree(response.body().string());
                JsonNode results = root.path("web").path("results");
                if (!results.isArray() || results.isEmpty()) {
                    return "SYSTEM_FEEDBACK: Brave 搜索未返回结果，换个关键词试试。";
                }

                List<SearchResult> list = new ArrayList<>();
                for (JsonNode r : results) {
                    if (list.size() >= count) break;
                    SearchResult sr = new SearchResult();
                    sr.title   = r.path("title").asText("");
                    sr.url     = r.path("url").asText("");
                    sr.snippet = r.path("description").asText("");
                    if (!sr.url.isBlank()) list.add(sr);
                }
                return format(query, list, "Brave Search");
            }
        } catch (Exception e) {
            log.error("[WebSearch] Brave 异常", e);
            return "ERROR: " + e.getMessage();
        }
    }

    // ── MetaSo Search API (备用) ───────────────────────────────────────

    private String searchWithMetaso(String query, int count, String apiKey) {
        try {
            String url = "https://metaso.cn/api/v1/search";

            ObjectNode body = mapper.createObjectNode();
            body.put("q", query);
            body.put("scope", "webpage");
            body.put("includeSummary", false);
            body.put("size", String.valueOf(count));
            body.put("includeRawContent", false);
            body.put("conciseSnippet", false);

            MediaType mediaType = MediaType.parse("application/json");
            RequestBody requestBody = RequestBody.create(mediaType, body.toString());

            Request request = new Request.Builder()
                    .url(url)
                    .method("POST", requestBody)
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("Accept", "application/json")
                    .addHeader("Content-Type", "application/json")
                    .build();

            try (Response response = searchClient.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    log.warn("[WebSearch] MetaSo HTTP {}", response.code());
                    return "ERROR: MetaSo HTTP " + response.code();
                }
                JsonNode root = mapper.readTree(response.body().string());
                JsonNode webpages = root.path("webpages");
                if (!webpages.isArray() || webpages.isEmpty()) {
                    return "SYSTEM_FEEDBACK: MetaSo 搜索未返回结果，换个关键词试试。";
                }

                List<SearchResult> list = new ArrayList<>();
                for (JsonNode r : webpages) {
                    if (list.size() >= count) break;
                    SearchResult sr = new SearchResult();
                    sr.title   = r.path("title").asText("");
                    sr.url     = r.path("link").asText("");
                    sr.snippet = r.path("snippet").asText("");
                    if (!sr.url.isBlank()) list.add(sr);
                }
                return format(query, list, "MetaSo Search");
            }
        } catch (Exception e) {
            log.error("[WebSearch] MetaSo 异常", e);
            return "ERROR: " + e.getMessage();
        }
    }

    // ── DuckDuckGo HTML fallback ─────────────────────────────────────────

    private String searchWithDuckDuckGo(String query, int count) {
        try {
            String url = "https://html.duckduckgo.com/html/?q="
                    + URLEncoder.encode(query, StandardCharsets.UTF_8);

            Request request = new Request.Builder()
                    .url(url)
                    .header("User-Agent",
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .header("Accept-Language", "zh-TW,zh;q=0.9,en;q=0.8")
                    .build();

            String html;
            try (Response response = searchClient.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    return "ERROR: DuckDuckGo HTTP " + response.code();
                }
                html = response.body().string();
            }

            Document doc = Jsoup.parse(html);
            Elements resultEls = doc.select("div.result__body");

            List<SearchResult> list = new ArrayList<>();
            for (Element el : resultEls) {
                if (list.size() >= count) break;

                Element titleEl   = el.selectFirst("a.result__a");
                Element snippetEl = el.selectFirst("a.result__snippet");
                if (titleEl == null) continue;

                SearchResult sr = new SearchResult();
                sr.title   = titleEl.text();
                sr.url     = extractDdgUrl(titleEl.attr("href"));
                sr.snippet = snippetEl != null ? snippetEl.text() : "";

                if (sr.url != null && !sr.url.isBlank()) list.add(sr);
            }

            if (list.isEmpty()) return "SYSTEM_FEEDBACK: 未找到相关搜索结果，换个关键词试试。";
            return format(query, list, "DuckDuckGo");

        } catch (Exception e) {
            log.error("[WebSearch] DuckDuckGo 异常", e);
            return "ERROR: 搜索发生网络异常: " + e.getMessage();
        }
    }

    private static String extractDdgUrl(String href) {
        if (href == null || href.isBlank()) return null;
        if (href.startsWith("http")) return href;
        int idx = href.indexOf("uddg=");
        if (idx < 0) return null;
        String encoded = href.substring(idx + 5);
        int amp = encoded.indexOf('&');
        if (amp > 0) encoded = encoded.substring(0, amp);
        try {
            return URLDecoder.decode(encoded, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    // ── 格式化輸出 ────────────────────────────────────────────────────────

    private static String format(String query, List<SearchResult> results, String source) {
        StringBuilder sb = new StringBuilder();
        sb.append("【网络搜索结果】关键词: \"").append(query).append("\" (来源: ").append(source).append(")\n\n");
        for (int i = 0; i < results.size(); i++) {
            SearchResult r = results.get(i);
            sb.append(i + 1).append(". ").append(r.title).append("\n");
            sb.append("   🔗 ").append(r.url).append("\n");
            if (!r.snippet.isBlank()) {
                sb.append("   ").append(r.snippet).append("\n");
            }
            sb.append("\n");
        }
        sb.append("如需深入阅读某个链接，可调用 read_webpage 工具。");
        return sb.toString().trim();
    }

    @Override
    public String getTextRecord() {
        return lastQuery != null
                ? "调用网络搜索工具，搜索了关键词: [" + lastQuery + "];"
                : "尝试调用网络搜索工具;";
    }

    private static class SearchResult {
        String title = "";
        String url   = "";
        String snippet = "";
    }
}