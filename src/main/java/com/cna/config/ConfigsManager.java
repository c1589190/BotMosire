package com.cna.config;

import com.cna.llm.LLMConfig;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

@Slf4j
public class ConfigsManager {

    private static final Properties props = new Properties();

    // ==========================================
    // 声明为 public static final
    // ==========================================
    public static final LLMConfig GATEKEEPER_CONFIG;
    public static final LLMConfig PLANNER_CONFIG; // 【新增】：战略规划/第0轮预思考专用模型
    public static final LLMConfig BRAIN_CONFIG;
    public static final LLMConfig ADVANCED_BRAIN_CONFIG;
    public static final LLMConfig EMBEDDING_CONFIG;
    public static final LLMConfig SCHEDULER_CONFIG;
    public static final LLMConfig VISION_MODEL;

    public static final int COGNITIVE_CYCLE_TICKS;
    public static final int MESSAGE_WAITING_TIME;
    public static final int CONSUMER_CYCLING_TIME;
    public static final int SCHEDULE_CYCLING_TIME;
    public static final int TASK_COUNT_FOR_REFLECTION;
    public static final int PENDING_CHAT_WAITING_TIME;

    public static final int CURRENT_MEMORIES_MAXSIZE;
    public static final int EMB_MEMORY_SIZE;
    public static final int HISTORY_VIEW_AMOUNT;
    public static final int CHATHISTORY_VIEW_AMOUNT;
    public static final int MEMORY_DEPTH;
    public static final double RANDOM_CHAT_CHANCE;
    public static final String DB_URL;

    public static final String NAPCAT_WS_URL;
    public static final String NAPCAT_TOEKN;
    public static final int NAPCAT_WS_PORT;
    public static final String NAPCAT_HTTP_URL;

    public static final String WORKSPACE_DIR;

    public static final int MAX_TASK_AMOUNT;
    public static final long RATE_LIMIT_MS;
    public static final String DEPLOYER_ROLE;
    public static final String SEARCH_API_KEY;
    public static final String JINA_API_KEY;


    public static void init(){
        log.info("[ConfigsManager] 配置模块初始化完毕。");
    }

