package com.cna.apcore.interoception;

import com.cna.apcore.demand.DeviationLevel;

import java.util.List;

import static com.cna.apcore.demand.DeviationLevel.*;

/**
 * 零依赖自验证 runner（项目无 JUnit/test 目录，故不引框架、不碰 pom）。
 * 覆盖 interoception 的核心不变量 + 5 个修过的 bug 的回归测试。
 * 跑法：编进 classpath 后 {@code java ...InteroceptionTests}；全绿退出 0，有失败退出 1。
 * 若 Constantin 日后引入 JUnit，可平移到 src/test/java 一一对应。
 */
public class InteroceptionTests {

    private static int pass = 0, fail = 0;

    public static void main(String[] args) {
        modulatorState();
        affectiveDynamics();
        narrator();

        System.out.println("\n──────────────────────────────");
        System.out.printf("通过 %d / 失败 %d%n", pass, fail);
        if (fail > 0) {
            System.out.println("有测试失败 ✗");
            System.exit(1);
        }
        System.out.println("全部通过 ✓");
    }

    // ========== ModulatorState ==========
    private static void modulatorState() {
        section("ModulatorState");

        // 中性 / 可降级
        ModulatorState n = ModulatorState.NEUTRAL;
        check("neutral 四旋钮全 1.0 + valence 0",
                n.arousal() == 1 && n.selectionThreshold() == 1 && n.resolution() == 1
                        && n.securingRate() == 1 && n.valence() == 0);

        ModulatorState allNormal = ModulatorState.fromLevels(NORMAL, NORMAL, NORMAL, NORMAL, NORMAL, NORMAL);
        check("全 NORMAL 时逐字等于默认行为（可降级）",
                allNormal.recallCount(5) == 5 && allNormal.bfsDepth(3) == 3
                        && allNormal.replyBudget(400) == 400
                        && allNormal.selectionStickiness() == 1.0 && allNormal.valence() == 0);

        // 映射方向
        ModulatorState pressure = ModulatorState.fromLevels(NORMAL, NORMAL, NORMAL, NORMAL, NORMAL, HIGH);
        check("压力高 → arousal>1 且 resolution<1（赶而浅）",
                pressure.arousal() > 1.0 && pressure.resolution() < 1.0);

        ModulatorState unfamiliar = ModulatorState.fromLevels(VERY_LOW, NORMAL, NORMAL, NORMAL, NORMAL, NORMAL);
        check("陌生 → resolution>1 且 securing>1（深想多查）",
                unfamiliar.resolution() > 1.0 && unfamiliar.securingRate() > 1.0);

        ModulatorState dissonant = ModulatorState.fromLevels(NORMAL, NORMAL, VERY_HIGH, NORMAL, NORMAL, NORMAL);
        check("违和 → valence<0（不适）", dissonant.valence() < 0);

        ModulatorState rewarding = ModulatorState.fromLevels(NORMAL, NORMAL, NORMAL, NORMAL, VERY_HIGH, NORMAL);
        check("期待高 → valence>0（趋近）", rewarding.valence() > 0);

        // clamp
        ModulatorState hi = new ModulatorState(100, 100, 100, 100, 100);
        check("clamp 上界：旋钮≤3.0、valence≤1.0",
                hi.resolution() == 3.0 && hi.arousal() == 3.0 && hi.valence() == 1.0);
        ModulatorState lo = new ModulatorState(-9, -9, -9, -9, -9);
        check("clamp 下界：旋钮≥0.3、valence≥-1.0",
                lo.resolution() == 0.3 && lo.arousal() == 0.3 && lo.valence() == -1.0);

        // 回归：BFS / 召回 封顶（曾会指数爆炸）
        ModulatorState deep = new ModulatorState(1, 1, 3.0, 1, 0);
        check("[回归] resolution 极高时召回封顶 ≤12", deep.recallCount(5) <= 12);
        check("[回归] resolution 极高时 BFS 深度封顶 ≤6", deep.bfsDepth(3) <= 6);

        // 回归：arousal 哑巴旋钮（曾算了不用）
        ModulatorState urgent = new ModulatorState(2.0, 1, 1, 1, 0);
        check("[回归] arousal 高 → 回复预算被压缩（arousal 已落地）",
                urgent.replyBudget(400) < ModulatorState.NEUTRAL.replyBudget(400));
        ModulatorState thoughtful = new ModulatorState(1, 1, 2.0, 1, 0);
        check("resolution 高 → 回复预算加长",
                thoughtful.replyBudget(400) > ModulatorState.NEUTRAL.replyBudget(400));

        check("fromDemand(null) 返回 NEUTRAL", ModulatorState.fromDemand(null) == ModulatorState.NEUTRAL);
    }

