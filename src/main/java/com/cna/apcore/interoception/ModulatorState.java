package com.cna.apcore.interoception;

import com.cna.apcore.demand.DemandState;
import com.cna.apcore.demand.DeviationLevel;

import static com.cna.apcore.demand.DeviationLevel.*;

/**
 * 情绪状态（Psi/MicroPsi）—— 缺失的那只「手」+ 趋避轴。
 *
 * <p>问题：apcore 把 demand 六维（把握感/惊/违和/合理/期待/压力）算得精精细细，
 * 然后只 {@code renderPrompt} 塞进 prompt——情绪只会「说」，不会「动手」。
 * 而 2026 机制研究（arxiv 2604.00005）实测：对黑盒 LLM，把情绪塞 prompt 是
 * 「最不精确」的注入；正解是让情绪在<b>架构层</b>调决策阈值/策略。
 *
 * <p>解法（Dörner Psi / Bach MicroPsi 正典）：情绪 = 一组<b>认知调节器</b>的配置
 * <b>＋ pleasure/distress（valence）评价轴</b>。Psi 里二者语义不同：
 * <ul>
 *   <li><b>4 个 modulator（调「怎么想」）</b>
 *     <ul>
 *       <li><b>arousal</b>：行动就绪 / 执行速度 / 回复紧迫</li>
 *       <li><b>selectionThreshold</b>：咬死当前任务 vs 易被新输入打断</li>
 *       <li><b>resolution</b>：想多深、召回多少记忆、联想多远</li>
 *       <li><b>securingRate</b>：多久环顾一次、做多少背景查证</li>
 *     </ul>
 *   </li>
 *   <li><b>valence（调「趋还是避」）</b>：pleasure/distress 信号，驱动趋近/回避与强化学习。
 *       >0=以前是好体验→坚持/重试；<0=坏体验→换策略/放弃。范围 [-1, +1]，中性 0。</li>
 * </ul>
 *
 * <p>四个旋钮去拧他系统里现成的量（selectionScore / Scale→记忆量级 / 超图 BFS 深度 /
 * TickAction 周期），valence 去拧 continueWeight 趋避。与 {@link InteroceptionNarrator}（嘴）
 * 合为 Psi 的两层：调节层（手）+ 反思体验层（嘴）。瞬时值经 {@link AffectiveDynamics}
 * 平滑成「带惯性的心境」。
 *
 * <p><b>可降级</b>：四旋钮默认 {@code 1.0}、valence 默认 {@code 0}，逐字等于他现在的行为。
 */
