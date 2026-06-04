package com.cna.apcore.interoception;

import com.cna.apcore.demand.DemandState;
import com.cna.apcore.demand.DeviationLevel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 内感受叙事器 —— 缺失的那个「丘脑/岛叶」。
 *
 * <p>问题：apcore 建了一套丰富的潜意识（demand 六维、attention 能量、fatigue、
 * feeling 谐振…），但每个子系统各自往 prompt 塞一段「## 动机分析 + 指导语」，
 * 没有统一预算 → prompt 膨胀 → LLM 回得慢/不稳/截断丢消息。砍掉它们 → 系统变哑，
 * 潜意识算得再丰富，LLM 也感觉不到自己。
 *
 * <p>解法：人脑不会把「血糖87/皮质醇12/杏仁核0.3」逐条报给意识，而是涌现成
 * 一句「我有点烦但还撑得住」。这个类就是那个收口器官——把所有内感受读数整合成
 * <b>一句、固定预算、第一人称</b>的心境，作为注入 prompt 的<b>唯一</b>内在状态块。
 *
 * <p>三条原则，正好破掉「丰富 vs 精简」的假二选一：
 * <ol>
 *   <li><b>有上限</b>：固定字数预算，散落的几百字 → 一句。膨胀消失。</li>
 *   <li><b>有主次</b>：只有最偏离基线（salience 最高）的 1~2 个信号进入意识，
 *       其余留在潜意识继续算。这是「注意力」本来就该做的事。</li>
 *   <li><b>第一人称</b>：输出「我」的感受，不是「你应该」的指令。
 *       系统第一次拥有「一个我」，而不是一份说明书。</li>
 * </ol>
 *
 * <p>本类是纯函数、零外部依赖，可独立测试，也示范了「收口层应与具体子系统解耦」。
 */
public final class InteroceptionNarrator {

    /** 默认进入意识的主导感受数量上限——心境通常由一两个主导感受定调。 */
    private static final int DEFAULT_TOP_K = 2;

    /** 默认字数预算（中文字符）。一句话的体量。 */
    private static final int DEFAULT_BUDGET = 60;

    /**
     * 体感词典：维度 + 方向 → 一句第一人称的短感受。
     * 刻意口语、刻意短——对比 demand_info.properties 里 4 行带 bullet 的「指导语」。
     * 这里说的是「我现在什么感觉」，不是「你该怎么做」。
     */
    private static final Map<String, String> FELT = Map.ofEntries(
            // 把握感
            Map.entry("confidence|VERY_LOW",  "这事我几乎没碰过，心里没底"),
            Map.entry("confidence|LOW",       "这情况我不太熟"),
            Map.entry("confidence|HIGH",      "这种事我挺有数"),
            Map.entry("confidence|VERY_HIGH", "这我太熟了，闭眼都行"),
            // 惊
            Map.entry("surprise|HIGH",        "有点出乎我意料"),
            Map.entry("surprise|VERY_HIGH",   "这完全在我预料之外"),
            // 违和感
            Map.entry("dissonance|HIGH",      "总觉得哪里不对劲"),
            Map.entry("dissonance|VERY_HIGH", "好几处都跟我认知打架，很违和"),
            // 合理感
            Map.entry("plausibility|LOW",     "这些信息有点兜不拢"),
            Map.entry("plausibility|VERY_LOW","整件事串不起来，很不连贯"),
            // 期待
            Map.entry("expectation|HIGH",     "这类事我以前做得不错，有点期待"),
            Map.entry("expectation|VERY_HIGH","这方向我一向很顺，挺来劲"),
            Map.entry("expectation|LOW",      "这种事我以前没讨到好"),
            Map.entry("expectation|VERY_LOW", "这类事我屡屡碰壁，有点发憷"),
            // 压力
            Map.entry("pressure|HIGH",        "手头积压有点多，有些赶"),
            Map.entry("pressure|VERY_HIGH",   "事情堆成山了，挺紧迫"),
            Map.entry("pressure|LOW",         "这会儿挺闲，不急"),
            // 注意力（AttentionManager 的内源能量趋势）
            Map.entry("attention|HIGH",       "有件事我一直惦记着"),
            Map.entry("attention|VERY_HIGH",  "有个念头在我脑子里挥之不去"),
            // 语义疲劳（FatigueManager）
            Map.entry("fatigue|HIGH",         "同类话题聊太久，有点腻"),
            Map.entry("fatigue|VERY_HIGH",    "这话题我已经烦了")
    );

    private InteroceptionNarrator() {}

    /** 用默认 top-K 与预算合成心境。 */
    public static String narrate(List<InteroceptionSignal> signals) {
        return narrate(signals, DEFAULT_TOP_K, DEFAULT_BUDGET);
    }

    /**
     * 生产入口：直接从真实 {@link DemandState} 收口成心境
     * （与 {@link ModulatorState#fromDemand} 对称，解决两侧入口不一致）。
     */
    public static String fromDemand(DemandState d) {
        if (d == null) return "我现在心里挺平稳的。";
        List<InteroceptionSignal> sigs = List.of(
                new InteroceptionSignal("confidence", d.confidenceLvl, d.confidenceZ),
                new InteroceptionSignal("surprise", d.surpriseLvl, d.surpriseZ),
                new InteroceptionSignal("dissonance", d.dissonanceLvl, d.dissonanceZ),
                new InteroceptionSignal("plausibility", d.plausibilityLvl, d.plausibilityZ),
                new InteroceptionSignal("expectation", d.expectationLvl, d.expectationZ),
                new InteroceptionSignal("pressure", d.pressureLvl, d.pressureZ));
        return narrate(sigs);
    }

    /**
     * 把内感受读数收口成一句第一人称心境。
     *
     * @param signals   所有子系统的内感受读数（含 NORMAL，会被自动滤掉）
     * @param topK      最多让几个主导感受进入意识
     * @param budget    字数预算（中文字符），硬上限，超出截断到最近的整句感受
     * @return 一句心境；全部正常时返回一句中性平稳的状态（仍是第一人称）
     */
    public static String narrate(List<InteroceptionSignal> signals, int topK, int budget) {
        if (signals == null || signals.isEmpty()) {
            return "我现在心里挺平稳的。";
        }

        // 1. 只留偏离常态的，按「响度」（|z|）降序——最偏离基线的最先被感觉到
        List<InteroceptionSignal> deviating = new ArrayList<>();
        for (InteroceptionSignal s : signals) {
            if (s != null && s.isDeviating() && FELT.containsKey(key(s))) {
                deviating.add(s);
            }
        }
        if (deviating.isEmpty()) {
            return "我现在心里挺平稳的。";
        }
        deviating.sort(Comparator.comparingDouble(InteroceptionSignal::salience).reversed());

        // 2. 取 top-K 主导感受，串成一句，受字数预算约束
        // 词典短语本身已带「我」的语气，开头不再强加主语（修掉「我这事我…」的叠主语语病）
        StringBuilder sb = new StringBuilder();
        int taken = 0;
        for (InteroceptionSignal s : deviating) {
            if (taken >= topK) break;
            String felt = FELT.get(key(s));
            // 预算检查：加上这句（含连接符）会不会超？超了就停，宁可少说也不膨胀
            int projected = sb.length() + felt.length() + 1;
            if (taken > 0 && projected > budget) break;
            sb.append(taken == 0 ? "" : "，").append(felt);
            taken++;
        }
        sb.append("。");
        return sb.toString();
    }

    private static String key(InteroceptionSignal s) {
        return s.dim() + "|" + s.level().name();
    }
}
