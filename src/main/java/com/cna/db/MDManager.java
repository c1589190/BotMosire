package com.cna.db;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

@Slf4j
public class MDManager {
    // 存放 Prompt 的物理目录


    static {
        // 确保目录物理存在

    }

    /**
     * 查 (读)：无默认值的传统调用
     * 如果文件不存在，直接返回空字符串
     */
    public static String read(String fileName) {
        return read(fileName, "请输入文本");
    }

    /**
     * 查 (读) + 自动初始化：支持传入默认内容
     * @param fileName 文件名
     * @param defaultContent 默认物理规则。如果文件不存在，将自动生成文件并写入此内容。
     */
    public static String read(String fileName, String defaultContent) {
        try {
            Path path = Paths.get(fileName);
            if (Files.exists(path)) {
                // 文件存在，直接读取物理数据
                return Files.readString(path, StandardCharsets.UTF_8);
            } else {
                // 文件不存在
                if (defaultContent != null || defaultContent.equals(" ")) {
                    log.info("MD文件 [{}] 不存在，正在物理层自动生成并注入默认配置...", fileName);
                    // 复用下方的 write 方法创建文件
                    write(fileName, defaultContent);
                    return defaultContent;
                } else {
                    log.warn("MD文件 [{}] 不存在，且未提供默认结构，返回空集。", fileName);
                    return "";
                }
            }
        } catch (Exception e) {
            log.error("读取 MD 文件 {} 发生物理异常", fileName, e);
            // 发生异常时的物理兜底：如果有默认内容就退回默认内容，否则给空串
            return defaultContent != null ? defaultContent : "";
        }
    }

    /**
     * 增/改 (写)：覆写 Prompt 内容（Bot 修改自身思想的物理接口）
     */
    public static boolean write(String fileName, String content) {
        try {
            Path path = Paths.get(fileName);
            Files.writeString(path, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
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