    // 静态代码块：类加载时读取配置文件并初始化
    static {
        Path externalConfig = Paths.get("application.properties");

        // 核心逻辑：如果没有配置文件，先从 resources 模板生成一份！
        if (!Files.exists(externalConfig)) {
            System.err.println("[ConfigsManager] ⚠️ 未检测到 application.properties，正在从模板生成默认配置文件...");
            generateDefaultConfigFile(externalConfig);
        }

        try {
            // 此时文件一定存在（除非生成失败或权限不足），直接读取
            try (InputStream in = Files.newInputStream(externalConfig)) {
                props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
                System.out.println("[ConfigsManager] 成功加载外部物理配置文件: " + externalConfig.toAbsolutePath());
            }
        } catch (Exception e) {
            System.err.println("❌ 读取外部配置文件失败: " + e.getMessage());
            System.err.println("[ConfigsManager] ⚠️ 引擎将使用系统硬编码的默认参数运行。");
        }

        // ==========================================
        // 1. 初始化 LLM 混合编排矩阵
        // ==========================================

        GATEKEEPER_CONFIG = LLMConfig.builder()
                .apiBase(getString("llm.gatekeeper.apiBase", "https://api.siliconflow.cn/v1"))
                .apiKey(getEnvOrProp("SILICONFLOW_API_KEY", "llm.gatekeeper.apiKey", ""))
                .chatModel(getString("llm.gatekeeper.chatModel", "Pro/deepseek-ai/DeepSeek-V3.2"))
                .temperature(getDouble("llm.gatekeeper.temperature", 0.3))
                .enableCoT(getBoolean("llm.gatekeeper.enableCoT", true))
                .build();

        // 【新增】：战略规划者配置 (第0轮专用)
        PLANNER_CONFIG = LLMConfig.builder()
                .apiBase(getString("llm.planner.apiBase", "https://api.siliconflow.cn/v1"))
                .apiKey(getEnvOrProp("SILICONFLOW_API_KEY", "llm.planner.apiKey", ""))
                .chatModel(getString("llm.planner.chatModel", "Pro/deepseek-ai/DeepSeek-V3.2"))
                .temperature(getDouble("llm.planner.temperature", 0.5))
                .frequencyPenalty(getDouble("llm.planner.frequencyPenalty", 0.2))
                .presencePenalty(getDouble("llm.planner.presencePenalty", 0.2))
                .enableCoT(getBoolean("llm.planner.enableCoT", true))
                .build();

        BRAIN_CONFIG = LLMConfig.builder()
                .apiBase(getString("llm.brain.apiBase", "https://api.siliconflow.cn/v1"))
                .apiKey(getEnvOrProp("SILICONFLOW_API_KEY", "llm.brain.apiKey", ""))
                .chatModel(getString("llm.brain.chatModel", "Pro/deepseek-ai/DeepSeek-V3.2"))
                .temperature(getDouble("llm.brain.temperature", 0.6))
                .frequencyPenalty(getDouble("llm.brain.frequencyPenalty", 0.4))
                .presencePenalty(getDouble("llm.brain.presencePenalty", 0.5))
                .enableCoT(getBoolean("llm.brain.enableCoT", false))
                .max_tokens(getInt("llm.brain.maxTokens", 8192))
                .build();

        ADVANCED_BRAIN_CONFIG = LLMConfig.builder()
                .apiBase(getString("llm.advanced_brain.apiBase", "https://api.siliconflow.cn/v1"))
                .apiKey(getEnvOrProp("SILICONFLOW_API_KEY", "llm.advanced_brain.apiKey", ""))
                .chatModel(getString("llm.advanced_brain.chatModel", "Pro/deepseek-ai/DeepSeek-R1"))
                .temperature(getDouble("llm.advanced_brain.temperature", 0.6))
                .frequencyPenalty(getDouble("llm.advanced_brain.frequencyPenalty", 0.4))
                .presencePenalty(getDouble("llm.advanced_brain.presencePenalty", 0.5))
                .enableCoT(getBoolean("llm.advanced_brain.enableCoT", true))
                .max_tokens(getInt("llm.advanced_brain.maxTokens", 16384))
                .build();

        EMBEDDING_CONFIG = LLMConfig.builder()
                .apiBase(getString("llm.embedding.apiBase", "https://api.siliconflow.cn/v1"))
                .apiKey(getEnvOrProp("SILICONFLOW_API_KEY", "llm.embedding.apiKey", ""))
                .embeddingModel(getString("llm.embedding.embeddingModel", "Qwen/Qwen3-Embedding-4B"))
                .temperature(getDouble("llm.embedding.temperature", 0.0))
                .build();

        SCHEDULER_CONFIG = LLMConfig.builder()
                .apiBase(getString("llm.scheduler.apiBase", "https://api.siliconflow.cn/v1"))
                .apiKey(getEnvOrProp("SILICONFLOW_API_KEY", "llm.scheduler.apiKey", ""))
                .chatModel(getString("llm.scheduler.chatModel", "Pro/deepseek-ai/DeepSeek-V3.2"))
                .temperature(getDouble("llm.scheduler.temperature", 0.4))
                .frequencyPenalty(getDouble("llm.scheduler.frequencyPenalty", 0.4))
                .presencePenalty(getDouble("llm.scheduler.presencePenalty", 0.5))
                .enableCoT(getBoolean("llm.scheduler.enableCoT", true))
                .build();

        VISION_MODEL = LLMConfig.builder()
                .apiBase(getString("llm.vision.apiBase", "https://api.siliconflow.cn/v1"))
                .apiKey(getEnvOrProp("SILICONFLOW_API_KEY", "llm.vision.apiKey", "")) // 修复了这里原本的 llm.svision.apiKey
                .chatModel(getString("llm.vision.chatModel", "Qwen/Qwen3.6-35B-A3B"))
                .temperature(getDouble("llm.vision.temperature", 0.0))
                //.frequencyPenalty(getDouble("llm.vision.frequencyPenalty", 0.4))
                //.presencePenalty(getDouble("llm.vision.presencePenalty", 0.5))
                //.enableCoT(getBoolean("llm.vision.enableCoT", true))
                .build();

        // ==========================================
        // 2. 认知引擎心跳参数
        // ==========================================
        COGNITIVE_CYCLE_TICKS = getInt("cognitive.cycleTicks", 8000);
        MESSAGE_WAITING_TIME = getInt("cognitive.messageWaitingTime", 5);
        CONSUMER_CYCLING_TIME = getInt("cognitive.consumerCyclingTime", 10);
        TASK_COUNT_FOR_REFLECTION = getInt("cognitive.taskCountForReflection", 10);
        SCHEDULE_CYCLING_TIME = getInt("cognitive.scheduleCyclingTime", 300000);
        MAX_TASK_AMOUNT = getInt("cognitive.maxTaskAmount", 3);
        RANDOM_CHAT_CHANCE = getDouble("cognitive.randomChatChance", 0.05);
        RATE_LIMIT_MS = getInt("cognitive.rateLimitMs", 5000);
        DEPLOYER_ROLE = getString("bot.deployerRole", "");
        SEARCH_API_KEY = getEnvOrProp("BRAVE_SEARCH_API_KEY", "search.braveApiKey", "");
        JINA_API_KEY   = getEnvOrProp("JINA_API_KEY", "search.jinaApiKey", "");
        PENDING_CHAT_WAITING_TIME = getInt("cognitive.pendingChatWaitingTime", 180000);

        // ==========================================
        // 3. 海马体记忆参数
        // ==========================================
        CURRENT_MEMORIES_MAXSIZE = getInt("memory.currentMemoriesMaxSize", 64);
        EMB_MEMORY_SIZE = getInt("memory.embMemorySize", 32);
        HISTORY_VIEW_AMOUNT = getInt("memory.historyViewAmount", 20);
        CHATHISTORY_VIEW_AMOUNT = getInt("memory.chatHistoryViewAmount", 20);
        MEMORY_DEPTH = getInt("memory.memoryDepth", 3);
        DB_URL = getString("memory.dbUrl", "jdbc:sqlite:agent_memory.db");

        // ==========================================
        // 4. Napcat 物理通信配置
        // ==========================================
        NAPCAT_WS_URL = getString("napcat.wsUrl", "127.0.0.1");
        NAPCAT_WS_PORT = getInt("napcat.wsPort", 3001);
        NAPCAT_HTTP_URL = getString("napcat.httpUrl", "http://127.0.0.1:3000");
        NAPCAT_TOEKN = getString("napcat.token", "");

        // ==========================================
        // 5. WorkSpace 沙盒目录
        // ==========================================
        WORKSPACE_DIR = getString("workspace.dir", "workspace");

    }

