package com.cna.apcore.interoception;

import com.cna.apcore.demand.DeviationLevel;

import java.util.ArrayList;
import java.util.List;

import static com.cna.apcore.demand.DeviationLevel.*;

/**
 * 内感受系统 PoC：同一个情绪状态，同时展示 Psi 的两层——
 *   「手」= ModulatorState（拧 selectionScore / 记忆量级 / BFS / 自检周期 / 执行紧迫）
 *   「嘴」= InteroceptionNarrator（收口成一句第一人称心境）
 *
 * 对照他现在的实现：demand 六维只会 renderPrompt 塞一大段进 prompt（哑巴 + 膨胀）。
 * 独立 main，可单独 javac 跑。
 */
public class InteroceptionSystemDemo {

    public static void main(String[] args) {
        scene("陌生 + 违和 + 积压",
                /*conf*/ VERY_LOW, /*surp*/ NORMAL, /*diss*/ HIGH,
                /*plaus*/ NORMAL, /*expe*/ NORMAL, /*pres*/ HIGH,
                List.of(sig("confidence", VERY_LOW, -2.1),
                        sig("dissonance", HIGH, 1.3),
                        sig("pressure", HIGH, 0.9)));

        scene("一切顺手 + 闲 + 来劲",
                HIGH, NORMAL, NORMAL, NORMAL, VERY_HIGH, LOW,
                List.of(sig("confidence", HIGH, 1.0),
                        sig("expectation", VERY_HIGH, 1.8),
                        sig("pressure", LOW, -1.2)));

        scene("意外炸场 + 信息不连贯",
                LOW, VERY_HIGH, NORMAL, VERY_LOW, NORMAL, NORMAL,
                List.of(sig("surprise", VERY_HIGH, 2.4),
                        sig("plausibility", VERY_LOW, -2.0),
                        sig("confidence", LOW, -0.8)));

        scene("一切如常（无情绪偏离）",
                NORMAL, NORMAL, NORMAL, NORMAL, NORMAL, NORMAL,
                new ArrayList<>());
    }

    private static void scene(String label,
                              DeviationLevel conf, DeviationLevel surp, DeviationLevel diss,
                              DeviationLevel plaus, DeviationLevel expe, DeviationLevel pres,
                              List<InteroceptionSignal> signals) {
        ModulatorState m = ModulatorState.fromLevels(conf, surp, diss, plaus, expe, pres);

        System.out.println("========== " + label + " ==========");
        System.out.println("【手 · modulator】 " + m.describe());
        System.out.println("   → 记忆召回   base 5   → " + m.recallCount(5) + " 条经验");
        System.out.println("   → 超图 BFS   base 3   → 深 " + m.bfsDepth(3) + " 层");
        System.out.println("   → 回复预算   base 400 → " + m.replyBudget(400) + " tokens  (受 resolution 加长 / arousal 压缩)");
        System.out.println("   → 自检间隔   base 60s → " + (m.nextSecuringIntervalMs(60_000) / 1000) + "s");
        System.out.println("   → 选择黏性   ×" + String.format("%.2f", m.selectionStickiness())
                + "  (<1 = 更愿意放下当前去查证)");
        System.out.println("   → 执行紧迫   ×" + String.format("%.2f", m.executionUrgency())
                + "  (>1 = 走果断/快路径)");
        System.out.println("   → 趋避偏置   " + String.format("%+.2f", m.persistenceBias())
                + "  (>0 坚持/重试, <0 换策略/放弃)");
        System.out.println("【嘴 · narrator】 " + InteroceptionNarrator.narrate(signals));
        System.out.println();
    }

    private static InteroceptionSignal sig(String dim, DeviationLevel lvl, double z) {
        return new InteroceptionSignal(dim, lvl, z);
    }
}
