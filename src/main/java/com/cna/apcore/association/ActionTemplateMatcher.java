package com.cna.apcore.association;

import com.cna.apcore.config.CoreConfig;
import com.cna.apcore.db.ExperiencesDB;
import com.cna.apcore.model.CognitiveAction;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

/**
 * 行动模板匹配器 — 从历史经验中按感觉维度 + 来源类型匹配，
 * 统计工具出场率，将高频工具作为预填建议注入 LLM Prompt。
 *
 * <h3>核心逻辑</h3>
 * <ol>
 *   <li>从当前 action 收集 UE dims + source_type</li>
 *   <li>加载全量经验的轻量摘要（MatchableExp），计算匹配得分</li>
 *   <li>对匹配到的经验，统计每个 tool 的出现次数和比例</li>
 *   <li>比例超过阈值的 tool → 输出为 ActionTemplate 推荐</li>
 * </ol>
 *
 * <h3>容错设计</h3>
 * <ul>
 *   <li>感觉维度为空时，退化为纯 source_type 匹配</li>
 *   <li>source_type 为空时，只用感觉维度匹配</li>
 *   <li>两者都为空时，跳过匹配（输出空列表）</li>
 *   <li>LLM 仍然可以推翻推荐——模板是建议，不是命令</li>
 * </ul>
 */
@Slf4j
public class ActionTemplateMatcher {

    /** 单例 */
    private static volatile ActionTemplateMatcher INSTANCE;

    public static ActionTemplateMatcher getInstance() {
        if (INSTANCE == null) {
            synchronized (ActionTemplateMatcher.class) {
                if (INSTANCE == null) {
                    INSTANCE = new ActionTemplateMatcher();
                }
            }
        }
        return INSTANCE;
    }

    private ActionTemplateMatcher() {
        log.info("[TemplateMatcher] 🔧 行动模板匹配器已就绪 (minRatio={}, minMatch={})",
                String.format("%.0f%%", CoreConfig.TEMPLATE_MIN_TOOL_RATIO * 100), CoreConfig.TEMPLATE_MIN_MATCH_COUNT);
    }

    // ==========================================
    // 数据模型
    // ==========================================

    /** 一个匹配到的行动模板 */
    public record ActionTemplate(
            String toolName,
            double ratio,          // 匹配经验中该工具的出现比例
            int toolCount,         // 该工具在匹配经验中出现的次数
            int totalMatches,      // 匹配到的经验总数
            List<String> sampleSources  // 示例来源（用于 LLM 理解上下文）
    ) {}

    /**
     * 匹配得分结构。
     * score = dimJaccard × 0.7 + sourceMatch(0/1) × 0.3
     */
    private record ScoredExp(ExperiencesDB.MatchableExp exp, double score) {}

    // ==========================================
    // 核心方法
    // ==========================================

