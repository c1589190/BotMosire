package com.cna;

import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 虚拟文件链接注册表 — 桥接 LLM 可见的 napcat:// 链接与 Napcat API 参数。
 *
 * NapcatAdapter 收到文件消息时，提取 file_id/busid 等信息并调用 register()。
 * DownloadChatFile 工具通过 lookup() 获取真实下载参数，调用 Napcat API 下载。
 */
@Slf4j
public class NapcatFileLinkRegistry {

    /** 虚拟链接记录 */
    public record FileLinkInfo(
            String virtualLink,
            String fileId,
            int busid,
            String fileName,
            long fileSize
    ) {}

    private static final Map<String, FileLinkInfo> registry = new ConcurrentHashMap<>();

    /** 注册一个虚拟链接 */
    public static void register(String virtualLink, String fileId, int busid,
                                 String fileName, long fileSize) {
        FileLinkInfo info = new FileLinkInfo(virtualLink, fileId, busid, fileName, fileSize);
        registry.put(virtualLink, info);
        log.info("[FileLink] 注册: {} → fileId={}, busid={}", virtualLink, fileId, busid);
    }

    /** 按虚拟链接查找 */
    public static FileLinkInfo lookup(String virtualLink) {
        return registry.get(virtualLink);
    }

    /** 按 fileId 模糊查找（LLM 可能只传了 fileId 而非完整链接） */
    public static FileLinkInfo lookupByFileId(String fileId) {
        return registry.values().stream()
                .filter(info -> info.fileId.equals(fileId))
                .findFirst().orElse(null);
    }

    /** 清理过期记录（可定期调用） */
    public static void evictOlderThan(long maxAgeMs) {
        // 当前简单实现：仅保留最近 100 条
        if (registry.size() > 100) {
            var it = registry.entrySet().iterator();
            int toRemove = registry.size() - 50;
            for (int i = 0; i < toRemove && it.hasNext(); i++) {
                it.next();
                it.remove();
            }
            log.info("[FileLink] 清理 {} 条过期记录，剩余 {}", toRemove, registry.size());
        }
    }

    public static int size() {
        return registry.size();
    }
}
