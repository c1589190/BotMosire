package com.cna.db;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@Slf4j
@Deprecated
public class MDManager {

    // 异步写入专用单线程，保证串行写入，避免并发写文件导致内容交错或丢失
    private static final ExecutorService MD_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "MDManager-Writer");
        t.setDaemon(true);
        return t;
    });

    // 读写锁：读缓存/读文件持读锁；写文件+更新缓存持写锁
    private static final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();

    private static final ConcurrentHashMap<String, String> contentCache = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Long> mtimeCache = new ConcurrentHashMap<>();

    // ==================== 读操作（同步，带缓存） ====================

    public static String read(String fileName) {
        return read(fileName, "请输入文本");
    }

    public static String read(String fileName, String defaultContent) {
        rwLock.readLock().lock();
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
                    // 文件不存在，创建默认文件（降级为写锁）
                    rwLock.readLock().unlock();
                    rwLock.writeLock().lock();
                    try {
                        // 双重检查
                        if (!Files.exists(path)) {
                            log.info("MD文件 [{}] 不存在，正在自动生成并注入默认配置...", fileName);
                            Files.writeString(path, defaultContent, StandardCharsets.UTF_8,
                                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                            contentCache.put(fileName, defaultContent);
                            mtimeCache.put(fileName, Files.getLastModifiedTime(path).toMillis());
                        }
                        return defaultContent;
                    } finally {
                        rwLock.writeLock().unlock();
                        rwLock.readLock().lock();
                    }
                } else {
                    log.warn("MD文件 [{}] 不存在，且未提供默认结构，返回空集。", fileName);
                    return "";
                }
            }
        } catch (Exception e) {
            log.error("读取 MD 文件 {} 发生物理异常", fileName, e);
            return defaultContent != null ? defaultContent : "";
        } finally {
            if (rwLock.getReadHoldCount() > 0) {
                rwLock.readLock().unlock();
            }
        }
    }

    // ==================== 同步写操作（等待写入完成，返回结果） ====================

    /**
     * 同步覆写文件。调用线程会阻塞直到写入完成。
     * 适用于必须等待写入结果再继续的场景（如 ReflectiveCompactionTool）。
     */
    public static boolean write(String fileName, String content) {
        try {
            writeAsync(fileName, content).get();
            return true;
        } catch (Exception e) {
            log.error("同步覆写 MD 文件 {} 失败", fileName, e);
            return false;
        }
    }

    /**
     * 同步追加文件。调用线程会阻塞直到写入完成。
     */
    public static boolean append(String fileName, String content) {
        try {
            appendAsync(fileName, content).get();
            return true;
        } catch (Exception e) {
            log.error("同步追加 MD 文件 {} 失败", fileName, e);
            return false;
        }
    }

    // ==================== 异步写操作（提交到队列，立即返回 Future） ====================

    /**
     * 异步覆写文件。提交到单线程写入队列，立即返回 CompletableFuture。
     * 适用于不关心写入结果的场景（如 FinishTask 追加 summary）。
     */
    public static CompletableFuture<Boolean> writeAsync(String fileName, String content) {
        return CompletableFuture.supplyAsync(() -> {
            rwLock.writeLock().lock();
            try {
                Path path = Paths.get(fileName);
                Files.writeString(path, content, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                contentCache.remove(fileName);
                mtimeCache.remove(fileName);
                return true;
            } catch (IOException e) {
                log.error("[MDManager] 异步覆写文件 {} 失败", fileName, e);
                return false;
            } finally {
                rwLock.writeLock().unlock();
            }
        }, MD_EXECUTOR);
    }

    /**
     * 异步追加文件。提交到单线程写入队列，立即返回 CompletableFuture。
     * 适用于不关心写入结果的场景（如 FinishTask 追加 summary）。
     */
    public static CompletableFuture<Boolean> appendAsync(String fileName, String content) {
        return CompletableFuture.supplyAsync(() -> {
            rwLock.writeLock().lock();
            try {
                Path path = Paths.get(fileName);
                Files.write(path,
                        content.getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
                // 追加后缓存失效，下次 read 会重新加载
                contentCache.remove(fileName);
                mtimeCache.remove(fileName);
                return true;
            } catch (IOException e) {
                log.error("[MDManager] 异步追加文件 {} 失败", fileName, e);
                return false;
            } finally {
                rwLock.writeLock().unlock();
            }
        }, MD_EXECUTOR);
    }
}