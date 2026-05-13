package com.cna.config;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

@Slf4j
public class ToolPromptsManager {

    // 静态缓存，存储所有场景的提示词
    private static final Properties PROMPTS_PATH_CACHE = new Properties();
    private static final String CONFIG_FILE_NAME = "prompts/ToolPrompts.properties";

    // 静态初始化块：只在类加载时读取一次配置文件
    static {
        File configFile = new File(System.getProperty("user.dir"), CONFIG_FILE_NAME);
        if (configFile.exists()) {
            // 💡 强烈建议加上 InputStreamReader 并指定 UTF-8，防止读取中文 properties 乱码！
            try (FileInputStream fis = new FileInputStream(configFile);
                 InputStreamReader isr = new InputStreamReader(fis, StandardCharsets.UTF_8)) {

                PROMPTS_PATH_CACHE.load(isr);
                log.info("[ToolPromptsManager] 成功加载提示词配置: {}", configFile.getAbsolutePath());
            } catch (IOException e) {
                log.error("[ToolPromptsManager] 加载提示词配置失败!", e);
            }
        } else {
            log.warn("[ToolPromptsManager] 配置文件不存在，请确保根目录包含: {}", configFile.getAbsolutePath());
        }
    }

    String ToolName;

    @Getter
    String ToolDescription;

    String simpleClassName;

    public ToolPromptsManager(String toolName) {
        this.ToolName = toolName;
        this.simpleClassName = this.ToolName;
        if (this.ToolName != null && this.ToolName.contains(".")) {
            this.simpleClassName = this.ToolName.substring(this.ToolName.lastIndexOf('.') + 1);
        }

        // ✅ 直接从静态配置中获取文本，不再进行 .md 校验和读取
        this.ToolDescription = PROMPTS_PATH_CACHE.getProperty(this.simpleClassName + ".ToolDescription", "");
    }

    public String getCustomDescription(String key) {
        // ✅ 直接从静态配置中获取文本
        return PROMPTS_PATH_CACHE.getProperty(this.simpleClassName + "." + key, "");
    }
}