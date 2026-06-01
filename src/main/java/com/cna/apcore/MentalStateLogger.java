package com.cna.apcore;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * 心智状态日志 — 记录 Agent 认知机制内部的微观变化。
 *
 * 输出独立于应用日志的 JSON-Lines 文件，每行一个事件对象，
 * 可用 jq/grep/Excel 直接分析。聚焦于对改进 Agent 机制有直接帮助的数据：
 *
 * - 感觉维度的激活/刺激/传播
 * - 语义疲劳的计算和累积
 * - 注意力资源的分配变化
 * - 准备池的选择过程和得分分解
 * - 动作完成的综合结算
 *
 * 文件位置: logs/mental_YYYYMMDD.log
 * 格式: 每行一个 JSON 对象，unixtime 毫秒 + 事件类型 + 事件数据
 */
@Slf4j
public class MentalStateLogger {

    private static volatile MentalStateLogger INSTANCE;

    private final ObjectMapper mapper;
    private final SimpleDateFormat tsFormat;
    private final Path logDir;
    private Writer writer;
    private String currentDate;

    private MentalStateLogger() {
        this.mapper = new ObjectMapper();
        this.tsFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS");
        this.logDir = Paths.get("logs");
        this.currentDate = new SimpleDateFormat("yyyyMMdd").format(new Date());
        ensureWriter();
        log.info("[MentalLog] 📝 心智状态日志已启动: {}", getLogPath());
    }

    public static MentalStateLogger getInstance() {
        if (INSTANCE == null) {
            synchronized (MentalStateLogger.class) {
                if (INSTANCE == null) {
                    INSTANCE = new MentalStateLogger();
                }
            }
        }
        return INSTANCE;
    }

    // ==========================================
    // 文件管理
    // ==========================================

    private Path getLogPath() {
        return logDir.resolve("mental_" + currentDate + ".log");
    }

