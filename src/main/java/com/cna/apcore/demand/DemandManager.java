package com.cna.apcore.demand;

import com.cna.apcore.model.CognitiveAction;
import com.cna.apcore.model.CognitivePrepareUnit;
import com.cna.apcore.pool.CognitivePreparePool;
import com.cna.apcore.feeling.FeelingsManager;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;

/**
 * 动机计算引擎 — 将认知原始数据翻译为"该花多少心思"的动机信号。
 *
 * <h3>六维认知感受</h3>
 * <ul>
 *   <li><b>把握感 (Confidence)</b> — CF/UE，我见过这事吗</li>
 *   <li><b>惊 (Surprise)</b> — max(0, UE-CF)，有意外信息吗</li>
 *   <li><b>违和感 (Dissonance)</b> — mutualExclusions 加权，有矛盾吗</li>
 *   <li><b>合理感 (Plausibility)</b> — resonance 共鸣强度，逻辑自洽吗</li>
 *   <li><b>期待 (Expectation)</b> — experience_scoring 加权，上次结果好吗</li>
 *   <li><b>压力 (Pressure)</b> — 外部积压归一化，多紧急</li>
 * </ul>
 *
 * <h3>EMA 动态基线</h3>
 * 每个维度维护独立的 EMA tracker。z-score 判定偏离，
 * 基准来自 agent 自己的历史实践——同一套代码在不同环境下会自动校准。
 *
 * <h3>提示词模板</h3>
 * 从 classpath:prompts/demand_info.properties 加载。
 */
@Slf4j
public class DemandManager {

    private static volatile DemandManager INSTANCE;

    // ── 六维 EMA tracker ──
    private final DimensionTracker confidenceTracker = new DimensionTracker();
    private final DimensionTracker surpriseTracker = new DimensionTracker();
    private final DimensionTracker dissonanceTracker = new DimensionTracker();
    private final DimensionTracker plausibilityTracker = new DimensionTracker();
    private final DimensionTracker expectationTracker = new DimensionTracker();
    private final DimensionTracker pressureTracker = new DimensionTracker();

    // ── 提示词模板缓存 ──
    private volatile Properties templates;

    private DemandManager() {
        loadTemplates();
        log.info("[Demand] 🧠 动机计算引擎初始化完成");
    }

    public static DemandManager getInstance() {
        if (INSTANCE == null) {
            synchronized (DemandManager.class) {
                if (INSTANCE == null) {
                    INSTANCE = new DemandManager();
                }
            }
        }
        return INSTANCE;
    }

    // ==========================================
    // 计算
    // ==========================================

