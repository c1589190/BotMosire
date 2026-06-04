package com.cna.apcore.interoception;

/**
 * 情绪惯性层 —— 让情绪成为「有记忆的心境」，而非「每轮瞬算的查表反应」。
 *
 * <p>真实情绪不会瞬变：上一刻的焦虑会拖到下一刻，再慢慢平复；好心情也有余韵。
 * {@link ModulatorState#fromDemand} 给的是「此刻该有的瞬时目标」，但若每轮直接用它，
 * 系统的情绪就是无记忆的——上一秒炸毛下一秒能瞬间平静，不像活物。
 *
 * <p>本类用<b>弹簧-阻尼二阶系统</b>让情绪平滑地<b>滞后跟随</b>目标：
 * <pre>
 *   velocity = damping · velocity + response · (target - current)
 *   current  = current + velocity
 * </pre>
 * <ul>
 *   <li><b>response</b>（弹簧刚度，拉向目标的强度）：大 → 跟得紧、变得快。</li>
 *   <li><b>damping</b>（速度保留率，阻尼）：<b>小 = 阻尼大 = 不易过冲</b>。</li>
 * </ul>
 * 默认 response=0.25 / damping=0.35 调在临界阻尼附近——滞后跟随且几乎不过冲。
 *
 * <p>注意：早期版本用单一动量 μ=0.75（2026 affective-dynamics 对小幅噪声信号的推荐值），
 * 但对「事件来→持续→撤」这种阶跃，μ 高会<b>欠阻尼振荡</b>：情绪平复时冲过中性、
 * 反弹成失真状态（如 resolution 跌破下限、valence 由负翻正）。拆成 response/damping
 * 双参数后可独立压住过冲，故改用本式。
 *
 * <p>用法：每个认知周期算瞬时目标 {@code ModulatorState.fromDemand(d)}，喂给 {@link #settle}，
 * 拿回「带惯性的当前心境」去消费。本类<b>有状态</b>，每个 agent 持有一个实例。
 */
public class AffectiveDynamics {

    private static final double DEFAULT_RESPONSE = 0.25;
    private static final double DEFAULT_DAMPING = 0.35;
    private static final double MOD_MIN = 0.3, MOD_MAX = 3.0;

    private final double response;
    private final double damping;

    // 当前心境（从中性起步）
    private double arousal = 1.0, selThreshold = 1.0, resolution = 1.0, securing = 1.0, valence = 0.0;
    // 各维速度
    private double vAro = 0, vSel = 0, vRes = 0, vSec = 0, vVal = 0;

    public AffectiveDynamics() {
        this(DEFAULT_RESPONSE, DEFAULT_DAMPING);
    }

    public AffectiveDynamics(double response, double damping) {
        this.response = clamp01(response);
        this.damping = clamp01(damping);
    }

    /**
     * 喂入瞬时目标，二阶动力学平滑一步，返回带惯性的当前心境。
     *
     * @param target 本轮从 demand 算出的瞬时目标情绪；null 视为中性（情绪自然向平静漂移）
     */
    public ModulatorState settle(ModulatorState target) {
        ModulatorState t = (target != null) ? target : ModulatorState.NEUTRAL;

        vAro = damping * vAro + response * (t.arousal() - arousal);
        arousal = clampMod(arousal + vAro);

        vSel = damping * vSel + response * (t.selectionThreshold() - selThreshold);
        selThreshold = clampMod(selThreshold + vSel);

        vRes = damping * vRes + response * (t.resolution() - resolution);
        resolution = clampMod(resolution + vRes);

        vSec = damping * vSec + response * (t.securingRate() - securing);
        securing = clampMod(securing + vSec);

        vVal = damping * vVal + response * (t.valence() - valence);
        valence = clampVal(valence + vVal);

        return current();
    }

    /** 当前心境快照（不推进）。 */
    public ModulatorState current() {
        return new ModulatorState(arousal, selThreshold, resolution, securing, valence);
    }

    /** 参数描述（调试用）。 */
    public String params() {
        return String.format("response=%.2f, damping=%.2f", response, damping);
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(0.99, v));
    }

    private static double clampMod(double v) {
        return Math.max(MOD_MIN, Math.min(MOD_MAX, v));
    }

    private static double clampVal(double v) {
        return Math.max(-1.0, Math.min(1.0, v));
    }
}
