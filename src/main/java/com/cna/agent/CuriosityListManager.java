package com.cna.agent;

import com.cna.config.ConfigsManager;
import com.cna.db.FeelingDimensionManager;
import com.cna.db.FeelingDimensionManager.DimensionScore;
import com.cna.db.MemoryDB;
import com.cna.db.MemoryDB.CuriosityEntry;
import com.cna.agent.FeelingResonanceAnalyzer.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 好奇心列表管理器。
 *
 * 将 FeelingResonanceAnalyzer 检测到的违和感持久化为"好奇心条目"，
 * 在 LLM 上下文重建时注入，在 Input 审查时加权，在 LLM 自主消解时清理。
 * 全生命周期事件写入 currentMemories 保证跨上下文连续性。
 */
@Slf4j
public class CuriosityListManager {

    private static volatile CuriosityListManager INSTANCE;

    public static synchronized void init(MemoryDB memoryDB) {
        if (INSTANCE == null) {
            INSTANCE = new CuriosityListManager(memoryDB);
        }
    }

    public static CuriosityListManager getInstance() {
        return INSTANCE;
    }

    private final MemoryDB db;
    private final ObjectMapper mapper = new ObjectMapper();

    private CuriosityListManager(MemoryDB memoryDB) {
        this.db = memoryDB;
        log.info("[CuriosityListManager] 好奇心管理器已初始化，活跃条目: {}", db.getActiveCuriosityCount());
    }

    // ============================================================
    // 1. 积累：从谐振分析结果中提取违和 → 创建/更新好奇心条目
    // ============================================================

    /**
     * 对每个有违和的 ResonanceGroup：
     *   - 已存在同(sourceDimId + 相同 dissonantDimIds) → triggerCount += 1
     *   - 不存在 → 新建条目
     * 创建/更新时写入 currentMemories。
     */
    public void accumulateFromResonance(ResonanceAnalysisResult resonance, String taskContext) {
        if (resonance == null || !resonance.hasDissonance()) return;

        List<CuriosityEntry> existing = db.getAllCuriosityEntries();
        MemoryManager mm = MemoryManager.getInstance();

        for (ResonanceGroup group : resonance.groups) {
            if (!group.hasDissonance()) continue;

            List<DimensionSimilarity> dissonant = group.getDissonant();
            List<Integer> dissIds = new ArrayList<>();
            List<String> dissConcepts = new ArrayList<>();
            for (DimensionSimilarity ds : dissonant) {
                dissIds.add(ds.dimId);
                dissConcepts.add(ds.concept);
            }

            // 检查是否已有匹配的好奇心条目（同 source + 同 dissonant set）
            CuriosityEntry matched = findMatchingEntry(existing, group.sourceDimId, dissIds);

            if (matched != null) {
                // 已存在 → 增加触发次数
                db.incrementCuriosityTriggerCount(matched.id);
                int newCount = matched.triggerCount + 1;
                log.info("[CuriosityListManager] 好奇心条目 #{} 再次触发 (第{}次): {}",
                        matched.id, newCount, group.sourceConcept);

                if (mm != null && (matched.llmQuestion == null || matched.llmQuestion.isBlank())) {
                    mm.inputCurrentMemory(
                            String.format("[好奇心] 条目#%d 再次触发(第%d次): 对'%s'的违和感再次浮现，请在 finish_task 的 curiosity_questions 中写下你的疑问",
                                    matched.id, newCount, group.sourceConcept));
                }
            } else {
                // 不存在 → 新建
                try {
                    String dissIdsJson = mapper.writeValueAsString(dissIds);
                    String dissConceptsJson = mapper.writeValueAsString(dissConcepts);

                    int newId = db.insertCuriosityEntry(group.sourceDimId, group.sourceConcept,
                            dissIdsJson, dissConceptsJson, "");

                    if (newId > 0) {
                        log.info("[CuriosityListManager] 新建好奇心条目 #{} : {}",
                                newId, group.sourceConcept);

                        if (mm != null) {
                            mm.inputCurrentMemory(
                                    String.format("[好奇心] 发现新违和(条目#%d): 对'%s'产生了违和感，请在 finish_task 的 curiosity_questions 中以疑问句写下你的困惑",
                                            newId, group.sourceConcept));
                        }

                        // 裁剪超限条目
                        pruneExcessEntries();
                    }
                } catch (JsonProcessingException e) {
                    log.error("[CuriosityListManager] JSON 序列化失败", e);
                }
            }
        }
    }

    // ============================================================
    // 1b. LLM 驱动的疑问注册
    // ============================================================

