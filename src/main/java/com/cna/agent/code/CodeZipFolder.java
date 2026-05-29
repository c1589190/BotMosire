package com.cna.agent.code;

import com.cna.Main;
import com.cna.agent.AgentTool.DefaultAgentToolUnit;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 将 workspace 沙盒内的某个文件夹压缩为 zip（输出也必须落在沙盒内）。
 */
@Slf4j
public class CodeZipFolder implements DefaultAgentToolUnit {

    private static final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String getName() {
        return "zip_folder";
    }

    @Override
    public ObjectNode getToolDefinition() {
        ObjectNode toolDef = mapper.createObjectNode();
        toolDef.put("type", "function");

        ObjectNode function = toolDef.putObject("function");
        function.put("name", getName());
        function.put("description", "把 workspace 内的一个文件夹压缩为 zip 文件。源与输出都必须在 workspace 沙盒内。");

        ObjectNode params = function.putObject("parameters");
        params.put("type", "object");
        ObjectNode properties = params.putObject("properties");

        ObjectNode sourceProp = properties.putObject("source");
        sourceProp.put("type", "string");
        sourceProp.put("description", "要压缩的源文件夹路径（相对 workspace，如 'project'）。");

        ObjectNode outputProp = properties.putObject("output");
        outputProp.put("type", "string");
        outputProp.put("description", "输出 zip 的路径（相对 workspace，如 'project.zip'）。");

        ArrayNode required = params.putArray("required");
        required.add("source");
        required.add("output");

        return toolDef;
    }

    @Override
    public String execute(JsonNode arguments) {
        String source = arguments.path("source").asText("");
        String output = arguments.path("output").asText("");
        if (source.isBlank() || output.isBlank()) {
            return "ERROR: 必须同时提供 source 和 output。";
        }

        Path srcDir = Main.workspaceManager.resolve(source);
        Path outZip = Main.workspaceManager.resolve(output);
        if (srcDir == null || outZip == null) {
            return "ERROR: 非法路径或尝试越权访问沙盒外目录。";
        }
        if (!Files.exists(srcDir) || !Files.isDirectory(srcDir)) {
            return "ERROR: 源不存在或不是文件夹: [" + source + "]";
        }

        long[] count = {0};
        try {
            Files.createDirectories(outZip.getParent());
            try (OutputStream os = Files.newOutputStream(outZip);
                 ZipOutputStream zos = new ZipOutputStream(os);
                 Stream<Path> walk = Files.walk(srcDir)) {

                walk.filter(p -> !Files.isDirectory(p)).forEach(p -> {
                    String entryName = srcDir.getParent().relativize(p).toString().replace("\\", "/");
                    try {
                        zos.putNextEntry(new ZipEntry(entryName));
                        Files.copy(p, zos);
                        zos.closeEntry();
                        count[0]++;
                    } catch (IOException e) {
                        log.warn("[CodeZipFolder] 压缩条目失败: {}", p, e);
                    }
                });
            }
            return String.format("SUCCESS: 已压缩 %d 个文件 -> [%s]", count[0], output);
        } catch (IOException e) {
            return "ERROR: 压缩失败 - " + e.getMessage();
        }
    }

    @Override
    public String getTextRecord() {
        return "压缩了一个文件夹";
    }
}
