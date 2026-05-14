package com.cna.workspace;

import com.cna.config.ConfigsManager;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

@Slf4j
public class WorkSpaceManager {

    private static volatile WorkSpaceManager instance;
    private final Path workspaceRoot;

    // 当前工作目录，相对于 workspaceRoot，"" 代表根目录
    // 单线程消费者模型下静态即可，无需 per-task
    private volatile String currentDir = "";

    private WorkSpaceManager() {
        this.workspaceRoot = Paths.get(ConfigsManager.WORKSPACE_DIR).toAbsolutePath().normalize();
        try {
            Files.createDirectories(workspaceRoot);
            log.info("[WorkSpace] 沙盒目录已就绪: {}", workspaceRoot);
        } catch (IOException e) {
            log.error("[WorkSpace] 无法创建工作目录: {}", workspaceRoot, e);
        }
    }

    public static WorkSpaceManager getInstance() {
        if (instance == null) {
            synchronized (WorkSpaceManager.class) {
                if (instance == null) instance = new WorkSpaceManager();
            }
        }
        return instance;
    }

    // ── 路径解析 ────────────────────────────────────────────────────────

    /**
     * 解析路径，规则：
     * - 以 "/" 开头 → 相对于 workspace 根目录（绝对路径）
     * - 否则 → 相对于当前工作目录 (cwd)
     * 返回 null 表示路径非法（穿越沙盒）。
     */
    public Path resolve(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) return null;

        Path base;
        String cleanPath;
        if (relativePath.startsWith("/")) {
            base = workspaceRoot;
            cleanPath = relativePath.substring(1);
        } else {
            base = currentDir.isEmpty() ? workspaceRoot : workspaceRoot.resolve(currentDir);
            cleanPath = relativePath;
        }