    /**
     * LLM 在 finish_task 中通过 curiosity_questions 提交对某个违和感觉的疑问。
     * 查找同 sourceDimId 的已有条目 → 更新 llm_question；
     * 不存在则新建条目。写入 currentMemories。
     */
    public void registerCuriosityQuestion(int sourceDimId, String question, List<Integer> dissonantDimIds) {
        if (question == null || question.isBlank()) return;

        FeelingDimensionManager fdm = FeelingDimensionManager.getInstance();
        String sourceConcept = "";
        List<String> dissConcepts = new ArrayList<>();
        if (fdm != null) {
            List<MemoryDB.FeelingDimension> allDims = fdm.getAllDimensions();
            for (MemoryDB.FeelingDimension d : allDims) {
                if (d.id == sourceDimId) sourceConcept = d.concept;
            }
            for (int did : dissonantDimIds) {
                for (MemoryDB.FeelingDimension d : allDims) {
                    if (d.id == did) { dissConcepts.add(d.concept); break; }
                }
            }
        }
        if (sourceConcept.isEmpty()) sourceConcept = "dim#" + sourceDimId;

        MemoryManager mm = MemoryManager.getInstance();

        // 查找已有条目
        List<CuriosityEntry> existing = db.getAllCuriosityEntries();
        CuriosityEntry matched = findMatchingEntry(existing, sourceDimId, dissonantDimIds);

        if (matched != null) {
            // 已存在 → 更新 llm_question
            db.updateCuriosityQuestion(matched.id, question);
            db.incrementCuriosityTriggerCount(matched.id);
            log.info("[CuriosityListManager] LLM 为条目 #{} 提交了疑问: {}", matched.id,
                    question.length() > 60 ? question.substring(0, 60) + "..." : question);

            if (mm != null) {
                mm.inputCurrentMemory(
                        String.format("[好奇心] 条目#%d: 对'%s'的疑问已更新——\"%s\"",
                                matched.id, sourceConcept,
                                question.length() > 80 ? question.substring(0, 80) + "..." : question));
            }
        } else {
            // 不存在 → 新建
            try {
                String dissIdsJson = mapper.writeValueAsString(
                        dissonantDimIds != null ? dissonantDimIds : Collections.emptyList());
                String dissConceptsJson = mapper.writeValueAsString(dissConcepts);

                int newId = db.insertCuriosityEntry(sourceDimId, sourceConcept,
                        dissIdsJson, dissConceptsJson, question);

                if (newId > 0) {
                    log.info("[CuriosityListManager] LLM 创建好奇心条目 #{} (含疑问): {}",
                            newId, sourceConcept);

                    if (mm != null) {
                        mm.inputCurrentMemory(
                                String.format("[好奇心] 条目#%d: 对'%s'提出疑问——\"%s\"",
                                        newId, sourceConcept,
                                        question.length() > 80 ? question.substring(0, 80) + "..." : question));
                    }

                    pruneExcessEntries();
                }
            } catch (JsonProcessingException e) {
                log.error("[CuriosityListManager] JSON 序列化失败", e);
            }
        }
    }

    // ============================================================
    // 2. Prompt 注入：构建 LLM 可见的好奇心上下文块
    // ============================================================

