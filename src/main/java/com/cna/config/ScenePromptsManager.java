package com.cna.config;

import com.cna.db.MDManager;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;

@Slf4j
@Deprecated
public class ScenePromptsManager {

    // 静态缓存，存储所有场景的提示词文件【路径】
    private static final Properties PROMPTS_PATH_CACHE = new Properties();
    private static final String CONFIG_FILE_NAME = "PromptScenesPath.properties";

    // 静态初始化块：只在类加载时读取一次配置文件
    static {
        File configFile = new File(System.getProperty("user.dir"), CONFIG_FILE_NAME);
        if (configFile.exists()) {
            try (FileInputStream fis = new FileInputStream(configFile)) {
                PROMPTS_PATH_CACHE.load(fis);
                log.info("[ScenePromptsManager] 成功加载提示词路径配置: {}", configFile.getAbsolutePath());
            } catch (IOException e) {
                log.error("[ScenePromptsManager] 加载提示词路径配置失败!", e);
            }
        } else {
            log.warn("[ScenePromptsManager] 配置文件不存在，请确保根目录包含: {}", configFile.getAbsolutePath());
        }
    }

    private String sceneName;

    // 默认初始值为 ""，完美适配 LivingLoop 的 Objects.equals(..., "")
    @Getter
    private String thinkingPrompt = "";

    @Getter
    private String solvingPrompt = "";

    public ScenePromptsManager(String sceneName) {
        this.sceneName = sceneName;

        // 1. 提取简短的类名 (例如: com.cna.agent.AgentTask.ChatTask -> ChatTask)
        String simpleClassName = sceneName;
        if (sceneName != null && sceneName.contains(".")) {
            simpleClassName = sceneName.substring(sceneName.lastIndexOf('.') + 1);
        }

        // 2. 从静态配置中获取文件路径 (注意这里的 Config 拼接，对应你的 properties 文件)
        String thinkingPath = PROMPTS_PATH_CACHE.getProperty(simpleClassName + ".Thinking", "");
        String solvingPath = PROMPTS_PATH_CACHE.getProperty(simpleClassName + ".Solving", "");

        // 3. 校验路径并读取内容（显式传 "" 作为默认值，避免 MDManager 用 "请输入文本" 创建垃圾文件）
        String tPrompt = isValidMdPath(thinkingPath) ? MDManager.read(thinkingPath, "") : "";
        String sPrompt = isValidMdPath(solvingPath) ? MDManager.read(solvingPath, "") : "";

        // 4. 终极兜底：防止 MDManager.read() 内部读取失败返回 null，强转为 ""
        this.thinkingPrompt = (tPrompt == null) ? "" : tPrompt;
        this.solvingPrompt = (sPrompt == null) ? "" : sPrompt;

        // 容错与日志提示
        if (this.solvingPrompt.isEmpty()) {
            log.warn("[ScenePromptsManager] 警告：未找到 {} 的 Solving 提示词内容。原因可能是：未配置、留空或路径不以 .md 结尾。读取到的路径: [{}]", simpleClassName, solvingPath);
        }
    }

    /**
     * 辅助方法：校验路径是否有效且以 .md 结尾
     */
    private boolean isValidMdPath(String path) {
        // 判空 & 判空白格 & 判断后缀 (转小写防止 .MD 导致的不匹配)
        return path != null && !path.trim().isEmpty() && path.toLowerCase().endsWith(".md");
    }
}