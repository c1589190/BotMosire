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

    /**
     * 解析用户传入的相对路径，确保它在沙盒内。
     * 返回 null 表示路径不合法（路径穿越攻击）。
     */
    public Path resolve(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) return null;
        // 拒绝绝对路径
        Path candidate = workspaceRoot.resolve(relativePath).normalize();
        if (!candidate.startsWith(workspaceRoot)) {
            log.warn("[WorkSpace] 路径穿越攻击已拦截: {}", relativePath);
            return null;
        }
        return candidate;
    }

    public Path getWorkspaceRoot() {
        return workspaceRoot;
    }

    /**
     * 写入文件（自动创建父目录）
     * append=false：覆盖写入，单次内容限 512KB
     * append=true：追加到末尾，单次内容限 512KB
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
                log.info("[WorkSpace] 追加写入文件: {}", target);
                return "SUCCESS: 内容已追加至 [" + relativePath + "]，本次追加 " + content.length() + " 字符。";
            } else {
                Files.writeString(target, content, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                log.info("[WorkSpace] 覆盖写入文件: {}", target);
                return "SUCCESS: 文件已写入 [" + relativePath + "]，共 " + content.length() + " 字符。";
            }
        } catch (IOException e) {
            log.error("[WorkSpace] 写入失败: {}", relativePath, e);
            return "ERROR: 文件写入失败 - " + e.getMessage();
        }
    }

    /**
     * 分段读取文件。
     * offset=0, limit=-1 时读取全部内容（仍有 500 行安全上限）。
     * offset 和 limit 均以「行号」为单位（从第 1 行起）。
     */
    public String read(String relativePath, int offset, int limit) {
        Path target = resolve(relativePath);
        if (target == null) return "ERROR: 非法路径。";
        if (!Files.exists(target)) return "ERROR: 文件不存在: [" + relativePath + "]";
        if (Files.isDirectory(target)) return "ERROR: 目标是目录，请使用 list_workspace 列出内容。";
        try {
            List<String> allLines = Files.readAllLines(target, StandardCharsets.UTF_8);
            int totalLines = allLines.size();

            // offset 从 1 开始（人类习惯），转为 0-based index
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

    /** 列出目录内容（最多 200 条），relativePath 为空时列出根目录 */
    public String list(String relativePath) {
        Path target = relativePath == null || relativePath.isBlank()
                ? workspaceRoot
                : resolve(relativePath);
        if (target == null) return "ERROR: 非法路径。";
        if (!Files.exists(target)) return "ERROR: 目录不存在: [" + relativePath + "]";
        if (!Files.isDirectory(target)) return "ERROR: 目标不是目录，请使用 read_workspace 读取文件。";

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

        if (entries.isEmpty()) return "【工作区为空】工作区根目录: " + workspaceRoot;
        String header = "【工作区文件列表】根目录: " + workspaceRoot + "\n";
        return header + String.join("\n", entries) + (entries.size() == 200 ? "\n...(超过 200 条已截断)" : "");
    }

    /** 删除文件（不允许删除目录） */
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