    private synchronized void ensureWriter() {
        try {
            String today = new SimpleDateFormat("yyyyMMdd").format(new Date());
            if (!today.equals(currentDate) || writer == null) {
                if (writer != null) {
                    writer.close();
                }
                currentDate = today;
                Files.createDirectories(logDir);
                writer = new BufferedWriter(
                        new OutputStreamWriter(
                                new FileOutputStream(getLogPath().toFile(), true),
                                StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            log.error("[MentalLog] 无法打开日志文件: {}", getLogPath(), e);
        }
    }

    private synchronized void writeLine(ObjectNode event) {
        try {
            ensureWriter();
            if (writer != null) {
                event.put("ts", tsFormat.format(new Date()));
                String line = mapper.writeValueAsString(event);
                writer.write(line);
                writer.write('\n');
                writer.flush();
            }
        } catch (IOException e) {
            log.error("[MentalLog] 写入失败", e);
        }
    }

    /** 关闭（应用退出时调用） */
    public synchronized void close() {
        try {
            if (writer != null) {
                writer.flush();
                writer.close();
                writer = null;
            }
        } catch (IOException e) {
            log.error("[MentalLog] 关闭失败", e);
        }
    }

    // ==========================================
    // 事件记录方法
    // ==========================================

    // ── 感觉维度 ──

    /** 感觉维度被刺激/激活 */
    public void feelingStimulated(int dimId, String concept, boolean isNew,
                                  double noveltyWeight, int activationCount) {
        ObjectNode ev = mapper.createObjectNode();
        ev.put("event", "FEELING_STIMULATED");
        ev.put("dim_id", dimId);
        ev.put("concept", concept);
        ev.put("is_new", isNew);
        ev.put("novelty_weight", round(noveltyWeight, 4));
        ev.put("activation_count", activationCount);
        writeLine(ev);
    }

    /** action_feelings 感觉维度结算 */
    public void feelingAction(int dimId, String concept, String relation) {
        ObjectNode ev = mapper.createObjectNode();
        ev.put("event", "FEELING_ACTION");
        ev.put("dim_id", dimId);
        ev.put("concept", concept != null ? concept : "");
        ev.put("relation", relation != null ? relation : "");
        writeLine(ev);
    }

    /** 经验打分传播到感觉维度 */
    public void feelingExperienceScored(int expId, double score, int propagatedDimCount) {
        ObjectNode ev = mapper.createObjectNode();
        ev.put("event", "FEELING_EXP_SCORED");
        ev.put("exp_id", expId);
        ev.put("score", round(score, 2));
        ev.put("propagated_dims", propagatedDimCount);
        writeLine(ev);
    }

    /** 感觉谐振分析结果 */
    public void feelingResonance(int dissonantCount, int consonantCount,
                                  double dissonanceScoreMax) {
        ObjectNode ev = mapper.createObjectNode();
        ev.put("event", "FEELING_RESONANCE");
        ev.put("dissonant_count", dissonantCount);
        ev.put("consonant_count", consonantCount);
        ev.put("dissonance_score_max", round(dissonanceScoreMax, 4));
        writeLine(ev);
    }

    /** 违和感累积 */
    public void feelingDissonanceAccumulated(int curiosityCount, double peakDissonance) {
        ObjectNode ev = mapper.createObjectNode();
        ev.put("event", "FEELING_DISSONANCE");
        ev.put("curiosity_count", curiosityCount);
        ev.put("peak_dissonance", round(peakDissonance, 4));
        writeLine(ev);
    }

    /** 互斥感觉维度检测 */
    public void feelingMutualExclusion(int count, String topConcepts) {
        ObjectNode ev = mapper.createObjectNode();
        ev.put("event", "FEELING_MUTUAL_EX");
        ev.put("candidate_count", count);
        ev.put("top_concepts", topConcepts);
        writeLine(ev);
    }

    // ── 语义疲劳 ──

    /** 为选择计算了单元疲劳 */
    public void fatigueComputed(String unitUuid, double fatigue, int historySize,
                                 double topSimilarity, int currentTick) {
        ObjectNode ev = mapper.createObjectNode();
        ev.put("event", "FATIGUE_COMPUTED");
        ev.put("uuid", shortUuid(unitUuid));
        ev.put("fatigue", round(fatigue, 4));
        ev.put("history_size", historySize);
        ev.put("top_similarity", round(topSimilarity, 4));
        ev.put("tick", currentTick);
        writeLine(ev);
    }

    /** 记录了一条处理过的 action 到疲劳历史 */
    public void fatigueRecorded(int historySize, int currentTick) {
        ObjectNode ev = mapper.createObjectNode();
        ev.put("event", "FATIGUE_RECORDED");
        ev.put("history_size", historySize);
        ev.put("tick", currentTick);
        writeLine(ev);
    }

    /** 疲劳历史清理 */
    public void fatiguePruned(int removed, int remaining) {
        ObjectNode ev = mapper.createObjectNode();
        ev.put("event", "FATIGUE_PRUNED");
        ev.put("removed", removed);
        ev.put("remaining", remaining);
        writeLine(ev);
    }

    // ── 注意力 ──

    /** 注意力分配到单元 */
    public void attentionAllocated(String unitUuid, double amount, double attractiveness,
                                    double fatigue, boolean endogenous) {
        ObjectNode ev = mapper.createObjectNode();
        ev.put("event", "ATTENTION_ALLOCATED");
        ev.put("uuid", shortUuid(unitUuid));
        ev.put("amount", round(amount, 4));
        ev.put("attractiveness", round(attractiveness, 4));
        ev.put("fatigue", round(fatigue, 3));
        ev.put("endogenous", endogenous);
        writeLine(ev);
    }

    /** 注意力池整体状态 */
    public void attentionTick(double regenerated, double allocated, double poolRemaining,
                               int attendedUnits, int endogenousAttended, int decayedCount) {
        ObjectNode ev = mapper.createObjectNode();
        ev.put("event", "ATTENTION_TICK");
        ev.put("regenerated", round(regenerated, 2));
        ev.put("allocated", round(allocated, 2));
        ev.put("pool_remaining", round(poolRemaining, 2));
        ev.put("attended_units", attendedUnits);
        ev.put("endogenous_attended", endogenousAttended);
        ev.put("decayed_units", decayedCount);
        writeLine(ev);
    }

    // ── 准备池选择 ──

    /** 选中了一个单元准备执行 */
    public void unitSelected(String unitUuid, double score, double se, double attn,
                              double ue, double tickFactor, double cw, double fatigue,
                              boolean endogenous, String textPreview, int poolSize) {
        ObjectNode ev = mapper.createObjectNode();
        ev.put("event", "UNIT_SELECTED");
        ev.put("uuid", shortUuid(unitUuid));
        ev.put("score", round(score, 4));
        ev.put("se", round(se, 4));
        ev.put("attn", round(attn, 4));
        ev.put("ue", round(ue, 4));
        ev.put("tick_factor", round(tickFactor, 3));
        ev.put("cw", round(cw, 4));
        ev.put("fatigue", round(fatigue, 4));
        ev.put("endogenous", endogenous);
        ev.put("text_preview", textPreview != null ? textPreview.substring(0, Math.min(60, textPreview.length())) : "");
        ev.put("pool_size_after", poolSize);
        writeLine(ev);
    }

    /** 选择时各候选单元的得分排名（只在有竞争时记录 top-5） */
    public void unitSelectionRanking(ObjectNode[] rankings) {
        // rankings 由调用方构建，直接写入
        for (ObjectNode r : rankings) {
            writeLine(r);
        }
    }

    // ── 动作结算 ──

    /** 一轮认知动作完成 */
    public void actionComplete(int tick, int toolCallCount, int toolResultCount,
                                int expId, int stimulatedDimCount, long llmElapsedMs,
                                int poolSize, double cf, double accident, int ueConceptCount) {
        ObjectNode ev = mapper.createObjectNode();
        ev.put("event", "ACTION_COMPLETE");
        ev.put("tick", tick);
        ev.put("tool_calls", toolCallCount);
        ev.put("tool_results", toolResultCount);
        ev.put("exp_id", expId);
        ev.put("stimulated_dims", stimulatedDimCount);
        ev.put("llm_elapsed_ms", llmElapsedMs);
        ev.put("pool_size", poolSize);
        ev.put("cf", round(cf, 4));
        ev.put("accident", round(accident, 4));
        ev.put("ue_concepts", ueConceptCount);
        writeLine(ev);
    }

    // ── 注意力态度 ──

    /** 注意力态度被行为驱动调节 */
    public void attentionAttitudeBoosted(int dimId, String concept, double delta) {
        ObjectNode ev = mapper.createObjectNode();
        ev.put("event", "ATTENTION_ATTITUDE_BOOSTED");
        ev.put("dim_id", dimId);
        ev.put("concept", concept != null ? concept : "");
        ev.put("delta", round(delta, 4));
        writeLine(ev);
    }

    /** 注意力态度乘数计算结果 */
    public void attentionAttitudeMultiplier(String uuid, double multiplier, int matchedFeelings) {
        ObjectNode ev = mapper.createObjectNode();
        ev.put("event", "ATTENTION_ATTITUDE_MULTIPLIER");
        ev.put("uuid", shortUuid(uuid));
        ev.put("multiplier", round(multiplier, 4));
        ev.put("matched_feelings", matchedFeelings);
        writeLine(ev);
    }

    // ── 池状态快照 ──

    /** 定期池状态快照（每 N tick） */
    public void poolSnapshot(int tick, int poolSize, int exogenousCount,
                              int endogenousCount, double avgFatigue, double avgSE) {
        ObjectNode ev = mapper.createObjectNode();
        ev.put("event", "POOL_SNAPSHOT");
        ev.put("tick", tick);
        ev.put("pool_size", poolSize);
        ev.put("exogenous_count", exogenousCount);
        ev.put("endogenous_count", endogenousCount);
        ev.put("avg_fatigue", round(avgFatigue, 4));
        ev.put("avg_se", round(avgSE, 4));
        writeLine(ev);
    }

    /** 单元过期被清理 */
    public void poolUnitExpired(String unitUuid, int tick, boolean endogenous, double cw) {
        ObjectNode ev = mapper.createObjectNode();
        ev.put("event", "POOL_UNIT_EXPIRED");
        ev.put("uuid", shortUuid(unitUuid));
        ev.put("tick", tick);
        ev.put("endogenous", endogenous);
        ev.put("cw", round(cw, 4));
        writeLine(ev);
    }

    // ==========================================
    // 工具方法
    // ==========================================

    private static String shortUuid(String uuid) {
        if (uuid == null) return "null";
        return uuid.length() > 8 ? uuid.substring(0, 8) : uuid;
    }

    private static double round(double v, int places) {
        double factor = Math.pow(10, places);
        return Math.round(v * factor) / factor;
    }

    /** 创建排名条目（供调用方构建排名列表） */
    public ObjectNode createRankEntry(String unitUuid, double score, double se, double attn,
                                       double ue, double tickFactor, double cw, double fatigue,
                                       boolean endogenous) {
        ObjectNode ev = mapper.createObjectNode();
        ev.put("event", "UNIT_RANKED");
        ev.put("uuid", shortUuid(unitUuid));
        ev.put("score", round(score, 4));
        ev.put("se", round(se, 4));
        ev.put("attn", round(attn, 4));
        ev.put("ue", round(ue, 4));
        ev.put("tick_factor", round(tickFactor, 3));
        ev.put("cw", round(cw, 4));
        ev.put("fatigue", round(fatigue, 4));
        ev.put("endogenous", endogenous);
        return ev;
    }
}
