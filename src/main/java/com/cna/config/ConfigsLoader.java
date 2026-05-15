package com.cna.config;

import lombok.extern.slf4j.Slf4j;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
public class ConfigsLoader {

    private static final Map<String, String> FILE_MAP = new LinkedHashMap<>();

    static {
        // 1. 基础配置文件 (模板 -> 实际配置)
        FILE_MAP.put("/application-template.properties", "application.properties");
        FILE_MAP.put("/PromptScenesPath.properties", "PromptScenesPath.properties");
        //FILE_MAP.put("/logback.xml", "logback.xml");

        // 2. LivingLoop 认知循环相关 Prompts
        String promptDir = "/prompts/";
        String[] promptFiles = {
                "CORE.md",
                "console_chat_task_solving.md",
                "console_chat_task_thinking.md",
                "getInterest.md",
                "scheduled_task_solving.md",
                "update_thoughts_task_solving.md",
                "chat_task_thinking.md",
                "chat_task_solving.md",
                "ToolPrompts.properties",
                "GetDeepMemories.md",
                "WebEventTaskSolving.md",
                "update_web_ui.md",
                "toolsGuide.md"
        };

        for (String fileName : promptFiles) {
            FILE_MAP.put(promptDir + fileName, "prompts/" + fileName);
        }
    }

    /**
     * 释放所有必要的资源文件到程序运行目录
     */
    public static void loadAll() {
        log.info("[ConfigsLoader] 正在检查 BotMosire 外部运行环境...");
        int releaseCount = 0;

        for (Map.Entry<String, String> entry : FILE_MAP.entrySet()) {
            String resourcePath = entry.getKey();
            Path targetPath = Paths.get(entry.getValue());

            // 仅在文件不存在时释放，避免覆盖用户的本地修改
            if (Files.notExists(targetPath)) {
                if (releaseResource(resourcePath, targetPath)) {
                    releaseCount++;
                }
            }
        }

        if (releaseCount > 0) {
            log.info("[ConfigsLoader] 环境初始化完毕，新增了 {} 个资源文件。", releaseCount);
        } else {
            log.info("[ConfigsLoader] 运行环境检查完毕，所有配置已就绪。");
        }
    }

    private static boolean releaseResource(String resourcePath, Path targetPath) {
        try (InputStream in = ConfigsLoader.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                log.error("[ConfigsLoader] ❌ 资源遗失: {}", resourcePath);
                return false;
            }

            // 自动创建 prompts 文件夹等父级目录
            if (targetPath.getParent() != null) {
                Files.createDirectories(targetPath.getParent());
            }

            Files.copy(in, targetPath, StandardCopyOption.REPLACE_EXISTING);
            log.info("[ConfigsLoader] 已释放: {}", targetPath.getFileName());
            return true;
        } catch (IOException e) {
            log.error("[ConfigsLoader] 释放资源 {} 失败: {}", resourcePath, e.getMessage());
            return false;
        }
    }
}