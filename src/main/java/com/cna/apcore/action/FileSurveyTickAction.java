package com.cna.apcore.action;

import com.cna.apcore.config.CoreConfig;
import com.cna.apcore.model.CognitivePrepareUnit;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 文件系统巡视 TickAction — Bot 的"眼睛扫过桌面"。
 *
 * 当认知准备池空闲（单元数低于阈值）时，每隔若干 tick 自动注入一次
 * 当前工作目录的快照，让 Bot 自己决定：
 * - 有什么文件值得读
 * - 要不要 cd 到子目录探索
 * - 哪些文件之前看过但内容变了
 *
 * 此功能从旧 FileInputWatcher 迁移而来，现作为 TickAction 的一个实现，
 * 由 TickActionManager 统一调度。
 */
@Slf4j
public class FileSurveyTickAction implements TickAction {

    private static final String ACTION_TYPE = "file_survey";

    private final Path rootDir;
    private final double baseSE;
    private final int surveyIntervalTicks;
    private final int idlePoolThreshold;

    public FileSurveyTickAction() {
        this.rootDir = Paths.get("").toAbsolutePath().normalize();
        this.baseSE = CoreConfig.FILE_INPUT_BASE_SE;
        this.surveyIntervalTicks = CoreConfig.FILE_SURVEY_INTERVAL_TICKS;
        this.idlePoolThreshold = CoreConfig.FILE_IDLE_POOL_THRESHOLD;

        log.info("[TickAction:{}] 初始化: root={}, interval={}ticks, idleThreshold={}",
                ACTION_TYPE, rootDir, surveyIntervalTicks, idlePoolThreshold);
    }

    @Override
    public String getActionType() {
        return ACTION_TYPE;
    }

    @Override
    public int getIntervalTicks() {
        return surveyIntervalTicks;
    }

    /**
     * 仅在池空闲时触发——避免在外部消息密集时插入桌面巡视干扰处理节奏。
     */
    @Override
    public boolean isReady(int poolSize, int currentTick) {
        return poolSize <= idlePoolThreshold;
    }

    @Override
    public CognitivePrepareUnit generate(int currentTick) {
        Path dir = resolveCurrentDir();
        if (dir == null || !Files.isDirectory(dir)) {
            log.debug("[TickAction:{}] 当前目录不可访问: {}",
                    ACTION_TYPE, System.getProperty("user.dir"));
            return null;
        }

        String snapshot = buildDirectorySnapshot(dir);
        if (snapshot == null) return null;

        CognitivePrepareUnit unit = CognitivePrepareUnit.create(
                snapshot, List.of(TICK_SOURCE_ID), baseSE);
        unit.setEndogenous(true);

        log.info("[TickAction:{}] 🔍 桌面巡视: {} ({} 行)", ACTION_TYPE,
                dir.getFileName(), snapshot.lines().count());
        return unit;
    }

    // ==========================================
    // 内部（迁移自 FileInputWatcher）
    // ==========================================

    private Path resolveCurrentDir() {
        String userDir = System.getProperty("user.dir");
        Path dir = Paths.get(userDir).toAbsolutePath().normalize();
        if (Files.exists(dir)) {
            return dir;
        }
        return rootDir;
    }

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

            subdirs.sort(Comparator.comparing(p -> p.getFileName().toString().toLowerCase()));
            files.sort(Comparator.comparing(p -> p.getFileName().toString().toLowerCase()));

            if (!subdirs.isEmpty()) {
                sb.append("📁 子目录 (").append(subdirs.size()).append("):\n");
                for (Path d : subdirs) {
                    sb.append("   📁 ").append(d.getFileName()).append("/\n");
                }
                sb.append("\n");
            }

            if (!files.isEmpty()) {
                sb.append("📄 文件 (").append(files.size()).append("):\n");
                for (Path f : files) {
                    long size = Files.size(f);
                    String type = guessFileType(f);
                    String sizeStr = formatSize(size);
                    sb.append(String.format("   %s %-30s %s\n",
                            type, f.getFileName().toString(), sizeStr));
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
            log.warn("[TickAction:{}] 构建目录快照失败: {}", ACTION_TYPE, e.getMessage());
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
        if (name.endsWith(".html") || name.endsWith(".ftl")) return "🌐";
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
}