public record ModulatorState(double arousal, double selectionThreshold,
                             double resolution, double securingRate, double valence) {

    /** 认知旋钮安全范围——多条情绪叠乘后 clamp，防极端值。 */
    private static final double MOD_MIN = 0.3, MOD_MAX = 3.0;

    /** 消费侧绝对上限：防 resolution 偏高时把召回/BFS 放大到性能炸弹（BFS 随深度指数爆炸）。 */
    private static final int MAX_RECALL = 12;
    private static final int MAX_BFS_DEPTH = 6;

    /** 中性状态：四旋钮全 1.0、valence 0，等于「无情绪调节」= 系统默认行为。 */
    public static final ModulatorState NEUTRAL = new ModulatorState(1.0, 1.0, 1.0, 1.0, 0.0);

    public ModulatorState {
        arousal = clampMod(arousal);
        selectionThreshold = clampMod(selectionThreshold);
        resolution = clampMod(resolution);
        securingRate = clampMod(securingRate);
        valence = clampVal(valence);
    }

    private static double clampMod(double v) { return Math.max(MOD_MIN, Math.min(MOD_MAX, v)); }
    private static double clampVal(double v) { return Math.max(-1.0, Math.min(1.0, v)); }

    /** 生产入口：从真实 {@link DemandState} 读六维偏离（public 字段）映射成情绪状态。 */
    public static ModulatorState fromDemand(DemandState d) {
        if (d == null) return NEUTRAL;
        return fromLevels(d.confidenceLvl, d.surpriseLvl, d.dissonanceLvl,
                d.plausibilityLvl, d.expectationLvl, d.pressureLvl);
    }

    /**
     * 核心映射：appraisal（六维偏离）→ 情绪状态。解耦版，便于独立测试。
     * 映射方向取自 Psi/MicroPsi 标准语义（如愤怒=高arousal/低resolution/高threshold）。
     */
    public static ModulatorState fromLevels(DeviationLevel confidence, DeviationLevel surprise,
                                            DeviationLevel dissonance, DeviationLevel plausibility,
                                            DeviationLevel expectation, DeviationLevel pressure) {
        double arousal = 1.0, threshold = 1.0, resolution = 1.0, securing = 1.0, valence = 0.0;

        // 压力：赶 → 快而浅；闲 → 慢而深
        if (pressure == HIGH)      { arousal *= 1.3;  resolution *= 0.8; }
        if (pressure == VERY_HIGH) { arousal *= 1.6;  resolution *= 0.6; }
        if (pressure == LOW)       { arousal *= 0.85; resolution *= 1.2; }

        // 把握感：陌生 → 深想多查；有把握/太熟 → 不必细想
        if (confidence == HIGH)      { resolution *= 0.9; }
        if (confidence == VERY_HIGH) { resolution *= 0.8; }
        if (confidence == LOW)       { resolution *= 1.3; securing *= 1.2; }
        if (confidence == VERY_LOW)  { resolution *= 1.6; securing *= 1.4; valence -= 0.15; }

        // 惊：意外 → 深想、多查、愿被打断
        if (surprise == HIGH)      { resolution *= 1.3; securing *= 1.2; threshold *= 0.85; }
        if (surprise == VERY_HIGH) { resolution *= 1.5; securing *= 1.3; threshold *= 0.7; }

        // 违和：矛盾 → 查证、愿被打断、不适
        if (dissonance == HIGH)      { securing *= 1.3; threshold *= 0.85; valence -= 0.2; }
        if (dissonance == VERY_HIGH) { securing *= 1.5; threshold *= 0.7;  valence -= 0.4; }

        // 合理感低：不连贯 → 多查、深想、不适
        if (plausibility == LOW)      { securing *= 1.2; resolution *= 1.2; valence -= 0.15; }
        if (plausibility == VERY_LOW) { securing *= 1.4; resolution *= 1.3; valence -= 0.30; }

        // 期待（以前效果）= valence 主来源 + 提神
        if (expectation == HIGH)      { arousal *= 1.15; valence += 0.30; }
        if (expectation == VERY_HIGH) { arousal *= 1.3;  valence += 0.50; }
        if (expectation == LOW)       { arousal *= 0.9;  valence -= 0.30; }
        if (expectation == VERY_LOW)  { arousal *= 0.8;  resolution *= 1.1; valence -= 0.50; }

        return new ModulatorState(arousal, threshold, resolution, securing, valence);
    }

    // ── 消费点：modulator 怎么拧他系统里现成的量（base 值取自他系统的默认） ──

    /** 记忆召回量级（蓝图第 2 点）：resolution 高 → 召回更多经验。封顶防过量。 */
    public int recallCount(int base) {
        return Math.max(1, Math.min(MAX_RECALL, (int) Math.round(base * resolution)));
    }

    /** 超图 BFS 深度：resolution 高 → 联想得更远。硬封顶防指数爆炸。 */
    public int bfsDepth(int base) {
        return Math.max(1, Math.min(MAX_BFS_DEPTH, (int) Math.round(base * resolution)));
    }

    /** 回复长度预算：想得深(resolution↑)→ 舍得展开；紧迫(arousal↑)→ 压缩。arousal 在此落地。 */
    public int replyBudget(int base) {
        return Math.max(1, (int) Math.round(base * resolution / arousal));
    }

    /** 下次自检/巡视间隔：securing 高 → 更频繁环顾（间隔缩短）。 */
    public long nextSecuringIntervalMs(long base) {
        return (long) (base / securingRate);
    }

    /** 给 selectionScore 的「当前任务黏性」乘数：threshold 高 → 咬死当前、难被新输入打断。 */
    public double selectionStickiness() {
        return selectionThreshold;
    }

    /** 执行紧迫度（arousal 的第二个落点）：>1 建议走果断/快路径，<1 可从容。 */
    public double executionUrgency() {
        return arousal;
    }

    /**
     * 趋避偏置（valence/pleasure-distress 的落点，与 4 个认知 modulator 语义不同）：
     * >0 → 以前是「好」体验，倾向坚持当前路线/重试；<0 → 「坏」体验，倾向换策略/放弃。
     */
    public double persistenceBias() {
        return valence;
    }

    /** 给 continueWeight 的趋避偏移建议：正 valence 加权（坚持）、负 valence 减权（放弃）。 */
    public double continueWeightDelta(double maxBoost) {
        return valence * maxBoost;
    }

    public String describe() {
        return String.format("arousal=%.2f  selThreshold=%.2f  resolution=%.2f  securing=%.2f  valence=%+.2f",
                arousal, selectionThreshold, resolution, securingRate, valence);
    }
}
