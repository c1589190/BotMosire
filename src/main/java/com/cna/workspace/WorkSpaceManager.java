package com.cna.workspace;

import com.cna.config.ConfigsManager;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
public class WorkSpaceManager {

    private final Path workspaceRoot;
    private final Path backupRoot; // 新增：备份历史目录

    private String currentDir = "";

    private static final int READ_PAGE_SIZE = 150;
    private static final int READ_OVERLAP = 30;

    // 时间戳格式化，用于备份文件名
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS");

    public WorkSpaceManager() {
        this.workspaceRoot = Paths.get(ConfigsManager.WORKSPACE_DIR).toAbsolutePath().normalize();

        // 自动计算同级备份目录：如果是 ./a/b/workspace，则备份到 ./a/b/workspace_history
        Path parent = workspaceRoot.getParent();
        String backupDirName = workspaceRoot.getFileName().toString() + "_history";
        this.backupRoot = (parent == null ? Paths.get(backupDirName) : parent.resolve(backupDirName)).toAbsolutePath().normalize();

        try {
            Files.createDirectories(workspaceRoot);
            Files.createDirectories(backupRoot);
            log.info("[WorkSpace] 沙盒目录已就绪: {}", workspaceRoot);
            log.info("[WorkSpace] 备份目录已就绪: {}", backupRoot);
        } catch (IOException e) {
            log.error("[WorkSpace] 无法初始化工作或备份目录", e);
        }
    }

    // ── 核心辅助：自动备份原稿 (时光机) ──────────────────────────────────

    /**
     * 在文件被覆盖或删除前，自动将其备份到 history 目录，并保留原有目录结构。
     */
    private void backupFileBeforeModify(Path targetFile) {
        if (targetFile == null || !Files.exists(targetFile) || Files.isDirectory(targetFile)) {
            return; // 不存在的或是目录，不需要备份
        }

        try {
            // 计算相对于 workspace 的路径 (例如: src/main.java)
            Path relativePath = workspaceRoot.relativize(targetFile);

            // 构造备份文件名: main.java.20231025_143022_123.bak
            String timestamp = LocalDateTime.now().format(TIME_FORMATTER);
            String backupFileName = targetFile.getFileName().toString() + "." + timestamp + ".bak";

            // 计算在 backupRoot 中的目标路径
            Path backupDir = backupRoot.resolve(relativePath).getParent();
            if (backupDir == null) backupDir = backupRoot;

            Files.createDirectories(backupDir); // 确保备份的父目录存在
            Path backupPath = backupDir.resolve(backupFileName);

            // 执行复制备份
            Files.copy(targetFile, backupPath, StandardCopyOption.REPLACE_EXISTING);
            log.info("[WorkSpace] 触发自动备份: {} -> {}", targetFile.getFileName(), backupPath);

        } catch (Exception e) {
            // 备份失败不要阻断主流程，但要记录严重警告
            log.error("[WorkSpace] ⚠️ 文件自动备份失败，路径: {}", targetFile, e);
        }
    }

    // ── 路径解析 ──────────────────────────────────────────────

    public Path resolve(String relativeOrAbsolutePath) {
        if (relativeOrAbsolutePath == null || relativeOrAbsolutePath.isBlank()) return null;

        Path base;
        String cleanPath;
        if (relativeOrAbsolutePath.startsWith("/")) {
            base = workspaceRoot;
            cleanPath = relativeOrAbsolutePath.substring(1);
        } else {
            base = currentDir.isEmpty() ? workspaceRoot : workspaceRoot.resolve(currentDir);
            cleanPath = relativeOrAbsolutePath;
        }

        Path candidate = base.resolve(cleanPath).normalize();
        if (!candidate.startsWith(workspaceRoot)) {
            log.warn("[WorkSpace] 路径越权拦截: {}", relativeOrAbsolutePath);
            return null;
        }
        return candidate;
    }

    // ── 1. 切换目录 ──────────────────────────────────────────────

    public String cd(String path) {
        if (path == null || path.isBlank() || "/".equals(path.trim())) {
            currentDir = "";
            return "SUCCESS: 已切换到工作区根目录 -> " + getCurrentDir();
        }

        Path target = resolve(path.trim());
        if (target == null) return "ERROR: 非法路径或尝试越权访问沙盒外目录。";
        if (!Files.exists(target)) return "ERROR: 目录不存在: [" + path + "]";
        if (!Files.isDirectory(target)) return "ERROR: 目标不是目录，无法切换。";

        currentDir = workspaceRoot.relativize(target).toString().replace("\\", "/");
        return "SUCCESS: 已切换到 -> " + getCurrentDir();
    }

    // ── 2. 当前目录绝对路径 ────────────────────────────────────────

    public String getCurrentDir() {
        Path currentPath = currentDir.isEmpty() ? workspaceRoot : workspaceRoot.resolve(currentDir);
        return currentPath.toAbsolutePath().toString();
    }

