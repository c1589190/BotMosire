package com.cna.apcore.config;

import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * V4 Core 配置管理器。
 *
 * 从 application.properties 加载所有 v4.core.* 配置项，
 * 模式与 com.cna.config.ConfigsManager 保持一致。
 */
@Slf4j
public class CoreConfig {

    private static final Properties props = new Properties();

    // ==========================================
    // 认知准备池参数
    // ==========================================

    /** SE×UE 基础底线，低于此值的单元不会被选中 */
    public static final double BASELINE_THRESHOLD;

    /** 每 tick ContinueWeight 衰减系数（0~1） */
    public static final double CONTINUE_WEIGHT_DECAY;

    /** ContinueWeight 上限 */
    public static final double MAX_CONTINUE_WEIGHT;

    /** 准备池最大容量 */
    public static final int MAX_POOL_SIZE;

    /** 超过此 tick 数未被选中的单元将被移除 */
    public static final int MAX_TICKS_WITHOUT_SELECT;

    // ==========================================
    // 经验检索参数
    // ==========================================

    /** 从 ExperiencesDB 检索多少条先验经验 */
    public static final int TOP_N_ACTION_PREDICTS;

    /** 每个 UE 节点增加多少条经验请求量 */
    public static final int SCALE_PER_UE_NODE;

    // ==========================================
    // 感觉维度参数
    // ==========================================

    /** 厌倦阈值：activationCount 超过此值进入"永熟悉"状态 */
    public static final int HABITUATION_LIMIT;

    /** BFS 最大搜索层数 */
    public static final int BFS_MAX_LAYERS;

    /** BFS 每层权重衰减因子（0~1） */
    public static final double BFS_LAYER_DECAY;

    // ==========================================
    // 去重参数
    // ==========================================

    /** embedding 余弦相似度去重阈值 */
    public static final double DEDUP_THRESHOLD;

    // ==========================================
    // 调度参数
    // ==========================================

    /** ActionLoop tick 间隔（毫秒） */
    public static final int COGNITIVE_TICK_MS;

    /** LLM 单次 boost ContinueWeight 的上限 */
    public static final double SINGLE_BOOST_CAP;

    // ==========================================
    // 初始化
    // ==========================================

    static {
        String configPath = "application.properties";
        Path path = Paths.get(configPath);
        if (Files.exists(path)) {
            try (InputStream input = Files.newInputStream(path);
                 InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
                props.load(reader);
                log.info("[CoreConfig] 已加载配置文件: {}", configPath);
            } catch (Exception e) {
                log.error("[CoreConfig] 加载配置文件失败: {}", configPath, e);
            }
        } else {
            log.warn("[CoreConfig] 配置文件不存在: {}，全部使用默认值", configPath);
        }

        // —— 认知准备池 ——
        BASELINE_THRESHOLD        = getDouble("v4.core.baselineThreshold", 0.3);
        CONTINUE_WEIGHT_DECAY     = getDouble("v4.core.continueWeightDecay", 0.9);
        MAX_CONTINUE_WEIGHT       = getDouble("v4.core.maxContinueWeight", 5.0);
        MAX_POOL_SIZE             = getInt("v4.core.maxPoolSize", 32);
        MAX_TICKS_WITHOUT_SELECT  = getInt("v4.core.maxTicksWithoutSelect", 20);

        // —— 经验检索 ——
        TOP_N_ACTION_PREDICTS     = getInt("v4.core.topNActionPredicts", 5);
        SCALE_PER_UE_NODE         = getInt("v4.core.scalePerUENode", 2);

        // —— 感觉维度 ——
        HABITUATION_LIMIT         = getInt("v4.core.habituationLimit", 10);
        BFS_MAX_LAYERS            = getInt("v4.core.bfsMaxLayers", 3);
        BFS_LAYER_DECAY           = getDouble("v4.core.bfsLayerDecay", 0.7);

        // —— 去重 ——
        DEDUP_THRESHOLD           = getDouble("v4.core.dedupThreshold", 0.85);

        // —— 调度 ——
        COGNITIVE_TICK_MS         = getInt("v4.core.cognitiveTickMs", 2000);
        SINGLE_BOOST_CAP           = getDouble("v4.core.singleBoostCap", 1.0);

        log.info("[CoreConfig] V4 Core 配置初始化完毕: baselineThreshold={}, tickMs={}, poolSize={}",
                BASELINE_THRESHOLD, COGNITIVE_TICK_MS, MAX_POOL_SIZE);
    }

    /** 初始化（显式调用，保证 static 块已被触发） */
    public static void init() {
        // static 块在类加载时自动执行，此方法仅作为显式触发点
    }

    // ==========================================
    // 配置读取辅助方法
    // ==========================================

    private static String getString(String key, String defaultValue) {
        return props.getProperty(key, defaultValue);
    }

    private static int getInt(String key, int defaultValue) {
        String val = props.getProperty(key);
        if (val != null && !val.isBlank()) {
            try {
                return Integer.parseInt(val.trim());
            } catch (NumberFormatException e) {
                log.warn("[CoreConfig] {} = {} 不是合法整数，使用默认值 {}", key, val, defaultValue);
            }
        }
        return defaultValue;
    }

    private static double getDouble(String key, double defaultValue) {
        String val = props.getProperty(key);
        if (val != null && !val.isBlank()) {
            try {
                return Double.parseDouble(val.trim());
            } catch (NumberFormatException e) {
                log.warn("[CoreConfig] {} = {} 不是合法浮点数，使用默认值 {}", key, val, defaultValue);
            }
        }
        return defaultValue;
    }
}