    /**
     * 从 resources 目录下的模板释放 application.properties 文件
     */
    private static void generateDefaultConfigFile(Path targetPath) {
        String templateName = "/application-template.properties";

        try (InputStream in = ConfigsManager.class.getResourceAsStream(templateName)) {
            if (in == null) {
                System.err.println("[ConfigsManager] ❌ 致命错误：在 resources 目录下找不到模板文件: " + templateName);
                return;
            }

            // 将内部的资源流直接复制到外部路径
            Files.copy(in, targetPath, StandardCopyOption.REPLACE_EXISTING);
            System.out.println("[ConfigsManager] ✅ 已成功从 resources 模板释放默认配置文件到: " + targetPath.toAbsolutePath());

        } catch (Exception e) {
            System.err.println("[ConfigsManager] ❌ 从 resources 拷贝配置文件失败: " + e.getMessage());
        }
    }

    // ==========================================
    // 辅助工具方法 (带默认值和类型转换)
    // ==========================================

    private static String getEnvOrProp(String envKey, String propKey, String defaultValue) {
        String envValue = System.getenv(envKey);
        if (envValue != null && !envValue.trim().isEmpty()) {
            return envValue;
        }
        return props.getProperty(propKey, defaultValue);
    }

    private static String getString(String key, String defaultValue) {
        return props.getProperty(key, defaultValue);
    }

    public static String getConfig(String key, String defaultValue) {
        return props.getProperty(key, defaultValue);
    }

    private static int getInt(String key, int defaultValue) {
        String val = props.getProperty(key);
        if (val == null) return defaultValue;
        try { return Integer.parseInt(val.trim()); }
        catch (NumberFormatException e) { return defaultValue; }
    }

    private static double getDouble(String key, double defaultValue) {
        String val = props.getProperty(key);
        if (val == null) return defaultValue;
        try { return Double.parseDouble(val.trim()); }
        catch (NumberFormatException e) { return defaultValue; }
    }

    private static boolean getBoolean(String key, boolean defaultValue) {
        String val = props.getProperty(key);
        if (val == null) return defaultValue;
        return Boolean.parseBoolean(val.trim());
    }
}