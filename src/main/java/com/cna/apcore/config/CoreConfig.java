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
    // 文件输入监控参数（FileSurveyTickAction）
    // ==========================================

    /** 桌面巡视间隔（tick 数），池空闲时每隔这么多 tick 注入一次目录快照 */
    public static final int FILE_SURVEY_INTERVAL_TICKS;

    /** 空闲判断阈值：池大小低于此值才触发桌面巡视 */
    public static final int FILE_IDLE_POOL_THRESHOLD;

    /** 桌面巡视产生单元的基础 SE */
    public static final double FILE_INPUT_BASE_SE;

    // ==========================================
    // TickAction 参数
    // ==========================================

    /** 自我检查间隔（tick 数），默认 30 = 约 60 秒 */
    public static final int TICK_SELFCHECK_INTERVAL_TICKS;

    /** 自我检查产生单元的基础 SE */
    public static final double TICK_SELFCHECK_BASE_SE;

    // ==========================================
    // 注意力机制参数（AttentionManager）
    // ==========================================

    /** 注意力资源池上限 */
    public static final double ATTENTION_POOL_MAX;

    /** 每 tick 注意力资源恢复量 */
    public static final double ATTENTION_REGEN_PER_TICK;

    /** 每 tick 最多关注多少个单元 */
    public static final int ATTENTION_MAX_ATTEND_UNITS;

    /** 每 tick 注意力衰减系数（未被持续关注的单元注意力消退速度） */
    public static final double ATTENTION_DECAY_PER_TICK;

    // ==========================================
    // Chat消息聚合参数（ChatMessageActionDeveloper）
    // ==========================================

    /** 每个 source 桶最少攒几条消息才 flush */
    public static final int CHAT_BATCH_MIN_MESSAGES;

    /** 每个 source 桶最多等待多少毫秒后强制 flush */
    public static final long CHAT_BATCH_MAX_WAIT_MS;

    /** flush 后同一 source 的冷却时间（毫秒） */
    public static final long CHAT_BATCH_COOLDOWN_MS;

    /** 私聊消息的最低 flush 消息数（低于群聊阈值，更快响应） */
    public static final int CHAT_BATCH_PRIVATE_MIN_MESSAGES;

    // ==========================================
    // 调度参数
    // ==========================================

    /** ActionLoop tick 间隔（毫秒） */
    public static final int COGNITIVE_TICK_MS;

    /** LLM 单次 boost ContinueWeight 的上限 */
    public static final double SINGLE_BOOST_CAP;

    // ==========================================
    // 注意力态度参数（感觉驱动的注意力倍率引擎）
    // ==========================================

    /** 注意力态度缩放因子：multiplier = 1 + rawModulation * scale */
    public static final double ATTENTION_ATTITUDE_SCALE;

    /** 注意力态度取值范围上限 */
    public static final double ATTENTION_ATTITUDE_MAX;

    /** 注意力态度取值范围下限 */
    public static final double ATTENTION_ATTITUDE_MIN;

    /** 注意力态度每 tick 自然衰减系数，0=不衰减 */
    public static final double ATTENTION_ATTITUDE_DECAY;

    /** 外部输入触发感觉的态度增量 */
    public static final double ATTENTION_BOOST_EXTERNAL;

    /** LLM 内源任务触发感觉的态度增量基准 */
    public static final double ATTENTION_BOOST_ENDOGENOUS;

    /** 被选中执行的任务触发感觉的态度增量 */
    public static final double ATTENTION_BOOST_SELECTED;

    // ==========================================
    // 语义疲劳参数（FatigueManager）
    // ==========================================

    /** 近期处理历史最大条目数（环形缓冲上限） */
    public static final int FATIGUE_MAX_HISTORY;

    /** 时间衰减系数：recencyWeight = exp(-age × decayRate) */
    public static final double FATIGUE_DECAY_RATE;

    /** 疲劳对选择得分的敏感度：penalty = 1 / (1 + fatigue × sensitivity) */
    public static final double FATIGUE_SENSITIVITY;

    /** 超过此 tick 数的历史条目自动清理 */
    public static final int FATIGUE_MAX_AGE_TICKS;

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

        // —— 桌面巡视 ——
        FILE_SURVEY_INTERVAL_TICKS = getInt("v4.file.surveyIntervalTicks", 8);
        FILE_IDLE_POOL_THRESHOLD   = getInt("v4.file.idlePoolThreshold", 2);
        FILE_INPUT_BASE_SE              = getDouble("v4.file.inputBaseSE", 0.6);

        // —— TickAction ——
        TICK_SELFCHECK_INTERVAL_TICKS  = getInt("v4.tick.selfCheckIntervalTicks", 30);
        TICK_SELFCHECK_BASE_SE         = getDouble("v4.tick.selfCheckBaseSE", 0.5);

        // —— 注意力机制 ——
        ATTENTION_POOL_MAX           = getDouble("v4.attention.poolMax", 100.0);
        ATTENTION_REGEN_PER_TICK     = getDouble("v4.attention.regenPerTick", 5.0);
        ATTENTION_MAX_ATTEND_UNITS   = getInt("v4.attention.maxAttendUnits", 5);
        ATTENTION_DECAY_PER_TICK     = getDouble("v4.attention.decayPerTick", 0.05);

        // —— 注意力态度 ——
        ATTENTION_ATTITUDE_SCALE     = getDouble("v4.attention.attitudeScale", 0.5);
        ATTENTION_ATTITUDE_MAX       = getDouble("v4.attention.attitudeMax", 1.0);
        ATTENTION_ATTITUDE_MIN       = getDouble("v4.attention.attitudeMin", -1.0);
        ATTENTION_ATTITUDE_DECAY     = getDouble("v4.attention.attitudeDecay", 0.002);
        ATTENTION_BOOST_EXTERNAL     = getDouble("v4.attention.boostExternal", 0.02);
        ATTENTION_BOOST_ENDOGENOUS   = getDouble("v4.attention.boostEndogenous", 0.05);
        ATTENTION_BOOST_SELECTED      = getDouble("v4.attention.boostSelected", 0.08);

        // —— 语义疲劳 ——
        FATIGUE_MAX_HISTORY          = getInt("v4.fatigue.maxHistory", 30);
        FATIGUE_DECAY_RATE           = getDouble("v4.fatigue.decayRate", 0.08);
        FATIGUE_SENSITIVITY          = getDouble("v4.fatigue.sensitivity", 2.5);
        FATIGUE_MAX_AGE_TICKS        = getInt("v4.fatigue.maxAgeTicks", 200);

        // —— Chat 消息聚合 ——
        CHAT_BATCH_MIN_MESSAGES        = getInt("v4.chat.batchMinMessages", 3);
        CHAT_BATCH_MAX_WAIT_MS         = getLong("v4.chat.batchMaxWaitMs", 5000);
        CHAT_BATCH_COOLDOWN_MS         = getLong("v4.chat.batchCooldownMs", 3000);
        CHAT_BATCH_PRIVATE_MIN_MESSAGES = getInt("v4.chat.batchPrivateMinMessages", 1);

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

    private static long getLong(String key, long defaultValue) {
        String val = props.getProperty(key);
        if (val != null && !val.isBlank()) {
            try {
                return Long.parseLong(val.trim());
            } catch (NumberFormatException e) {
                log.warn("[CoreConfig] {} = {} 不是合法整数，使用默认值 {}", key, val, defaultValue);
            }
        }
        return defaultValue;
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
