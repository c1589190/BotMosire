package com.cna.agent.AgentTool.io;

import com.cna.NapcatFileLinkRegistry;
import com.cna.agent.AgentTool.DefaultAgentToolUnit;
import com.cna.config.ConfigsManager;
import com.cna.config.ToolPromptsManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.time.Duration;

/**
 * 通用文件下载工具。
 *
 * 支持：
 * - http/https 直链（真实 URL）
 * - napcat://file/{fileId}/{name} 虚拟链接（通过 NapcatFileLinkRegistry 解析）
 *
 * 下载后保存到 workspace/downloads/ 目录，返回本地路径供 read_file 等工具使用。
 */
@Slf4j
public class DownloadFile implements DefaultAgentToolUnit {

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final Path DOWNLOAD_DIR = Paths.get("workspace/downloads").toAbsolutePath();

    static {
        try { Files.createDirectories(DOWNLOAD_DIR); } catch (IOException ignored) {}
    }

    @Override
    public String getName() {
        return "download_file";
    }

    @Override
    public ObjectNode getToolDefinition() {
        ObjectNode tool = mapper.createObjectNode();
        tool.put("type", "function");

        ToolPromptsManager p = new ToolPromptsManager(this.getClass().getSimpleName());

        ObjectNode fn = tool.putObject("function");
        fn.put("name", getName());
        fn.put("description", "下载文件到本地工作区。支持普通 URL (http/https) 和 QQ 文件虚拟链接 (napcat://file/...)。下载后文件保存在 workspace/downloads/ 目录，你可以用 read_file 工具阅读。");

        ObjectNode props = fn.putObject("parameters").put("type", "object").putObject("properties");

        props.putObject("url")
                .put("type", "string")
                .put("description", "要下载的文件链接。可以是真实的 http/https URL，也可以是消息中附带的 napcat://file/... 虚拟链接。");

        props.putObject("save_as")
                .put("type", "string")
                .put("description", "（可选）保存的文件名。不填则从链接中自动提取。");

        fn.with("parameters").putArray("required").add("url");
        return tool;
    }

    @Override
    public String execute(JsonNode arguments) {
        String url = arguments.path("url").asText("").trim();
        String saveAs = arguments.path("save_as").asText("").trim();

        if (url.isBlank()) {
            return "ERROR: url 参数不能为空。";
        }

        try {
            // ── 解析真实下载 URL ──
            String realUrl;
            String fileName;

            if (url.startsWith("napcat://")) {
                // 虚拟链接：从注册表查找 → 调用 Napcat API 获取真实 URL
                NapcatFileLinkRegistry.FileLinkInfo info = NapcatFileLinkRegistry.lookup(url);
                if (info == null) {
                    // 尝试用 fileId 模糊查找
                    String fileId = extractFileId(url);
                    info = NapcatFileLinkRegistry.lookupByFileId(fileId);
                }
                if (info == null) {
                    return "ERROR: 找不到该虚拟链接对应的文件信息: " + url
                            + "\n可能原因：链接已过期或文件消息在更早之前收到。";
                }
                realUrl = resolveNapcatUrl(info);
                if (realUrl == null) {
                    return "ERROR: 无法通过 Napcat API 获取文件下载地址。fileId=" + info.fileId();
                }
                fileName = !saveAs.isBlank() ? saveAs : sanitizeFileName(info.fileName());
                log.info("[DownloadFile] 解析 Napcat 链接: {} → {}", url, realUrl);
            } else {
                // 真实 URL：直接下载
                realUrl = url;
                fileName = !saveAs.isBlank() ? saveAs : extractFileNameFromUrl(url);
            }

            // ── 下载 ──
            Path dest = DOWNLOAD_DIR.resolve(fileName);
            long downloaded = downloadToFile(realUrl, dest);

            String sizeStr = downloaded > 1_000_000
                    ? String.format("%.1f MB", downloaded / 1_000_000.0)
                    : downloaded > 1_000
                    ? String.format("%.1f KB", downloaded / 1_000.0)
                    : downloaded + " B";

            log.info("[DownloadFile] ✅ 下载完成: {} ({})", dest, sizeStr);
            String hint = fileName.toLowerCase().endsWith(".pdf")
                    ? "你可以用 read_document 工具阅读此 PDF（自动提取文字）。"
                    : "你可以用 read_file 或 read_document 工具阅读此文件。";
            return "SUCCESS: 文件已下载到 " + dest.toString() + " (" + sizeStr + ")\n" + hint;

        } catch (IOException e) {
            log.error("[DownloadFile] 下载失败: {}", e.getMessage());
            return "ERROR: 下载失败 — " + e.getMessage();
        } catch (Exception e) {
            log.error("[DownloadFile] 未知错误", e);
            return "ERROR: " + e.getClass().getSimpleName() + " — " + e.getMessage();
        }
    }