    /**
     * 构建注入 LLM 的好奇心上下文 Prompt 块。
     * 列出所有活跃的好奇心条目。
     */
    public String buildCuriosityPromptBlock() {
        List<CuriosityEntry> active = db.getActiveCuriosityEntries();
        if (active.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("## ⚠️ 未解决的好奇心\n\n");
        sb.append("以下是你之前对自己提出的、尚未回答的问题。在每个任务中留意是否触及这些疑问：\n\n");

        for (CuriosityEntry entry : active) {
            boolean hasQuestion = entry.llmQuestion != null && !entry.llmQuestion.isBlank();
            sb.append(String.format("- **#%d** (追问%d次) 对 **%s** 的违和:\n",
                    entry.id, entry.triggerCount, entry.sourceConcept));
            if (hasQuestion) {
                String q = entry.llmQuestion.length() > 200
                        ? entry.llmQuestion.substring(0, 200) + "..."
                        : entry.llmQuestion;
                sb.append(String.format("  🤔 \"%s\"\n", q));
            } else {
                sb.append("  🤔 (尚未形成具体疑问——在 finish_task 的 curiosity_questions 中写下你的困惑)\n");
            }
        }

        sb.append("\n当你对某个问题有了答案，在 finish_task 的 dissonance_updates 中设置 `curiosity_resolved: true` 来关闭它。\n");
        return sb.toString();
    }

    // ============================================================
    // 3. Input 审查：检查输入是否触及活跃的好奇心维度
    // ============================================================

    /**
     * 检查输入文本是否触及活跃的好奇心维度。
     * 返回命中的维度 ID 列表（去重），供输入审查加权使用。
     */
    public List<Integer> checkCuriosityMatch(String inputText) {
        FeelingDimensionManager fdm = FeelingDimensionManager.getInstance();
        if (fdm == null) return Collections.emptyList();

        List<DimensionScore> inputScores = fdm.evaluateAllDimensions(inputText);
        if (inputScores.isEmpty()) return Collections.emptyList();

        Set<Integer> matchedDimIds = inputScores.stream()
                .filter(s -> s.dimId > 0)
                .map(s -> s.dimId)
                .collect(Collectors.toSet());

        List<CuriosityEntry> active = db.getActiveCuriosityEntries();
        if (active.isEmpty()) return Collections.emptyList();

        Set<Integer> matched = new LinkedHashSet<>();
        for (CuriosityEntry entry : active) {
            // 输入触及 source 维度
            if (matchedDimIds.contains(entry.sourceDimId)) {
                matched.add(entry.sourceDimId);
            }
            // 输入触及 dissonant 维度
            for (int dissId : entry.dissonantDimIds) {
                if (matchedDimIds.contains(dissId)) {
                    matched.add(dissId);
                }
            }
        }
        return new ArrayList<>(matched);
    }

    /**
     * 根据命中的好奇心维度构建人类可读的原因描述。
     */
    public String buildCuriosityMatchReason(List<Integer> matchedDimIds) {
        if (matchedDimIds == null || matchedDimIds.isEmpty()) return "";

        FeelingDimensionManager fdm = FeelingDimensionManager.getInstance();
        if (fdm == null) return "未解认知矛盾";

        // 尝试把维度 ID 转为概念名
        List<String> concepts = new ArrayList<>();
        for (int dimId : matchedDimIds) {
            try {
                List<MemoryDB.FeelingDimension> allDims = fdm.getAllDimensions();
                for (MemoryDB.FeelingDimension dim : allDims) {
                    if (dim.id == dimId) {
                        concepts.add(dim.concept);
                        break;
                    }
                }
            } catch (Exception ignored) {}
        }
        return concepts.isEmpty() ? "未解认知矛盾" : String.join("、", concepts);
    }

    // ============================================================
    // 4. 消解：LLM 声明好奇心已被满足
    // ============================================================

    /**
     * LLM 在 finish_task 中声明某个好奇心条目已消解。
     * 查找该 dim 涉及的所有活跃条目并标记 is_active=false。
     * 写入 currentMemories。
     */
    public void resolveEntriesByDim(int dimId, String note) {
        List<CuriosityEntry> active = db.getActiveCuriosityEntries();
        MemoryManager mm = MemoryManager.getInstance();

        for (CuriosityEntry entry : active) {
            // 该 dim 是 source 或 dissonant 维度之一
            if (entry.sourceDimId == dimId || entry.dissonantDimIds.contains(dimId)) {
                db.deactivateCuriosityEntry(entry.id, note);
                log.info("[CuriosityListManager] 好奇心条目 #{} 已消解 (dim_id={})", entry.id, dimId);

                if (mm != null) {
                    mm.inputCurrentMemory(
                            String.format("[好奇心] 条目#%d 已消解: 对'%s'的疑问已被充分理解%s",
                                    entry.id, entry.sourceConcept,
                                    (note != null && !note.isBlank()) ? "——" + note : ""));
                }
            }
        }
    }

    // ============================================================
    // 5. 裁剪：超限时删除最旧的条目
    // ============================================================

    private void pruneExcessEntries() {
        int maxEntries = ConfigsManager.CURIOSITY_MAX_ENTRIES;
        List<CuriosityEntry> all = db.getAllCuriosityEntries();
        if (all.size() <= maxEntries) return;

        // 按创建时间升序排列（最旧的在前），删除最旧的
        all.sort(Comparator.comparing(e -> e.createdAt));
        int toDelete = all.size() - maxEntries;
        for (int i = 0; i < toDelete; i++) {
            CuriosityEntry victim = all.get(i);
            db.deleteCuriosityEntry(victim.id);
            log.info("[CuriosityListManager] 裁剪旧好奇心条目 #{} ({})", victim.id, victim.sourceConcept);
        }
    }

    // ============================================================
    // 6. 辅助方法
    // ============================================================

    private CuriosityEntry findMatchingEntry(List<CuriosityEntry> existing,
                                              int sourceDimId, List<Integer> dissIds) {
        for (CuriosityEntry entry : existing) {
            if (entry.sourceDimId == sourceDimId
                    && entry.dissonantDimIds.size() == dissIds.size()
                    && new HashSet<>(entry.dissonantDimIds).containsAll(dissIds)) {
                return entry;
            }
        }
        return null;
    }
}