        Path candidate = base.resolve(cleanPath).normalize();
        if (!candidate.startsWith(workspaceRoot)) {
            log.warn("[WorkSpace] 路径穿越攻击已拦截: {}", relativePath);
            return null;
        }
        return candidate;
    }

    public Path getWorkspaceRoot() { return workspaceRoot; }

    public String getCurrentDir() {
        return currentDir.isEmpty() ? "/" : "/" + currentDir;
    }

    // ── 切换目录 ─────────────────────────────────────────────────────────

    /**
     * 切换当前工作目录。
     * 支持：相对路径、".."（上级）、"/"（回根目录）
     */
    public String cd(String path) {
        if (path == null || path.isBlank() || "/".equals(path.trim())) {
            currentDir = "";
            log.info("[WorkSpace] cd → /");
            return "SUCCESS: 已切换到工作区根目录 /";
        }

        String trimmed = path.trim();

        // 处理 ".." - 返回上级
        if ("..".equals(trimmed)) {
            if (currentDir.isEmpty()) return "SYSTEM_FEEDBACK: 已在根目录，无法继续向上。";
            int lastSlash = currentDir.lastIndexOf('/');
            currentDir = lastSlash <= 0 ? "" : currentDir.substring(0, lastSlash);
            log.info("[WorkSpace] cd .. → /{}", currentDir);
            return "SUCCESS: 已切换到 " + getCurrentDir();
        }

        Path target = resolve(trimmed);
        if (target == null) return "ERROR: 非法路径。";
        if (!Files.exists(target)) return "ERROR: 目录不存在: [" + trimmed + "]";
        if (!Files.isDirectory(target)) return "ERROR: 目标不是目录，无法 cd。";

        currentDir = workspaceRoot.relativize(target).toString().replace("\\", "/");
        log.info("[WorkSpace] cd → /{}", currentDir);
        return "SUCCESS: 已切换到 " + getCurrentDir();
    }

    // ── 当前目录信息（自动注入到 prompt）────────────────────────────────

    /**
     * 返回当前 cwd 路径 + 该目录下的文件列表。
     * 由 TaskHandler 每轮注入到 baseData，Bot 无需主动调用。
     */
    public String getCwdInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("【WorkSpace 当前目录】").append(getCurrentDir()).append("\n");

        Path dir = currentDir.isEmpty() ? workspaceRoot : workspaceRoot.resolve(currentDir);
        List<String> entries = new ArrayList<>();

        try {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
                for (Path entry : stream) {
                    String name = entry.getFileName().toString();
                    if (Files.isDirectory(entry)) {
                        entries.add("  [目录] " + name + "/");
                    } else {
                        entries.add("  [文件] " + name + "  (" + Files.size(entry) + " 字节)");
                    }
                }
            }
        } catch (IOException e) {
            return sb.append("（读取目录失败）").toString();
        }

        if (entries.isEmpty()) {
            sb.append("  （目录为空）");
        } else {
            entries.forEach(e -> sb.append(e).append("\n"));
        }
        return sb.toString().trim();
    }

    // ── 写入文件 ─────────────────────────────────────────────────────────

    /**
     * append=false：覆盖写入，单次上限 512KB
     * append=true ：追加到末尾，单次上限 512KB
     */
    public String write(String relativePath, String content, boolean append) {
        Path target = resolve(relativePath);
        if (target == null) return "ERROR: 非法路径，禁止写入沙盒外的目录。";
        if (content.length() > 512 * 1024) return "ERROR: 单次写入内容超过 512KB 限制，请分段写入。";
        try {
            Files.createDirectories(target.getParent());
            if (append) {
                Files.writeString(target, content, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                log.info("[WorkSpace] 追加写入: {}", target);
                return "SUCCESS: 内容已追加至 [" + relativePath + "]，本次 " + content.length() + " 字符。";
            } else {
                Files.writeString(target, content, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                log.info("[WorkSpace] 覆盖写入: {}", target);
                return "SUCCESS: 文件已写入 [" + relativePath + "]，共 " + content.length() + " 字符。";
            }
        } catch (IOException e) {
            log.error("[WorkSpace] 写入失败: {}", relativePath, e);
            return "ERROR: 文件写入失败 - " + e.getMessage();
        }
    }

    // ── 分段读取文件 ─────────────────────────────────────────────────────

    /**
     * 按行分段读取，每次最多 500 行，带行号。
     * offset 从 1 起算（第 1 行），limit <= 0 时取 500。
     */
    public String read(String relativePath, int offset, int limit) {
        Path target = resolve(relativePath);
        if (target == null) return "ERROR: 非法路径。";
        if (!Files.exists(target)) return "ERROR: 文件不存在: [" + relativePath + "]";
        if (Files.isDirectory(target)) return "ERROR: 目标是目录，请用 cd_workspace 进入后查看。";
        try {
            List<String> allLines = Files.readAllLines(target, StandardCharsets.UTF_8);
            int totalLines = allLines.size();

            int startIdx = Math.max(0, offset <= 0 ? 0 : offset - 1);
            int effectiveLimit = (limit <= 0) ? 500 : Math.min(limit, 500);
            int endIdx = Math.min(startIdx + effectiveLimit, totalLines);

            if (startIdx >= totalLines) {
                return "SYSTEM_FEEDBACK: offset " + offset + " 超出文件总行数 " + totalLines + "，无内容可读。";
            }

            List<String> slice = allLines.subList(startIdx, endIdx);
            boolean hasMore = endIdx < totalLines;

            StringBuilder sb = new StringBuilder();
            sb.append("【").append(relativePath).append("】");
            sb.append(" 第 ").append(startIdx + 1).append("~").append(endIdx).append(" 行");
            sb.append("（共 ").append(totalLines).append(" 行）\n");
            for (int i = 0; i < slice.size(); i++) {
                sb.append(String.format("%4d│%s%n", startIdx + 1 + i, slice.get(i)));
            }
            if (hasMore) {
                sb.append("... 还有 ").append(totalLines - endIdx)
                  .append(" 行未显示，可用 offset=").append(endIdx + 1).append(" 继续读取。");
            }
            return sb.toString();
        } catch (IOException e) {
            log.error("[WorkSpace] 读取失败: {}", relativePath, e);
            return "ERROR: 读取文件失败 - " + e.getMessage();
        }
    }

    // ── 列目录（保留为工具备用）─────────────────────────────────────────

    public String list(String relativePath) {
        Path target = (relativePath == null || relativePath.isBlank())
                ? workspaceRoot
                : resolve(relativePath);
        if (target == null) return "ERROR: 非法路径。";
        if (!Files.exists(target)) return "ERROR: 目录不存在: [" + relativePath + "]";
        if (!Files.isDirectory(target)) return "ERROR: 目标不是目录。";

        List<String> entries = new ArrayList<>();
        try {
            Files.walkFileTree(target, new SimpleFileVisitor<>() {
                int count = 0;
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (count++ >= 200) return FileVisitResult.TERMINATE;
                    String rel = workspaceRoot.relativize(file).toString().replace("\\", "/");
                    entries.add("  [文件] " + rel + "  (" + attrs.size() + " 字节)");
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (dir.equals(workspaceRoot)) return FileVisitResult.CONTINUE;
                    String rel = workspaceRoot.relativize(dir).toString().replace("\\", "/");
                    entries.add("  [目录] " + rel + "/");
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            return "ERROR: 列目录失败 - " + e.getMessage();
        }

        if (entries.isEmpty()) return "【工作区为空】根目录: " + workspaceRoot;
        return "【工作区文件列表】根目录: " + workspaceRoot + "\n"
                + String.join("\n", entries)
                + (entries.size() == 200 ? "\n...(超过 200 条已截断)" : "");
    }

    // ── 删除文件 ─────────────────────────────────────────────────────────

    public String delete(String relativePath) {
        Path target = resolve(relativePath);
        if (target == null) return "ERROR: 非法路径。";
        if (!Files.exists(target)) return "ERROR: 文件不存在: [" + relativePath + "]";
        if (Files.isDirectory(target)) return "ERROR: 不允许删除目录，请逐个删除其中的文件。";
        try {
            Files.delete(target);
            log.info("[WorkSpace] 删除文件: {}", target);
            return "SUCCESS: 文件 [" + relativePath + "] 已删除。";
        } catch (IOException e) {
            return "ERROR: 删除失败 - " + e.getMessage();
        }
    }
}