    /** 调用 Napcat API 获取文件真实下载 URL */
    private String resolveNapcatUrl(NapcatFileLinkRegistry.FileLinkInfo info) {
        try {
            String apiBase = ConfigsManager.NAPCAT_HTTP_URL;
            String token = ConfigsManager.NAPCAT_TOEKN;

            ObjectNode params = mapper.createObjectNode();
            params.put("file_id", info.fileId());
            if (info.busid() > 0) {
                params.put("busid", info.busid());
            }

            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(apiBase + "/get_file"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json");
            if (token != null && !token.isBlank()) {
                reqBuilder.header("Authorization", "Bearer " + token);
            }
            reqBuilder.POST(HttpRequest.BodyPublishers.ofString(params.toString()));

            HttpResponse<String> resp = httpClient.send(reqBuilder.build(),
                    HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() != 200) {
                log.warn("[DownloadFile] Napcat /get_file 返回 {}", resp.statusCode());
                return null;
            }

            JsonNode data = mapper.readTree(resp.body()).path("data");
            String fileUrl = data.path("url").asText("");
            if (fileUrl.isBlank()) fileUrl = data.path("file").asText("");
            if (fileUrl.isBlank()) {
                String base64 = data.path("base64").asText("");
                if (!base64.isBlank()) return "data:application/octet-stream;base64," + base64;
            }
            return fileUrl.isBlank() ? null : fileUrl;

        } catch (Exception e) {
            log.warn("[DownloadFile] Napcat API 解析失败: {}", e.getMessage());
            return null;
        }
    }

    /** HTTP 下载到本地文件 */
    private long downloadToFile(String url, Path dest) throws IOException {
        // 处理 base64 data URL
        if (url.startsWith("data:")) {
            int comma = url.indexOf(',');
            if (comma > 0) {
                byte[] bytes = java.util.Base64.getDecoder().decode(url.substring(comma + 1));
                Files.write(dest, bytes);
                return bytes.length;
            }
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();

        try {
            HttpResponse<Path> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofFile(dest));
            if (response.statusCode() >= 200 && response.statusCode() < 400) {
                return Files.size(dest);
            } else {
                Files.deleteIfExists(dest);
                throw new IOException("HTTP " + response.statusCode());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("下载被中断");
        }
    }

    private String extractFileId(String napcatUrl) {
        // napcat://file/{fileId}/{name} → fileId
        String[] parts = napcatUrl.replace("napcat://file/", "").split("/");
        return parts.length > 0 ? parts[0] : "";
    }

    private String extractFileNameFromUrl(String url) {
        try {
            String path = URI.create(url).getPath();
            if (path != null && path.contains("/")) {
                String name = path.substring(path.lastIndexOf('/') + 1);
                if (!name.isBlank()) return sanitizeFileName(name);
            }
        } catch (Exception ignored) {}
        return "download_" + System.currentTimeMillis();
    }

    private String sanitizeFileName(String name) {
        return name.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    @Override
    public String getTextRecord() {
        return "调用了 download_file 下载文件;";
    }
}
