package com.cna.apcore.interoception;

import com.cna.apcore.demand.DeviationLevel;

import static com.cna.apcore.demand.DeviationLevel.*;

/**
 * 情绪惯性 PoC：一段时间序列，展示心境如何<b>滞后跟随</b>事件，而非瞬变。
 *
 * 对照：「瞬时目标 resolution」是每轮该有的值（无惯性时直接用它）；
 *       「心境 resolution」是经 AffectiveDynamics 平滑后的实际值——
 *       事件来了它缓缓爬升、事件走了它缓缓回落，还会轻微过冲，像真情绪。
 */
public class AffectiveDynamicsDemo {

    public static void main(String[] args) {
        AffectiveDynamics mind = new AffectiveDynamics(); // 弹簧-阻尼二阶系统

        ModulatorState calm = ModulatorState.NEUTRAL;
        // 意外炸场 + 信息不连贯：高 resolution、负 valence
        ModulatorState shock = ModulatorState.fromLevels(LOW, VERY_HIGH, NORMAL, VERY_LOW, NORMAL, NORMAL);

        ModulatorState[] timeline = {
                calm, calm,
                shock, shock, shock, shock,
                calm, calm, calm, calm, calm, calm
        };
        String[] label = {
                "平静", "平静",
                "意外!", "意外", "意外", "意外",
                "平复", "平复", "平复", "平复", "平复", "平复"
        };

        System.out.println(mind.params() + "  (二阶阻尼：滞后跟随、几乎不过冲)\n");
        System.out.printf("%-4s %-6s %14s %14s %12s%n",
                "轮", "事件", "目标resolution", "心境resolution", "心境valence");
        System.out.println("------------------------------------------------------------");
        for (int i = 0; i < timeline.length; i++) {
            ModulatorState target = timeline[i];
            ModulatorState now = mind.settle(target);
            System.out.printf("%-4d %-6s %12.2f %14.2f %14s%n",
                    i + 1, label[i], target.resolution(), now.resolution(),
                    String.format("%+.2f", now.valence()) + bar(now.resolution()));
        }
        System.out.println("\n看 resolution 那列：意外来时不是瞬间跳到 " +
                String.format("%.2f", shock.resolution()) + "，而是缓缓爬升；");
        System.out.println("事件走后也不瞬间归 1.00，而是带余韵慢慢平复——这就是情绪惯性。");
    }

    /** 简单的视觉条，直观看心境强度的滞后曲线。 */
    private static String bar(double resolution) {
        int n = (int) Math.round((resolution - 1.0) * 10);
        if (n <= 0) return "";
        return "   " + "█".repeat(Math.min(n, 30));
    }
}