    // ========== AffectiveDynamics ==========
    private static void affectiveDynamics() {
        section("AffectiveDynamics（惯性）");

        AffectiveDynamics d0 = new AffectiveDynamics();
        check("从中性起步", d0.current().resolution() == 1.0 && d0.current().valence() == 0.0);

        // 滞后：第一步不会瞬达目标
        AffectiveDynamics d1 = new AffectiveDynamics();
        ModulatorState shock = ModulatorState.fromLevels(LOW, VERY_HIGH, NORMAL, VERY_LOW, NORMAL, NORMAL);
        ModulatorState after1 = d1.settle(shock);
        check("[惯性] 第一步滞后：介于中性与目标之间",
                after1.resolution() > 1.0 && after1.resolution() < shock.resolution());

        // 收敛：恒定目标多轮后逼近
        AffectiveDynamics d2 = new AffectiveDynamics();
        ModulatorState target = ModulatorState.fromLevels(NORMAL, NORMAL, HIGH, NORMAL, NORMAL, NORMAL);
        ModulatorState last = null;
        for (int i = 0; i < 60; i++) last = d2.settle(target);
        check("恒定目标 60 轮后收敛（securing 逼近目标）",
                Math.abs(last.securingRate() - target.securingRate()) < 0.05);

        // 回归：欠阻尼振荡（曾在平复期冲过中性、resolution 跌破下限、valence 反弹翻正）
        AffectiveDynamics d3 = new AffectiveDynamics();
        for (int i = 0; i < 4; i++) d3.settle(shock);          // 意外持续
        double minRes = Double.MAX_VALUE, maxVal = -Double.MAX_VALUE;
        for (int i = 0; i < 12; i++) {                          // 平复
            ModulatorState s = d3.settle(ModulatorState.NEUTRAL);
            minRes = Math.min(minRes, s.resolution());
            maxVal = Math.max(maxVal, s.valence());
        }
        check("[回归] 平复期 resolution 不下冲（≥0.85）", minRes >= 0.85);
        check("[回归] 平复期 valence 不反弹翻正（≤0.05）", maxVal <= 0.05);

        // null 目标 → 持续漂回中性。
        // 注意：惯性是二阶动量系统，撤刺激后会先微冲再回落，故验「持续多轮最终回中性」而非「立刻回落」。
        AffectiveDynamics d4 = new AffectiveDynamics();
        for (int i = 0; i < 4; i++) d4.settle(shock);
        double peak = d4.current().resolution();
        for (int i = 0; i < 25; i++) d4.settle(null);
        check("持续 settle(null) 最终漂回中性（且低于峰值）",
                Math.abs(d4.current().resolution() - 1.0) < 0.1 && d4.current().resolution() < peak);
    }

    // ========== Narrator ==========
    private static void narrator() {
        section("InteroceptionNarrator（嘴）");

        check("空 signals → 平稳的第一人称句",
                InteroceptionNarrator.narrate(List.of()).contains("我")
                        && InteroceptionNarrator.narrate(List.of()).endsWith("。"));

        check("全 NORMAL → 平稳句",
                InteroceptionNarrator.narrate(List.of(
                        new InteroceptionSignal("confidence", NORMAL, 0.1))).contains("平稳"));

        // 回归：叠主语 bug（曾输出「我这事我几乎没碰过」）
        String s = InteroceptionNarrator.narrate(List.of(
                new InteroceptionSignal("confidence", VERY_LOW, -2.1)));
        check("[回归] 不叠主语（无「我我」「我这事我」）",
                !s.contains("我我") && !s.contains("我这事我"));

        // top-K：限制进入意识的主导感受数。
        // 注意：词典短语自带逗号，不能用逗号数推断短语数，改用 k=1 vs k=2 的长度对比。
        List<InteroceptionSignal> four = List.of(
                new InteroceptionSignal("confidence", VERY_LOW, -2.1),
                new InteroceptionSignal("dissonance", HIGH, 1.3),
                new InteroceptionSignal("pressure", HIGH, 0.9),
                new InteroceptionSignal("surprise", VERY_HIGH, 2.0));
        check("top-K 限制：k=1 严格短于 k=2",
                InteroceptionNarrator.narrate(four, 1, 200).length()
                        < InteroceptionNarrator.narrate(four, 2, 200).length());

        // 预算：更小预算应不长于更大预算
        List<InteroceptionSignal> sigs = List.of(
                new InteroceptionSignal("confidence", VERY_LOW, -2.1),
                new InteroceptionSignal("dissonance", VERY_HIGH, 1.8),
                new InteroceptionSignal("pressure", VERY_HIGH, 1.5));
        check("预算约束：小预算不长于大预算",
                InteroceptionNarrator.narrate(sigs, 5, 18).length()
                        <= InteroceptionNarrator.narrate(sigs, 5, 200).length());

        // 最偏离的优先进入意识（salience 排序）
        String ordered = InteroceptionNarrator.narrate(List.of(
                new InteroceptionSignal("pressure", HIGH, 0.5),       // 弱
                new InteroceptionSignal("dissonance", VERY_HIGH, 3.0) // 强
        ), 1, 60);
        check("salience 排序：最偏离者优先（违和先于压力）",
                ordered.contains("不对劲") || ordered.contains("违和"));
    }

    // ── helpers ──
    private static void section(String name) {
        System.out.println("\n[" + name + "]");
    }

    private static void check(String name, boolean cond) {
        if (cond) {
            pass++;
            System.out.println("  ✓ " + name);
        } else {
            fail++;
            System.out.println("  ✗ FAIL: " + name);
        }
    }

}
