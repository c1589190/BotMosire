package com.cna.db;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class MDManager {

    private static final ConcurrentHashMap<String, String> contentCache = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Long> mtimeCache = new ConcurrentHashMap<>();

    public static String read(String fileName) {
        return read(fileName, "请输入文本");
    }

    public static String read(String fileName, String defaultContent) {
        try {
            Path path = Paths.get(fileName);
            if (Files.exists(path)) {
                long mtime = Files.getLastModifiedTime(path).toMillis();
                Long cachedMtime = mtimeCache.get(fileName);
                if (cachedMtime != null && cachedMtime == mtime && contentCache.containsKey(fileName)) {
                    return contentCache.get(fileName);
                }
                String content = Files.readString(path, StandardCharsets.UTF_8);
                contentCache.put(fileName, content);
                mtimeCache.put(fileName, mtime);
                return content;
            } else {
                if (defaultContent != null) {
                    log.info("MD文件 [{}] 不存在，正在自动生成并注入默认配置...", fileName);
                    write(fileName, defaultContent);
                    return defaultContent;
                } else {
                    log.warn("MD文件 [{}] 不存在，且未提供默认结构，返回空集。", fileName);
                    return "";
                }
            }
        } catch (Exception e) {
            log.error("读取 MD 文件 {} 发生物理异常", fileName, e);
            return defaultContent != null ? defaultContent : "";
        }
    }

    public static boolean write(String fileName, String content) {
        try {
            Path path = Paths.get(fileName);
            Files.writeString(path, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            contentCache.remove(fileName);
            mtimeCache.remove(fileName);
            return true;
        } catch (Exception e) {
            log.error("覆写 MD 文件 {} 失败", fileName, e);
            return false;
        }
    }

    /**
     * 向 Markdown 文件末尾追加内容
     * * @param fileName 目标文件名（如 "thoughts.md"）
     * @param content  要追加的具体内容
     * @return 是否成功
     */
    public static boolean append(String fileName, String content) {
        Path path = Paths.get(fileName);
        try {
            // StandardOpenOption.CREATE: 如果文件不存在，自动创建
            // StandardOpenOption.APPEND: 如果文件存在，在末尾追加
            Files.write(path,
                    content.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
            return true;
        } catch (IOException e) {
            log.error("[MDManager] 向文件 {} 追加内容时发生底层异常", fileName, e);
            return false;
        }
    }
}