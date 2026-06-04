package com.cna.agent.AgentTool.io;

import com.cna.agent.AgentTool.DefaultAgentToolUnit;
import com.cna.config.ToolPromptsManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 文档阅读工具 — 支持 PDF 自动提取文字。
 *
 * 对于 .pdf 文件：使用 PDFBox 提取全文。
 * 对于 .txt/.md/.log 等文本文件：直接读取内容。
 * 支持分段读取（chunk_id），避免超长文档撑爆上下文。
 */
@Slf4j
public class ReadDocument implements DefaultAgentToolUnit {

    private final ObjectMapper mapper = new ObjectMapper();

    private static final int CHARS_PER_CHUNK = 4000; // 每段约 1000 tokens

    @Override
    public String getName() {
        return "read_document";
    }

    @Override
    public ObjectNode getToolDefinition() {
        ObjectNode tool = mapper.createObjectNode();
        tool.put("type", "function");

        ToolPromptsManager p = new ToolPromptsManager(this.getClass().getSimpleName());

        ObjectNode fn = tool.putObject("function");
        fn.put("name", getName());
        fn.put("description", "阅读文档内容。支持 PDF（自动提取文字）、TXT、MD 等格式。大文件分段读取，chunk_id 从 0 开始递增。");

        ObjectNode props = fn.putObject("parameters").put("type", "object").putObject("properties");

        props.putObject("path")
                .put("type", "string")
                .put("description", "文档路径，如 workspace/downloads/论文.pdf");

        props.putObject("chunk_id")
                .put("type", "integer")
                .put("description", "段落编号（从 0 开始），用于分段读取大文件。默认 0。");

        fn.with("parameters").putArray("required").add("path");
        props.put("additionalProperties", false);
        return tool;
    }

    @Override
    public String execute(JsonNode arguments) {
        String pathStr = arguments.path("path").asText("").trim();
        int chunkId = arguments.path("chunk_id").asInt(0);

        if (pathStr.isBlank()) {
            return "ERROR: path 参数不能为空。";
        }

        Path path = Paths.get(pathStr).toAbsolutePath().normalize();
        if (!Files.exists(path)) {
            return "ERROR: 文件不存在: " + path;
        }
        if (!Files.isRegularFile(path)) {
            return "ERROR: 不是文件: " + path;
        }

        try {
            String fileName = path.getFileName().toString().toLowerCase();
            String fullText;

            if (fileName.endsWith(".pdf")) {
                fullText = extractPdfText(path);
                if (fullText == null || fullText.isBlank()) {
                    return "WARN: PDF 文件中没有可提取的文字。可能是扫描版 PDF（图片），当前暂不支持 OCR 识别。";
                }
            } else {
                fullText = Files.readString(path);
            }

            // 分段返回
            int totalChars = fullText.length();
            int totalChunks = (int) Math.ceil((double) totalChars / CHARS_PER_CHUNK);

            if (chunkId < 0) chunkId = 0;
            if (chunkId >= totalChunks) {
                return "INFO: chunk_id=" + chunkId + " 超出范围，全文共 " + totalChunks + " 段（" + totalChars + " 字符）。";
            }

            int start = chunkId * CHARS_PER_CHUNK;
            int end = Math.min(start + CHARS_PER_CHUNK, totalChars);
            String chunk = fullText.substring(start, end);

            StringBuilder result = new StringBuilder();
            result.append("📄 ").append(path.getFileName()).append("\n");
            result.append("   总字符: ").append(totalChars).append(" | 总段数: ").append(totalChunks)
                  .append(" | 当前段: ").append(chunkId).append("/").append(totalChunks - 1).append("\n");
            if (fileName.endsWith(".pdf")) {
                result.append("   (已从 PDF 自动提取文字)\n");
            }
            result.append("─── 段 ").append(chunkId).append(" ───\n");
            result.append(chunk);

            if (chunkId < totalChunks - 1) {
                result.append("\n\n💡 还有 ").append(totalChunks - chunkId - 1)
                      .append(" 段未读，用 chunk_id=").append(chunkId + 1).append(" 继续阅读。");
            } else {
                result.append("\n\n✅ 已读完。");
            }

            return result.toString();

        } catch (IOException e) {
            log.error("[ReadDocument] 读取失败: {}", e.getMessage());
            return "ERROR: 读取失败 — " + e.getMessage();
        }
    }

    /** PDFBox 提取全文 */
    private String extractPdfText(Path path) {
        try (var doc = Loader.loadPDF(path.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String text = stripper.getText(doc);
            log.info("[ReadDocument] PDF 提取完成: {} → {} chars", path.getFileName(), text.length());
            return text;
        } catch (IOException e) {
            log.error("[ReadDocument] PDF 解析失败: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public String getTextRecord() {
        return "调用了 read_document 阅读文档;";
    }
}
