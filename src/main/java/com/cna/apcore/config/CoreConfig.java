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

    /** 选取得分基线：(semanticWeight + practicalBonus) 低于此值的单元不会被选中 */
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

    /** 先验经验最大条数上限（防止 prompt 爆炸） */
    public static final int MAX_ACTION_PREDICTS;

    /** 每条先验经验文本最大字符数 */
    public static final int ACTION_PREDICT_TEXT_MAX_CHARS;

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
    // 来源优先级（sourcePriority）— 外源消息的实践权重
    // ==========================================

    /** 文件巡视产生单元的基础来源优先级 */
    public static final double FILE_SOURCE_PRIORITY;

    /** 文件巡视间隔（tick 数） */
    public static final int FILE_SURVEY_INTERVAL_TICKS;

    /** 文件巡视空闲阈值（池大小低于此值才触发） */
    public static final int FILE_IDLE_POOL_THRESHOLD;

    /** 自我检查产生单元的基础来源优先级 */
    public static final double TICK_SELFCHECK_SOURCE_PRIORITY;

    /** 自我检查间隔（tick 数） */
    public static final int TICK_SELFCHECK_INTERVAL_TICKS;

    /** 自我检查是否需要池空闲才触发 */
    public static final boolean TICK_SELFCHECK_REQUIRE_IDLE;

    /** 自我检查空闲判断阈值（池大小低于此值才触发） */
    public static final int TICK_SELFCHECK_IDLE_THRESHOLD;

    // ==========================================
    // 调试/指标参数
    // ==========================================

    /** 是否启用注意力指标记录 */
    public static final boolean ATTENTION_METRICS_ENABLED;

    /** 注意力指标环形缓冲区容量 */
    public static final int ATTENTION_METRICS_BUFFER_SIZE;

    // ==========================================
    // 实践加成（practicalBonus）— 内源任务继承父权重 + 定值
    // ==========================================

    /** next_action：LLM 显式规划的下一步，sourcePriority = 父(semanticW+srcPri) + 此值 */
    public static final double NEXT_ACTION_BONUS;

    /** 续命任务（无 finish_action / new_prepare_unit）的加成 */
    public static final double CONTINUED_BONUS;

    /** 工具执行结果汇总入池的固定来源优先级 */
    public static final double TOOL_SUMMARY_SOURCE_PRIORITY;

    // ==========================================
    // 行动模板匹配参数（ActionTemplateMatcher）
    // ==========================================

    /** 工具在匹配经验中出现比例的最低阈值 */
    public static final double TEMPLATE_MIN_TOOL_RATIO;

    /** 最小匹配经验数 */
    public static final int TEMPLATE_MIN_MATCH_COUNT;

    /** 最多输出的模板数 */
    public static final int TEMPLATE_MAX_TEMPLATES;

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
    // 互斥检测 v2 参数（触发内距离法 / Intra-Triggered Dissonance）
    // ==========================================

    /** 触发相似度下限：余弦相似度达到此值的感觉视为被 action 触发 */
    public static final double INTRATRIGGER_TRIGGER_THRESHOLD;

    /** 最多分析几个触发感觉 */
    public static final int INTRATRIGGER_MAX_TRIGGERED;

    /** 最少需要几个触发感觉才做分析（太少无意义） */
    public static final int INTRATRIGGER_MIN_TRIGGERED;

    /** 孤立阈值：触发感觉到其他 peer 的平均距离超过此值视为"触发但孤立" */
    public static final double INTRATRIGGER_ISOLATION_THRESHOLD;

    /** 远距对阈值：两个触发感觉的相似度低于此值视为"远距触发对" */
    public static final double INTRATRIGGER_PAIR_THRESHOLD;

    /** 最多报告几个互斥/失调候选 */
    public static final int INTRATRIGGER_MAX_REPORT;

    /** 是否启用触发感觉聚类检测 */
    public static final boolean INTRATRIGGER_CLUSTER_ENABLED;

    // ==========================================
    // Prompt 大小管理参数
    // ==========================================

    /** 发送给 LLM 之前，对渲染后 prompt 的最大字符数限制（超出则智能截断） */
    public static final int PROMPT_MAX_CHARS;

    /** 准备池摘要最多显示的单元数（控制 prompt 大小） */
    public static final int POOL_SUMMARY_MAX_UNITS;

    /** 方法论/工具指南文本最大字符数（注入 prompt 前截断到此值） */
    public static final int MAX_METHODOLOGY_CHARS;

    /** 截断优先级（逗号分隔：methodology,experiences,pool_summary） */
    public static final String PROMPT_TRUNCATION_PRIORITY;

    // ==========================================
    // 初始化
    // ==========================================

    static {
        String configPath = "core.properties";
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
        BASELINE_THRESHOLD        = getDouble("v4.core.baselineThreshold", 1.0);
        CONTINUE_WEIGHT_DECAY     = getDouble("v4.core.continueWeightDecay", 0.9);
        MAX_CONTINUE_WEIGHT       = getDouble("v4.core.maxContinueWeight", 5.0);
        MAX_POOL_SIZE             = getInt("v4.core.maxPoolSize", 32);
        MAX_TICKS_WITHOUT_SELECT  = getInt("v4.core.maxTicksWithoutSelect", 20);

        // —— 经验检索 ——
        TOP_N_ACTION_PREDICTS     = getInt("v4.core.topNActionPredicts", 5);
        SCALE_PER_UE_NODE         = getInt("v4.core.scalePerUENode", 2);

        // —— 经验检索上限 ——
        MAX_ACTION_PREDICTS            = getInt("v4.core.maxActionPredicts", 15);
        ACTION_PREDICT_TEXT_MAX_CHARS  = getInt("v4.core.predictTextMaxChars", 2000);

        // —— 感觉维度 ——
        HABITUATION_LIMIT         = getInt("v4.core.habituationLimit", 10);
        BFS_MAX_LAYERS            = getInt("v4.core.bfsMaxLayers", 3);
        BFS_LAYER_DECAY           = getDouble("v4.core.bfsLayerDecay", 0.7);

        // —— 去重 ——
        DEDUP_THRESHOLD           = getDouble("v4.core.dedupThreshold", 0.85);

        // —— 来源优先级 ——
        FILE_SOURCE_PRIORITY                = getDouble("v4.source.filePriority", 0.6);
        FILE_SURVEY_INTERVAL_TICKS          = getInt("v4.file.surveyIntervalTicks", 8);
        FILE_IDLE_POOL_THRESHOLD            = getInt("v4.file.idlePoolThreshold", 2);
        TICK_SELFCHECK_SOURCE_PRIORITY      = getDouble("v4.source.selfCheckPriority", 0.5);
        TICK_SELFCHECK_INTERVAL_TICKS       = getInt("v4.tick.selfCheckIntervalTicks", 30);
        TICK_SELFCHECK_REQUIRE_IDLE         = getBoolean("v4.tick.selfCheckRequireIdle", true);
        TICK_SELFCHECK_IDLE_THRESHOLD       = getInt("v4.tick.selfCheckIdleThreshold", 3);

        // —— 调试/指标 ——
        ATTENTION_METRICS_ENABLED        = getBoolean("v4.debug.attentionMetrics", false);
        ATTENTION_METRICS_BUFFER_SIZE    = getInt("v4.debug.attentionMetricsBufferSize", 100);

        // —— 实践加成（内源继承父权重 + 定值）——
        NEXT_ACTION_BONUS            = getDouble("v4.bonus.nextAction", 0.2);
        CONTINUED_BONUS              = getDouble("v4.bonus.continued", 0.1);
        TOOL_SUMMARY_SOURCE_PRIORITY = getDouble("v4.bonus.toolSummary", 0.1);

        // —— 行动模板匹配 ——
        TEMPLATE_MIN_TOOL_RATIO    = getDouble("v4.template.minToolRatio", 0.6);
        TEMPLATE_MIN_MATCH_COUNT   = getInt("v4.template.minMatchCount", 3);
        TEMPLATE_MAX_TEMPLATES     = getInt("v4.template.maxTemplates", 5);

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

        // —— 互斥检测 v2（触发内距离法）——
        INTRATRIGGER_TRIGGER_THRESHOLD   = getDouble("v4.intratrigger.triggerThreshold", 0.4);
        INTRATRIGGER_MAX_TRIGGERED       = getInt("v4.intratrigger.maxTriggered", 20);
        INTRATRIGGER_MIN_TRIGGERED       = getInt("v4.intratrigger.minTriggered", 4);
        INTRATRIGGER_ISOLATION_THRESHOLD = getDouble("v4.intratrigger.isolationThreshold", 0.6);
        INTRATRIGGER_PAIR_THRESHOLD      = getDouble("v4.intratrigger.pairThreshold", 0.15);
        INTRATRIGGER_MAX_REPORT          = getInt("v4.intratrigger.maxReport", 8);
        INTRATRIGGER_CLUSTER_ENABLED     = getBoolean("v4.intratrigger.clusterEnabled", true);

        // —— Chat 消息聚合 ——
        CHAT_BATCH_MIN_MESSAGES        = getInt("v4.chat.batchMinMessages", 3);
        CHAT_BATCH_MAX_WAIT_MS         = getLong("v4.chat.batchMaxWaitMs", 5000);
        CHAT_BATCH_COOLDOWN_MS         = getLong("v4.chat.batchCooldownMs", 3000);
        CHAT_BATCH_PRIVATE_MIN_MESSAGES = getInt("v4.chat.batchPrivateMinMessages", 1);

        // —— Prompt 大小管理 ——
        PROMPT_MAX_CHARS          = getInt("v4.prompt.maxChars", 24000);
        POOL_SUMMARY_MAX_UNITS    = getInt("v4.prompt.poolSummaryMaxUnits", 10);
        MAX_METHODOLOGY_CHARS     = getInt("v4.prompt.maxMethodologyChars", 4000);
        PROMPT_TRUNCATION_PRIORITY = getString("v4.prompt.truncationPriority", "methodology,experiences,pool_summary");

        // —— 调度 ——
        COGNITIVE_TICK_MS         = getInt("v4.core.cognitiveTickMs", 2000);
        SINGLE_BOOST_CAP           = getDouble("v4.core.singleBoostCap", 1.0);

        log.info("[CoreConfig] V4 Core 配置初始化完毕: baselineThreshold={}, tickMs={}, poolSize={}, predictChars={}, promptMax={}",
                BASELINE_THRESHOLD, COGNITIVE_TICK_MS, MAX_POOL_SIZE, ACTION_PREDICT_TEXT_MAX_CHARS, PROMPT_MAX_CHARS);
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

    private static boolean getBoolean(String key, boolean defaultValue) {
        String val = props.getProperty(key);
        if (val != null && !val.isBlank()) {
            return Boolean.parseBoolean(val.trim());
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