    // ── 3. 列表 ls ──────────────────────────────────────────────

    public String ls() {
        Path dir = currentDir.isEmpty() ? workspaceRoot : workspaceRoot.resolve(currentDir);
        StringBuilder sb = new StringBuilder();
        sb.append("【当前目录】").append(getCurrentDir()).append("\n");

        List<String> folders = new ArrayList<>();
        List<String> files = new ArrayList<>();

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path entry : stream) {
                String name = entry.getFileName().toString();
                if (Files.isDirectory(entry)) {
                    folders.add("  [文件夹] " + name + "/");
                } else {
                    files.add(String.format("  [文件] %s  (%,d 字节)", name, Files.size(entry)));
                }
            }
        } catch (IOException e) {
            return sb.append("ERROR: 读取目录内容失败 - ").append(e.getMessage()).toString();
        }

        if (folders.isEmpty() && files.isEmpty()) {
            return sb.append("  (目录为空)").toString();
        }

        folders.sort(String::compareToIgnoreCase);
        files.sort(String::compareToIgnoreCase);

        folders.forEach(f -> sb.append(f).append("\n"));
        files.forEach(f -> sb.append(f).append("\n"));

        return sb.toString().trim();
    }

    // ── 4. 滑动窗口读取 ──────────────────────────────────────────────

    public String read(String relativePath, long id) {
        Path target = resolve(relativePath);
        if (target == null) return "ERROR: 非法路径。";
        if (!Files.exists(target)) return "ERROR: 文件不存在: [" + relativePath + "]";
        if (Files.isDirectory(target)) return "ERROR: 目标是目录，请使用 cd 进入后执行 ls。";

        long step = READ_PAGE_SIZE - READ_OVERLAP;
        long startLine = id * step;

        try (Stream<String> lines = Files.lines(target, StandardCharsets.UTF_8)) {
            String content = lines.skip(startLine)
                    .limit(READ_PAGE_SIZE)
                    .collect(Collectors.joining("\n"));

            if (content.isEmpty()) {
                return "EOF: 无内容或已读到文件末尾。";
            }

            long endLine = startLine + content.split("\n", -1).length;
            return String.format("【文件片段 %s | Chunk ID: %d | 行号: %d - %d】\n%s",
                    target.getFileName(), id, startLine + 1, endLine, content);

        } catch (IOException e) {
            return "ERROR: 读取文件失败 - " + e.getMessage();
        }
    }

    // ── 修改点：注入了备份逻辑的 Write 和 Delete ──────────────────────────────

    public String write(String relativePath, String content, boolean append) {
        Path target = resolve(relativePath);
        if (target == null) return "ERROR: 非法路径。";

        try {
            Files.createDirectories(target.getParent());

            if (append) {
                // 追加写入不会破坏原内容，通常不需要全量备份，直接追加即可
                Files.writeString(target, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                return "SUCCESS: 内容已追加至 [" + relativePath + "]";
            } else {
                // 【核心修改】：覆盖写入前，如果文件已存在，先进行备份！
                if (Files.exists(target)) {
                    backupFileBeforeModify(target);
                }
                Files.writeString(target, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                return "SUCCESS: 文件已覆盖写入并对原稿进行了历史备份 [" + relativePath + "]";
            }
        } catch (IOException e) {
            return "ERROR: 写入失败 - " + e.getMessage();
        }
    }

    public String delete(String relativePath) {
        Path target = resolve(relativePath);
        if (target == null) return "ERROR: 非法路径。";
        if (!Files.exists(target)) return "ERROR: 文件不存在。";

        try {
            if (Files.isDirectory(target)) {
                // 删除目录（目前不支持直接删除非空目录，防呆设计）
                Files.delete(target);
                return "SUCCESS: 已删除空目录 [" + relativePath + "]";
            } else {
                // 【核心修改】：删除文件前，先进行备份！
                backupFileBeforeModify(target);
                Files.delete(target);
                return "SUCCESS: 文件已删除，原稿已保存至历史备份 [" + relativePath + "]";
            }
        } catch (DirectoryNotEmptyException e) {
            return "ERROR: 目录非空，无法直接删除。";
        } catch (IOException e) {
            return "ERROR: 删除失败 - " + e.getMessage();
        }
    }

    // 在 WorkSpaceManager 中新增的初始化方法
    public void initWebsite() {
        Path websiteDir = workspaceRoot.resolve("website");
        Path indexFile = websiteDir.resolve("index.html");

        try {
            Files.createDirectories(websiteDir);
            if (!Files.exists(indexFile)) {
                // 写入一个默认的支持动态接收 LLM 指令的网页模板
                String defaultHtml = """
                    啥都木有
                    """;
                Files.writeString(indexFile, defaultHtml, StandardCharsets.UTF_8);
                log.info("[WorkSpace] 默认网站模板已创建: {}", indexFile);
            }
        } catch (IOException e) {
            log.error("[WorkSpace] 初始化 website 目录失败", e);
        }
    }
}