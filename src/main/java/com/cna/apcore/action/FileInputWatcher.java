package com.cna.apcore.action;

import com.cna.apcore.config.CoreConfig;
import com.cna.apcore.model.CognitivePrepareUnit;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/**
 * 文件系统桌面巡视器 — Bot 的"眼睛扫过桌面"。
 *
 * 当认知准备池空闲（单元数低于阈值）时，每隔若干 tick 自动注入一次
 * 当前工作目录的快照，让 Bot 自己决定：
 * - 有什么文件值得读
 * - 要不要 cd 到子目录探索
 * - 哪些文件之前看过但内容变了
 *
 * Bot 通过现有的工具链自主探索：
 *   cd_workspace → 切换当前目录
 *   list_files → 查看任意目录
 *   read_file → 分段阅读（chunk_id 递增）
 *   write_file → 写笔记/总结
 */
@Slf4j
public class FileInputWatcher {

    private final Path rootDir;
    private final double baseSE;
    private final int surveyIntervalTicks;   // 每隔多少 tick 巡视一次
    private final int idlePoolThreshold;     // 池大小低于此值才巡视（空闲判断）

    private int ticksSinceLastSurvey = 0;
    private String currentDir;               // Bot 当前工作目录（与 CdWorkspace 同步）

    public FileInputWatcher() {
        this.rootDir = Paths.get("").toAbsolutePath().normalize();
        this.baseSE = CoreConfig.FILE_INPUT_BASE_SE;
        this.surveyIntervalTicks = getSurveyInterval();
        this.idlePoolThreshold = getIdleThreshold();
        this.currentDir = rootDir.toString();

        log.info("[FileWatch] 桌面巡视器初始化: root={}, interval={}ticks, idleThreshold={}",
                rootDir, surveyIntervalTicks, idlePoolThreshold);
    }

    /**
     * 每个 tick 调用。当池空闲时，每隔 surveyIntervalTicks 注入一次目录快照。
     *
     * @param poolSize 当前准备池大小
     * @return 巡视发现的认知单元（可能为空）
     */
    public List<CognitivePrepareUnit> survey(int poolSize) {
        ticksSinceLastSurvey++;

        // 池不够空闲 → 不巡视，避免干扰正在处理的外部输入
        if (poolSize > idlePoolThreshold) {
            return List.of();
        }

        // 间隔不够 → 跳过
        if (ticksSinceLastSurvey < surveyIntervalTicks) {
            return List.of();
        }

        ticksSinceLastSurvey = 0;

        Path dir = resolveCurrentDir();
        if (dir == null || !Files.isDirectory(dir)) {
            log.debug("[FileWatch] 当前目录不可访问: {}", currentDir);
            return List.of();
        }

        // 构建目录快照
        String snapshot = buildDirectorySnapshot(dir);
        if (snapshot == null) return List.of();

        CognitivePrepareUnit unit = CognitivePrepareUnit.create(
                snapshot, List.of("system:desktop_survey"), baseSE);
        unit.setEndogenous(true); // 内源——由系统巡视触发，非外部输入

        log.info("[FileWatch] 🔍 桌面巡视: {} ({} 项)", dir.getFileName(),
                snapshot.lines().count());
        return List.of(unit);
    }

    // ==========================================
    // 内部
    // ==========================================

    private Path resolveCurrentDir() {
        // 直接读 JVM 当前工作目录，CdWorkspace/Main.workspaceManager 改过之后这里自动跟随
        String userDir = System.getProperty("user.dir");
        Path dir = Paths.get(userDir).toAbsolutePath().normalize();
        if (Files.exists(dir)) {
            this.currentDir = dir.toString();
            return dir;
        }
        // 回退到根目录
        this.currentDir = rootDir.toString();
        return rootDir;
    }

    /**
     * 构建当前目录的快照文本。
     * 列出文件/子目录，标注大小、类型、相对于根目录的路径。
     */
    private String buildDirectorySnapshot(Path dir) {
        try {
            StringBuilder sb = new StringBuilder();
            String relPath = rootDir.relativize(dir).toString();
            if (relPath.isEmpty()) relPath = "/";

            sb.append("📂 当前目录: ").append(relPath).append("\n");
            sb.append("   绝对路径: ").append(dir.toString()).append("\n\n");

            List<Path> subdirs = new ArrayList<>();
            List<Path> files = new ArrayList<>();

            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
                for (Path p : stream) {
                    if (Files.isDirectory(p)) subdirs.add(p);
                    else files.add(p);
                }
            }

            // 排序
            subdirs.sort(Comparator.comparing(p -> p.getFileName().toString().toLowerCase()));
            files.sort(Comparator.comparing(p -> p.getFileName().toString().toLowerCase()));

            // 子目录
            if (!subdirs.isEmpty()) {
                sb.append("📁 子目录 (").append(subdirs.size()).append("):\n");
                for (Path d : subdirs) {
                    sb.append("   📁 ").append(d.getFileName()).append("/\n");
                }
                sb.append("\n");
            }

            // 文件
            if (!files.isEmpty()) {
                sb.append("📄 文件 (").append(files.size()).append("):\n");
                for (Path f : files) {
                    long size = Files.size(f);
                    String type = guessFileType(f);
                    String sizeStr = formatSize(size);
                    sb.append(String.format("   %s %-30s %s\n", type, f.getFileName().toString(), sizeStr));
                }
                sb.append("\n");
            }

            if (subdirs.isEmpty() && files.isEmpty()) {
                sb.append("   (空目录)\n\n");
            }

            sb.append("💡 你可以用 cd_workspace 切换目录，用 read_file 分段阅读文件。\n");
            sb.append("   空闲时巡视桌面、阅读感兴趣的内容，是积累知识的好机会。\n");

            return sb.toString();
        } catch (IOException e) {
            log.warn("[FileWatch] 构建目录快照失败: {}", e.getMessage());
            return null;
        }
    }

    private String guessFileType(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        if (name.endsWith(".txt"))  return "📝";
        if (name.endsWith(".md"))   return "📋";
        if (name.endsWith(".log"))  return "📜";
        if (name.endsWith(".java")) return "☕";
        if (name.endsWith(".py"))   return "🐍";
        if (name.endsWith(".js") || name.endsWith(".ts")) return "🟨";
        if (name.endsWith(".json")) return "📊";
        if (name.endsWith(".xml") || name.endsWith(".yml") || name.endsWith(".yaml")) return "⚙️";
        if (name.endsWith(".properties")) return "🔧";
        if (name.endsWith(".xml") || name.endsWith(".html") || name.endsWith(".ftl")) return "🌐";
        if (name.endsWith(".csv"))  return "📈";
        if (name.endsWith(".pdf"))  return "📕";
        if (name.endsWith(".zip") || name.endsWith(".jar") || name.endsWith(".tar") || name.endsWith(".gz")) return "📦";
        if (name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".gif")) return "🖼️";
        if (name.endsWith(".sh") || name.endsWith(".bat")) return "💻";
        return "📄";
    }

    private String formatSize(long bytes) {
        if (bytes > 1_000_000) return String.format("%.1f MB", bytes / 1_000_000.0);
        if (bytes > 1_000) return String.format("%.1f KB", bytes / 1_000.0);
        return bytes + " B";
    }

    private int getSurveyInterval() {
        return CoreConfig.FILE_SURVEY_INTERVAL_TICKS;
    }

    private int getIdleThreshold() {
        return CoreConfig.FILE_IDLE_POOL_THRESHOLD;
    }
}
