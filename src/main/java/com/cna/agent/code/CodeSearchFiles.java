package com.cna.agent.code;

import com.cna.Main;
import com.cna.agent.AgentTool.DefaultAgentToolUnit;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 在 workspace 沙盒内按 glob 模式搜索文件路径。
 */
@Slf4j
public class CodeSearchFiles implements DefaultAgentToolUnit {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final int MAX_RESULTS = 100;

    @Override
    public String getName() {
        return "search_files";
    }

    @Override
    public ObjectNode getToolDefinition() {
        ObjectNode toolDef = mapper.createObjectNode();
        toolDef.put("type", "function");

        ObjectNode function = toolDef.putObject("function");
        function.put("name", getName());
        function.put("description", "在 workspace 沙盒内按 glob 模式（如 '**/*.java'）搜索文件，返回匹配的相对路径列表。");

        ObjectNode params = function.putObject("parameters");
        params.put("type", "object");
        ObjectNode properties = params.putObject("properties");

        ObjectNode patternProp = properties.putObject("pattern");
        patternProp.put("type", "string");
        patternProp.put("description", "glob 模式，例如 '**/*.md'、'src/**/*.json'。");

        ArrayNode required = params.putArray("required");
        required.add("pattern");

        return toolDef;
    }

    @Override
    public String execute(JsonNode arguments) {
        String pattern = arguments.path("pattern").asText("");
        if (pattern.isBlank()) {
            return "ERROR: 必须提供 pattern。";
        }

        Path root = Main.workspaceManager.resolve("/");
        if (root == null) {
            return "ERROR: 无法定位 workspace 根目录。";
        }

        PathMatcher matcher;
        try {
            matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
        } catch (Exception e) {
            return "ERROR: 非法的 glob 模式 - " + e.getMessage();
        }

        List<String> hits = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(p -> !Files.isDirectory(p))
                    .forEach(p -> {
                        Path rel = root.relativize(p);
                        if (matcher.matches(rel) && hits.size() < MAX_RESULTS) {
                            hits.add(rel.toString().replace("\\", "/"));
                        }
                    });
        } catch (Exception e) {
            return "ERROR: 搜索失败 - " + e.getMessage();
        }

        if (hits.isEmpty()) {
            return "未匹配到任何文件: [" + pattern + "]";
        }
        String suffix = hits.size() >= MAX_RESULTS ? "\n(结果已截断至 " + MAX_RESULTS + " 条)" : "";
        return "匹配到 " + hits.size() + " 个文件：\n" + String.join("\n", hits) + suffix;
    }

    @Override
    public String getTextRecord() {
        return "按模式搜索了文件";
    }
}
