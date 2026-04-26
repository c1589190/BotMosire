package com.cna.config;

import com.cna.llm.LLMConfig;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

@Slf4j
public class ConfigsManager {

    private static final Properties props = new Properties();

    // ==========================================
    // 声明为 public static final，保持你原有的调用方式不变
    // ==========================================
    public static final LLMConfig GATEKEEPER_CONFIG;
    public static final LLMConfig BRAIN_CONFIG;
    public static final LLMConfig EMBEDDING_CONFIG;

    public static final int COGNITIVE_CYCLE_TICKS;
    public static final int MESSAGE_WAITING_TIME;
    public static final int CONSUMER_CYCLING_TIME;
    public static final int TASK_COUNT_FOR_REFLECTION;

    public static final int CURRENT_MEMORIES_MAXSIZE;
    public static final int EMB_MEMORY_SIZE;
    public static final int HISTORY_VIEW_AMOUNT;
    public static final int MEMORY_DEPTH;
    public static final String DB_URL;

    public static final int NAPCAT_WS_PORT;
    public static final String NAPCAT_HTTP_URL;

    public static final String FILE_CORE_PERSONA;
    public static final String FILE_ATTENTION_PROMPT;
    public static final String FILE_DEEP_MEMORY_PROMPT;
    public static final int MAX_TASK_AMOUNT;


    public static void init(){
        log.info("");
        //只是专门调用一下确保下面的static字段成功调用
    }
    // 静态代码块：类加载时读取配置文件并初始化
    static {
        // 1. 加载 properties 文件
        try (InputStream in = ConfigsManager.class.getClassLoader().getResourceAsStream("application.properties")) {
            if (in != null) {
                // 推荐指定 UTF-8 编码，防止读取中文路径或配置乱码
                props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            } else {
                System.err.println("⚠️ 未在 classpath 下找到 application.properties，将使用代码中的默认值！");
            }
        } catch (Exception e) {
            System.err.println("❌ 读取配置文件失败: " + e.getMessage());
        }

        // ==========================================
        // 1. 初始化 LLM 混合编排矩阵
        // ==========================================
        GATEKEEPER_CONFIG = LLMConfig.builder()
                .apiBase(getString("llm.gatekeeper.apiBase", "https://api.siliconflow.cn/v1"))
                // 优先读取系统环境变量 SILICONFLOW_API_KEY，如果没有则读取文件中的 llm.gatekeeper.apiKey
                .apiKey(getEnvOrProp("SILICONFLOW_API_KEY", "llm.gatekeeper.apiKey", ""))
                .chatModel(getString("llm.gatekeeper.chatModel", "Pro/deepseek-ai/DeepSeek-V3.2"))
                .temperature(getDouble("llm.gatekeeper.temperature", 0.3))
                .enableCoT(getBoolean("llm.gatekeeper.enableCoT", true))
                .build();

        BRAIN_CONFIG = LLMConfig.builder()
                .apiBase(getString("llm.brain.apiBase", "https://api.siliconflow.cn/v1"))
                .apiKey(getEnvOrProp("SILICONFLOW_API_KEY", "llm.brain.apiKey", ""))
                .chatModel(getString("llm.brain.chatModel", "Pro/deepseek-ai/DeepSeek-V3.2"))
                .temperature(getDouble("llm.brain.temperature", 0.6))
                .frequencyPenalty(getDouble("llm.brain.frequencyPenalty", 0.4))
                .presencePenalty(getDouble("llm.brain.presencePenalty", 0.5))
                .enableCoT(getBoolean("llm.brain.enableCoT", true))
                .build();

        EMBEDDING_CONFIG = LLMConfig.builder()
                .apiBase(getString("llm.embedding.apiBase", "https://api.siliconflow.cn/v1"))
                .apiKey(getEnvOrProp("SILICONFLOW_API_KEY", "llm.embedding.apiKey", ""))
                .embeddingModel(getString("llm.embedding.embeddingModel", "Qwen/Qwen3-Embedding-4B"))
                .temperature(getDouble("llm.embedding.temperature", 0.0))
                .build();

        // ==========================================
        // 2. 认知引擎心跳参数
        // ==========================================
        COGNITIVE_CYCLE_TICKS = getInt("cognitive.cycleTicks", 8000);
        MESSAGE_WAITING_TIME = getInt("cognitive.messageWaitingTime", 5);
        CONSUMER_CYCLING_TIME = getInt("cognitive.consumerCyclingTime", 10);
        TASK_COUNT_FOR_REFLECTION = getInt("cognitive.taskCountForReflection", 10);
        MAX_TASK_AMOUNT = getInt("cognitive.maxTaskAmount", 3);

        // ==========================================
        // 3. 海马体记忆参数
        // ==========================================
        CURRENT_MEMORIES_MAXSIZE = getInt("memory.currentMemoriesMaxSize", 64);
        EMB_MEMORY_SIZE = getInt("memory.embMemorySize", 32);
        HISTORY_VIEW_AMOUNT = getInt("memory.historyViewAmount", 20);
        MEMORY_DEPTH = getInt("memory.memoryDepth", 3);
        DB_URL = getString("memory.dbUrl", "jdbc:sqlite:agent_memory.db");

        // ==========================================
        // 4. Napcat 物理通信配置
        // ==========================================
        NAPCAT_WS_PORT = getInt("napcat.wsPort", 3001);
        NAPCAT_HTTP_URL = getString("napcat.httpUrl", "http://127.0.0.1:3000");

        // ==========================================
        // 5. 提示词文件路径
        // ==========================================
        FILE_CORE_PERSONA = getString("prompts.corePersona", "promptForChatCore.md");
        FILE_ATTENTION_PROMPT = getString("prompts.attention", "promptForAttention.md");
        FILE_DEEP_MEMORY_PROMPT = getString("prompts.deepMemory", "promptForGetDeepMemory.md");
    }

    // ==========================================
    // 辅助工具方法 (带默认值和类型转换)
    // ==========================================

    /**
     * 获取配置字符串（支持优先从系统环境变量读取）
     */
    private static String getEnvOrProp(String envKey, String propKey, String defaultValue) {
        // 1. 尝试读环境变量 (例如在 Docker/Linux 环境中配置的密钥)
        String envValue = System.getenv(envKey);
        if (envValue != null && !envValue.trim().isEmpty()) {
            return envValue;
        }
        // 2. 降级读取 properties 文件
        return props.getProperty(propKey, defaultValue);
    }

    private static String getString(String key, String defaultValue) {
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