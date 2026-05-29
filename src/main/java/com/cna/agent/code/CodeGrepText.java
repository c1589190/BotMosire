package com.cna.agent.code;

import com.cna.Main;
import com.cna.agent.AgentTool.DefaultAgentToolUnit;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 在 workspace 沙盒内搜索包含指定关键字的文本行（类似 grep）。
 */
@Slf4j
public class CodeGrepText implements DefaultAgentToolUnit {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final int MAX_HITS = 80;
    private static final long MAX_FILE_BYTES = 2_000_000; // 跳过超大文件，避免卡顿

    @Override
    public String getName() {
        return "grep_text";
    }

    @Override
    public ObjectNode getToolDefinition() {
        ObjectNode toolDef = mapper.createObjectNode();
        toolDef.put("type", "function");

        ObjectNode function = toolDef.putObject("function");
        function.put("name", getName());
        function.put("description", "在 workspace 沙盒内搜索包含某个关键字的文本行，返回 文件:行号:内容。");

        ObjectNode params = function.putObject("parameters");
        params.put("type", "object");
        ObjectNode properties = params.putObject("properties");

        ObjectNode keywordProp = properties.putObject("keyword");
        keywordProp.put("type", "string");
        keywordProp.put("description", "要搜索的关键字（纯文本，区分大小写）。");

        ObjectNode pathProp = properties.putObject("path");
        pathProp.put("type", "string");
        pathProp.put("description", "[可选] 限定搜索的子目录（相对 workspace）。留空则搜索整个 workspace。");

        ArrayNode required = params.putArray("required");
        required.add("keyword");

        return toolDef;
    }

    @Override
    public String execute(JsonNode arguments) {
        String keyword = arguments.path("keyword").asText("");
        if (keyword.isBlank()) {
            return "ERROR: 必须提供 keyword。";
        }
        String path = arguments.path("path").asText("");

        Path base = Main.workspaceManager.resolve(path.isBlank() ? "/" : path);
        if (base == null) {
            return "ERROR: 非法路径或越权访问。";
        }
        if (!Files.exists(base)) {
            return "ERROR: 路径不存在: [" + path + "]";
        }

        List<String> hits = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(base)) {
            for (Path p : (Iterable<Path>) walk.filter(f -> !Files.isDirectory(f))::iterator) {
                if (hits.size() >= MAX_HITS) break;
                try {
                    if (Files.size(p) > MAX_FILE_BYTES) continue;
                    List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
                    Path root = Main.workspaceManager.resolve("/");
                    String rel = root != null ? root.relativize(p).toString().replace("\\", "/") : p.getFileName().toString();
                    for (int i = 0; i < lines.size() && hits.size() < MAX_HITS; i++) {
                        if (lines.get(i).contains(keyword)) {
                            hits.add(rel + ":" + (i + 1) + ":" + lines.get(i).trim());
                        }
                    }
                } catch (IOException ignored) {
                    // 二进制或无法读取的文件直接跳过
                }
            }
        } catch (Exception e) {
            return "ERROR: 搜索失败 - " + e.getMessage();
        }

        if (hits.isEmpty()) {
            return "未找到包含 [" + keyword + "] 的内容。";
        }
        String suffix = hits.size() >= MAX_HITS ? "\n(结果已截断至 " + MAX_HITS + " 条)" : "";
        return "找到 " + hits.size() + " 处：\n" + String.join("\n", hits) + suffix;
    }

    @Override
    public String getTextRecord() {
        return "搜索了文本关键字";
    }
}