    /**
     * 对一个 CognitiveAction 计算六维认知感受。
     *
     * @param action             当前动作
     * @param mutualExclusions   互斥感觉维度列表（可由 FeelingsManager 提供）
     * @param resonanceDissonantCount 谐振分析中的违和节点数
     * @param resonanceResonantCount  谐振分析中的共鸣节点数
     * @param pool              准备池（用于压力计算）
     */
    public DemandState compute(CognitiveAction action,
                               List<Map<String, Object>> mutualExclusions,
                               int resonanceDissonantCount,
                               int resonanceResonantCount,
                               CognitivePreparePool pool) {

        CognitivePrepareUnit src = action.getSourceUnit();
        double cf = action.getCognitiveFamiliarity();
        double ue = src.getSemanticWeight();
        double safeUe = Math.max(ue, 0.001);

        // 1. 把握感 = CF / UE，即熟悉度占理解的比例
        double confidence = cf / safeUe;

        // 2. 惊 = max(0, UE - CF)，意料之外的程度
        double surprise = Math.max(0.0, ue - cf);

        // 3. 违和感 = mutualExclusions v2 dissonance_strength 加权
        double dissonance = 0.0;
        int mxCount = 0;
        if (mutualExclusions != null && !mutualExclusions.isEmpty()) {
            mxCount = mutualExclusions.size();
            double mxStrengthSum = 0.0;
            for (Map<String, Object> mx : mutualExclusions) {
                Object strength = mx.get("dissonance_strength");
                if (strength instanceof Number) {
                    mxStrengthSum += ((Number) strength).doubleValue();
                }
            }
            // dissonance = 平均失调强度 × count，归一化到合理范围
            dissonance = mxCount > 0 ? mxStrengthSum / mxCount : 0.0;
        }

        // 4. 合理感 = 共鸣节点占比 (resonant / total)
        int totalResonanceNodes = resonanceDissonantCount + resonanceResonantCount;
        double plausibility = totalResonanceNodes > 0
                ? (double) resonanceResonantCount / totalResonanceNodes
                : 0.5; // 无数据时中性

        // 5. 期待 = 经验 scoring 加权均值
        double expectation = 0.0;
        List<?> predicts = action.getActionPredicts();
        int predictCount = 0;
        if (predicts != null && !predicts.isEmpty()) {
            double sum = 0.0;
            for (Object p : predicts) {
                if (p instanceof com.cna.apcore.model.ActionPredict ap) {
                    sum += ap.getHelpfulDegree();
                    predictCount++;
                }
            }
            expectation = predictCount > 0 ? sum / predictCount : 0.0;
        }

        // 6. 压力 = 外部积压归一化 (pool external count * avgSE / maxPool)
        double pressure = 0.0;
        int externalCount = 0;
        int poolTotal = 0;
        if (pool != null) {
            List<CognitivePrepareUnit> units = pool.getAllUnits();
            poolTotal = units.size();
            double extSeSum = 0.0;
            for (CognitivePrepareUnit u : units) {
                if (!u.isEndogenous()) {
                    externalCount++;
                    extSeSum += u.getSourcePriority();
                }
            }
            double avgExtSe = externalCount > 0 ? extSeSum / externalCount : 0.0;
            double maxPool = com.cna.apcore.config.CoreConfig.MAX_POOL_SIZE;
            // 外部数量占比 + SE 加权
            pressure = poolTotal > 0
                    ? (double) externalCount / Math.max(poolTotal, maxPool * 0.3)
                    : 0.0;
            pressure = Math.min(1.0, pressure + avgExtSe * 0.5);
        }

        // ── 跑 EMA，获取 z-score 和偏离等级 ──
        confidenceTracker.feed(confidence);
        surpriseTracker.feed(surprise);
        dissonanceTracker.feed(dissonance);
        plausibilityTracker.feed(plausibility);
        expectationTracker.feed(expectation);
        pressureTracker.feed(pressure);

        double cZ = confidenceTracker.zScore(confidence);
        double sZ = surpriseTracker.zScore(surprise);
        double dZ = dissonanceTracker.zScore(dissonance);
        double pZ = plausibilityTracker.zScore(plausibility);
        double eZ = expectationTracker.zScore(expectation);
        double prZ = pressureTracker.zScore(pressure);

        DeviationLevel cLvl = confidenceTracker.describe(confidence);
        DeviationLevel sLvl = surpriseTracker.describe(surprise);
        DeviationLevel dLvl = dissonanceTracker.describe(dissonance);
        DeviationLevel pLvl = plausibilityTracker.describe(plausibility);
        DeviationLevel eLvl = expectationTracker.describe(expectation);
        DeviationLevel prLvl = pressureTracker.describe(pressure);

        log.debug("[Demand] 动机计算: cf={} ue={} → confidence={}(z={} {}) surprise={}(z={} {}) dissonance={}(z={} {}) plausibility={}(z={} {}) expectation={}(z={} {}) pressure={}(z={} {}) mxCount={} pool={}/{}",
                cf, ue, confidence, cZ, cLvl, surprise, sZ, sLvl,
                dissonance, dZ, dLvl, plausibility, pZ, pLvl,
                expectation, eZ, eLvl, pressure, prZ, prLvl,
                mxCount, externalCount, poolTotal);

        return new DemandState(
                confidence, surprise, dissonance, plausibility, expectation, pressure,
                cZ, sZ, dZ, pZ, eZ, prZ,
                cLvl, sLvl, dLvl, pLvl, eLvl, prLvl,
                mxCount, externalCount, poolTotal,
                predictCount > 0 ? expectation : Double.NaN
        );
    }