    /**
     * 计算当前 action 的推荐行动模板。
     *
     * @param action        当前认知动作
     * @param experiencesDB 经验数据库
     * @return 按出场率降序排列的模板列表（可能为空）
     */
    public List<ActionTemplate> compute(CognitiveAction action, ExperiencesDB experiencesDB) {
        // 1. 收集当前场景的匹配 key
        Set<Integer> actionDims = new LinkedHashSet<>(action.getUEDimIds());
        String actionSource = extractSourceType(action);

        if (actionDims.isEmpty() && actionSource.isEmpty()) {
            log.debug("[TemplateMatcher] 无匹配 key (dims empty, source empty)，跳过");
            return List.of();
        }

        // 2. 加载并评分经验
        List<ExperiencesDB.MatchableExp> all = experiencesDB.loadMatchableExps();
        if (all.isEmpty()) {
            log.debug("[TemplateMatcher] 经验库为空，跳过");
            return List.of();
        }

        List<ScoredExp> scored = new ArrayList<>();
        for (ExperiencesDB.MatchableExp exp : all) {
            if (exp.toolNames().isEmpty()) continue; // 没有工具记录的经验不参与统计

            double dimJaccard = computeDimJaccard(actionDims, exp.feelingDimIds());
            double sourceMatch = (!actionSource.isEmpty() && actionSource.equals(exp.sourceType())) ? 1.0 : 0.0;

            // 至少有一维匹配才纳入
            double matchScore;
            if (!actionDims.isEmpty() && !actionSource.isEmpty()) {
                matchScore = dimJaccard * 0.7 + sourceMatch * 0.3;
            } else if (!actionDims.isEmpty()) {
                matchScore = dimJaccard;
            } else {
                matchScore = sourceMatch;
            }

            if (matchScore > 0.0) {
                scored.add(new ScoredExp(exp, matchScore));
            }
        }

        if (scored.size() < CoreConfig.TEMPLATE_MIN_MATCH_COUNT) {
            log.debug("[TemplateMatcher] 匹配经验数 {} < 阈值 {}，跳过", scored.size(), CoreConfig.TEMPLATE_MIN_MATCH_COUNT);
            return List.of();
        }

        // 3. 统计工具频率
        Map<String, Integer> toolCounts = new LinkedHashMap<>();
        for (ScoredExp se : scored) {
            for (String toolName : se.exp.toolNames()) {
                toolCounts.merge(toolName, 1, Integer::sum);
            }
        }

        int totalMatches = scored.size();

        // 4. 按比例筛选
        List<ActionTemplate> templates = new ArrayList<>();
        for (Map.Entry<String, Integer> e : toolCounts.entrySet()) {
            double ratio = (double) e.getValue() / totalMatches;
            if (ratio >= CoreConfig.TEMPLATE_MIN_TOOL_RATIO) {
                // 收集示例来源
                List<String> samples = scored.stream()
                        .filter(se -> se.exp.toolNames().contains(e.getKey()))
                        .limit(3)
                        .map(se -> se.exp.sourceType())
                        .filter(s -> !s.isEmpty())
                        .distinct()
                        .toList();
                templates.add(new ActionTemplate(e.getKey(), ratio, e.getValue(), totalMatches, samples));
            }
        }

        // 按 ratio 降序
        templates.sort((a, b) -> Double.compare(b.ratio, a.ratio));

        // 截断
        if (templates.size() > CoreConfig.TEMPLATE_MAX_TEMPLATES) {
            templates = templates.subList(0, CoreConfig.TEMPLATE_MAX_TEMPLATES);
        }

        if (!templates.isEmpty()) {
            log.info("[TemplateMatcher] ✅ 匹配到 {} 个模板 (dims={} source={} matchExps={})",
                    templates.size(), actionDims.size(), actionSource, totalMatches);
            for (ActionTemplate t : templates) {
                log.info("[TemplateMatcher]   → {} (ratio={:.0f}% = {}/{})",
                        t.toolName, t.ratio * 100, t.toolCount, t.totalMatches);
            }
        }

        return templates;
    }

    /**
     * 将模板列表格式化为 Prompt 注入文本。
     */
    public String renderPromptBlock(List<ActionTemplate> templates) {
        if (templates == null || templates.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("【行动模板 — 类似场景中高频使用的工具（基于历史实践统计）】\n");
        sb.append("以下是你在类似感觉维度和来源场景中成功使用过的工具及其出场率。\n");
        sb.append("你可以直接使用推荐的工具（只需填参数），也可以推翻选择其他工具。\n\n");

        for (int i = 0; i < templates.size(); i++) {
            ActionTemplate t = templates.get(i);
            String confidenceLabel;
            if (t.ratio >= 0.9) confidenceLabel = "🟢 极高置信";
            else if (t.ratio >= 0.75) confidenceLabel = "🔵 高置信";
            else confidenceLabel = "🟡 中置信";

            sb.append(String.format("  [模板%d] %s → 推荐工具: %s\n", i + 1, confidenceLabel, t.toolName));
            sb.append(String.format("    出场率: %d/%d (%.0f%%)", t.toolCount, t.totalMatches, t.ratio * 100));
            if (!t.sampleSources.isEmpty()) {
                sb.append(String.format(" | 来源: %s", String.join(", ", t.sampleSources)));
            }
            sb.append("\n");
        }

        sb.append("\n如果你认为以上模板都不适用于当前具体情况，请自由选择其他工具。\n");
        return sb.toString();
    }

    // ==========================================
    // 辅助方法
    // ==========================================

    /** 从 action 的 sourceUnit 提取来源类型标识 */
    public static String extractSourceType(CognitiveAction action) {
        List<String> sourceIds = action.getSourceUnit().getSourceIds();
        if (sourceIds == null || sourceIds.isEmpty()) return "";

        String first = sourceIds.get(0);
        if (first == null || first.isBlank()) return "";

        // qqid:xxx → qqid, qq_group:xxx → qq_group
        int colon = first.indexOf(':');
        if (colon > 0) {
            return first.substring(0, colon);
        }
        return first; // web_event, system, etc.
    }

    /** 计算两个维度集合的 Jaccard 相似度 */
    static double computeDimJaccard(Set<Integer> a, List<Integer> bList) {
        if (a.isEmpty() || bList.isEmpty()) return 0.0;
        Set<Integer> b = new HashSet<>(bList);
        int intersection = 0;
        for (int d : a) {
            if (b.contains(d)) intersection++;
        }
        if (intersection == 0) return 0.0;
        int union = a.size() + b.size() - intersection;
        return (double) intersection / union;
    }
}