    // ==========================================
    // Prompt 渲染
    // ==========================================

    /**
     * 将 DemandState 翻译为注入 prompt 的动机分析文本。
     */
    public String renderPrompt(DemandState state) {
        if (templates == null) {
            loadTemplates();
        }

        // 全正常 → 极简输出
        if (state.isAllNormal()) {
            return templates.getProperty("demand.normal", "");
        }

        // 收集所有偏离维度
        List<String> deviatingLines = new ArrayList<>();
        appendIfDeviating(deviatingLines, "confidence", state.confidenceLvl);
        appendIfDeviating(deviatingLines, "surprise", state.surpriseLvl);
        appendIfDeviating(deviatingLines, "dissonance", state.dissonanceLvl);
        appendIfDeviating(deviatingLines, "plausibility", state.plausibilityLvl);
        appendIfDeviating(deviatingLines, "expectation", state.expectationLvl);
        appendIfDeviating(deviatingLines, "pressure", state.pressureLvl);

        // 单维度偏离 → 使用该维度的专用模板
        if (deviatingLines.size() == 1) {
            // 已经通过 appendIfDeviating 添加了渲染后的文本
            return deviatingLines.get(0);
        }

        // 多维度偏离 → 组合模板
        String combinedTemplate = templates.getProperty("demand.combined", "");
        if (combinedTemplate.isEmpty()) {
            return String.join("\n\n", deviatingLines);
        }
        StringBuilder dims = new StringBuilder();
        for (String line : deviatingLines) {
            dims.append(line).append("\n");
        }
        return combinedTemplate.replace("{dimensions}", dims.toString().trim());
    }

    private void appendIfDeviating(List<String> lines, String dim, DeviationLevel lvl) {
        if (lvl == DeviationLevel.NORMAL) return;

        String direction = lvl.name().toLowerCase(); // "high", "low", "very_high", "very_low"
        String key = "demand." + dim + "." + direction;
        String template = templates != null ? templates.getProperty(key) : null;

        if (template != null && !template.isBlank()) {
            lines.add(template);
        } else {
            // 回退：仅维度名+方向
            lines.add("## 动机分析\n" + dim + " " + lvl);
        }
    }

    // ==========================================
    // 配置加载
    // ==========================================

    private void loadTemplates() {
        templates = new Properties();
        try (InputStream is = getClass().getClassLoader()
                .getResourceAsStream("prompts/demand_info.properties")) {
            if (is != null) {
                templates.load(is);
                log.info("[Demand] 📋 加载 {} 条提示词模板", templates.size());
            } else {
                log.warn("[Demand] ⚠️ 未找到 prompts/demand_info.properties，使用空模板");
            }
        } catch (IOException e) {
            log.error("[Demand] ❌ 加载模板失败: {}", e.getMessage());
        }
    }

    /** 强制重新加载模板（调试/热更新） */
    public void reloadTemplates() {
        loadTemplates();
    }

    // ==========================================
    // 查询
    // ==========================================

    public DimensionTracker getConfidenceTracker() { return confidenceTracker; }
    public DimensionTracker getSurpriseTracker() { return surpriseTracker; }
    public DimensionTracker getDissonanceTracker() { return dissonanceTracker; }
    public DimensionTracker getPlausibilityTracker() { return plausibilityTracker; }
    public DimensionTracker getExpectationTracker() { return expectationTracker; }
    public DimensionTracker getPressureTracker() { return pressureTracker; }
}
