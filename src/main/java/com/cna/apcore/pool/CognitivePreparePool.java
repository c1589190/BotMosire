package com.cna.apcore.pool;

import com.cna.apcore.MentalStateLogger;
import com.cna.apcore.config.CoreConfig;
import com.cna.apcore.db.ExperiencesDB;
import com.cna.apcore.db.FeelingsDB;
import com.cna.apcore.attention.FatigueManager;
import com.cna.apcore.feeling.FeelingsManager;
import com.cna.apcore.model.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.cna.config.ConfigsManager;
import com.cna.db.FeelingDimensionManager;
import com.cna.db.FeelingHypergraphManager;
import com.cna.db.MemoryDB;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.Function;

/**
 * 认知准备池。
 *
 * 职责：
 * 1. 存放 CognitivePrepareUnit
 * 2. 每 tick：tickAll() + decayAndPrune()
 * 3. selectAndConvert()：计算 UE，选择 max(SE×UE×tick) 的单元转为 CognitiveAction
 * 4. 支持 LLM 对 ContinueWeight 的 boost
 */
@Slf4j
public class CognitivePreparePool {

    private final ConcurrentLinkedDeque<CognitivePrepareUnit> pool = new ConcurrentLinkedDeque<>();
    private final Object poolLock = new Object();

    private FatigueManager fatigueManager;     // 语义疲劳管理器（外部注入）
    private Function<String, double[]> embedder;  // 文本→embedding 函数（外部注入）
    private FeelingDimensionManager fdm;           // 旧版感觉维度管理器（外部注入）
    private FeelingHypergraphManager hypergraph;   // 感觉超图管理器（外部注入）
    private FeelingsDB feelingsDB;                 // V4 感觉数据库（外部注入）

    /** 注入语义疲劳管理器 */
    public void setFatigueManager(FatigueManager fm) {
        this.fatigueManager = fm;
    }

    /** 注入 embedder 函数（用于 eager UE 计算） */
    public void setEmbedder(Function<String, double[]> embedder) {
        this.embedder = embedder;
    }

    /** 注入感觉维度管理器 */
    public void setFdm(FeelingDimensionManager fdm) {
        this.fdm = fdm;
    }

    /** 注入感觉超图管理器 */
    public void setHypergraph(FeelingHypergraphManager h) {
        this.hypergraph = h;
    }

    /** 注入 V4 感觉数据库 */
    public void setFeelingsDB(FeelingsDB db) {
        this.feelingsDB = db;
    }

    /** 推送一个准备单元到池中。会在 poolLock 外部做 eager embedding + UE 计算。 */
    public void push(CognitivePrepareUnit unit) {
        if (unit == null) return;

        // ★ Eager embedding 计算（poolLock 外部，避免阻塞）
        if (embedder != null && unit.getCachedEmbedding() == null) {
            try {
                double[] emb = embedder.apply(unit.getText());
                if (emb != null && emb.length > 0) {
                    unit.setCachedEmbedding(emb);
                }
            } catch (Exception e) {
                log.warn("[Pool] push() embedding 计算失败: {}，将在 select 时重试", e.getMessage());
            }
        }

        // ★ Eager UE 计算（poolLock 外部，利用刚算好的 embedding）
        if (unit.getCachedEmbedding() != null
                && (unit.getUeUnits() == null || unit.getUeUnits().isEmpty())
                && fdm != null && feelingsDB != null) {
            try {
                computeUE(unit, unit.getCachedEmbedding(), fdm, hypergraph, feelingsDB);
                // UE 算完后立即计算注意力态度乘数
                computeAttentionMultiplier(unit);
            } catch (Exception e) {
                log.warn("[Pool] push() UE 计算失败: {}，将在 select 时重试", e.getMessage());
            }
        }

        synchronized (poolLock) {
            // 容量控制：超出时移除最旧的（tick 最大的）
            while (pool.size() >= CoreConfig.MAX_POOL_SIZE) {
                CognitivePrepareUnit victim = pool.peekLast();
                if (victim != null) {
                    pool.remove(victim);
                    log.debug("[Pool] 容量满，移除最旧单元: {}", victim);
                } else {
                    break;
                }
            }
            pool.offerFirst(unit);
            log.debug("[Pool] 推送新单元: {} (池大小: {}, attnM={})", unit, pool.size(),
                    String.format("%.2f", unit.getAttentionAttitudeMultiplier()));
        }
    }

    /** 对所有单元 tick++ 并衰减 ContinueWeight */
    public void tickAll() {
        synchronized (poolLock) {
            for (CognitivePrepareUnit unit : pool) {
                unit.tick();
                unit.decayContinueWeight(CoreConfig.CONTINUE_WEIGHT_DECAY);
            }
        }
        if (!pool.isEmpty()) {
            log.debug("[Pool] tickAll: {} 个单元已打 tick", pool.size());
        }
    }

    /** 移除 tick 超过阈值的过期单元，返回移除数量 */
    public int decayAndPrune() {
        int removed = 0;
        synchronized (poolLock) {
            Iterator<CognitivePrepareUnit> it = pool.iterator();
            while (it.hasNext()) {
                CognitivePrepareUnit unit = it.next();
                if (unit.getTick() > CoreConfig.MAX_TICKS_WITHOUT_SELECT) {
                    it.remove();
                    removed++;
                    log.debug("[Pool] 移除过期单元: {} (tick={})", unit, unit.getTick());
                    MentalStateLogger.getInstance().poolUnitExpired(
                            unit.getUuid().toString(), unit.getTick(),
                            unit.isEndogenous(), unit.getContinueWeight());
                }
            }
        }
        if (removed > 0) {
            log.info("[Pool] 清理 {} 个过期单元，剩余 {}", removed, pool.size());
        }
        return removed;
    }

    /**
     * 选择并转换为 CognitiveAction。
     *
     * 流程：
     * 1. 对每个单元计算 embedding（仅一次），用于 UE 计算和疲劳评估
     * 2. 对每个单元计算 UE（理解能量）— BFS 搜索感觉维度超图
     * 3. 找到 selectionScore = max((SE+attn)×UE×log₂(tick+1)×CW×fatiguePenalty)
     *    且 (SE+attn)×UE > baselineThreshold
     * 4. 计算 CognitiveAction 的 6 个情绪字段
     * 5. 从 ExperiencesDB 检索 ActionPredicts
     *
     * @param embedder      文本→embedding 函数
     * @param experiencesDB 经验数据库（用于检索先验经验）
     * @param feelingsDB    V4 感觉数据库（用于 UE 计算和去重）
     * @param hypergraph    感觉超图（用于 BFS 扩展）
     * @param currentTick   当前 tick 编号（用于疲劳时间衰减计算）
     * @return 选中的 CognitiveAction，如果没有符合条件的单元则返回 null
     */
    public CognitiveAction selectAndConvert(
            Function<String, double[]> embedder,
            ExperiencesDB experiencesDB,
            FeelingsDB feelingsDB,
            FeelingHypergraphManager hypergraph,
            int currentTick) {

        List<CognitivePrepareUnit> units;
        synchronized (poolLock) {
            if (pool.isEmpty()) return null;
            units = new ArrayList<>(pool);
        }

        // ★ 计算每个单元的 embedding（优先用缓存）、UE 和语义疲劳
        FeelingDimensionManager fdm = this.fdm != null ? this.fdm : FeelingDimensionManager.getInstance();
        FeelingsDB v4feelings = this.feelingsDB != null ? this.feelingsDB : feelingsDB;
        FeelingHypergraphManager hg = this.hypergraph != null ? this.hypergraph : hypergraph;
        for (CognitivePrepareUnit unit : units) {
            // 优先使用 push 时预计算的 embedding，避免重复 LLM 调用
            double[] emb = unit.getCachedEmbedding();
            if (emb == null) {
                emb = embedder.apply(unit.getText());
                unit.setCachedEmbedding(emb);
            }

            // UE 计算：若 push 时已算则跳过
            if (unit.getUeUnits() == null || unit.getUeUnits().isEmpty()) {
                computeUE(unit, emb, fdm, hg, v4feelings);
                // 补算注意力态度乘数（push 时可能因 UE 未算而没算）
                computeAttentionMultiplier(unit);
            } else if (unit.getFeelingMatchStrengths() == null || unit.getFeelingMatchStrengths().isEmpty()) {
                // UE 已算但态度乘数未算
                computeAttentionMultiplier(unit);
            }

            // ★ 语义疲劳计算
            if (fatigueManager != null) {
                unit.setUnitFatigue(fatigueManager.computeFatigue(emb, currentTick));
            }
        }

        // 选择得分最高的单元
        CognitivePrepareUnit best = null;
        double bestScore = 0;
        for (CognitivePrepareUnit unit : units) {
            double score = unit.selectionScore(CoreConfig.BASELINE_THRESHOLD);
            if (score > bestScore) {
                bestScore = score;
                best = unit;
            }
        }

        if (best == null) {
            log.debug("[Pool] selectAndConvert: 无单元超过基础底线 threshold={}", CoreConfig.BASELINE_THRESHOLD);
            return null;
        }

        // 从池中移除选中单元
        synchronized (poolLock) {
            pool.remove(best);
        }

        log.info("[Pool] 选中单元: " + best + " (score=" + String.format("%.3f", bestScore) + ")");

        // ★ 心智日志：单元选中 + 选取得分分解
        double tickFactor = 1.0 + Math.log(Math.max(1, best.getTick()) + 1) / Math.log(2);
        MentalStateLogger mlog = MentalStateLogger.getInstance();
        mlog.unitSelected(
                best.getUuid().toString(), bestScore,
                best.getStimulateEnergy(), best.getAttentionEnergy(),
                best.getUnderstandEnergy(), tickFactor, best.getContinueWeight(),
                best.getUnitFatigue(), best.isEndogenous(),
                best.getText(), pool.size());

        // ★ 心智日志：排名前 5 的候选单元
        final CognitivePrepareUnit selectedUnit = best;
        List<CognitivePrepareUnit> ranked = units.stream()
                .filter(u -> u != selectedUnit)
                .sorted((a, b) -> Double.compare(
                        b.selectionScore(CoreConfig.BASELINE_THRESHOLD),
                        a.selectionScore(CoreConfig.BASELINE_THRESHOLD)))
                .limit(5)
                .toList();
        if (!ranked.isEmpty()) {
            ObjectNode[] rankings = new ObjectNode[ranked.size()];
            for (int i = 0; i < ranked.size(); i++) {
                CognitivePrepareUnit u = ranked.get(i);
                double uTick = 1.0 + Math.log(Math.max(1, u.getTick()) + 1) / Math.log(2);
                rankings[i] = mlog.createRankEntry(
                        u.getUuid().toString(),
                        u.selectionScore(CoreConfig.BASELINE_THRESHOLD),
                        u.getStimulateEnergy(), u.getAttentionEnergy(),
                        u.getUnderstandEnergy(), uTick, u.getContinueWeight(),
                        u.getUnitFatigue(), u.isEndogenous());
            }
            mlog.unitSelectionRanking(rankings);
        }

        // 转换为 CognitiveAction
        CognitiveAction action = CognitiveAction.from(best);

        // 计算 6 个情绪字段
        double[] actionTextEmb = embedder.apply(best.getText());
        action.computeFamiliarity(actionTextEmb, best.getUeUnits());
        action.computeScale();
        action.computeAccidentDegree();
        // continueWeight 已在 CognitiveAction.from() 中从 sourceUnit 继承

        // 检索先验经验
        List<Integer> topDimIds = getTopDimIds(best.getUeUnits(), CoreConfig.TOP_N_ACTION_PREDICTS);
        if (!topDimIds.isEmpty() && experiencesDB != null) {
            List<ActionPredict> predicts = experiencesDB.queryByFeelings(
                    topDimIds, actionTextEmb, action.getScale());
            action.setPredicts(predicts);
        }
        // ActionPressure 暂为 0（TODO）

        log.info("[Pool] 转换为 CognitiveAction — CF=" + String.format("%.3f", action.getCognitiveFamiliarity())
                + ", Scale=" + action.getScale()
                + ", Accident=" + String.format("%.3f", action.getAccidentDegree())
                + ", Predicts=" + action.getActionPredicts().size());

        return action;
    }

    /**
     * 计算 UE（理解能量）。
     *
     * 算法：
     * 1. 从 FeelingDimensionManager 获取与 unit.text 最匹配的 top-N 感觉维度作为种子
     * 2. 对每个种子通过 FeelingHypergraph BFS 扩展（最多 BFS_MAX_LAYERS 层）
     * 3. 每一层搜到的节点按 (noveltyWeight × layerDecay^layer) 加权累加
     * 4. 同时查找 V4 FeelingsDB 中的感觉并加入计算
     * 5. 记录所有涉及节点为 UEUnit[]
     */
    private void computeUE(
            CognitivePrepareUnit unit,
            double[] unitEmbedding,
            FeelingDimensionManager fdm,
            FeelingHypergraphManager hypergraph,
            FeelingsDB feelingsDB) {

        List<UEUnit> allUEUnits = new ArrayList<>();
        double totalUE = 0.0;
        Set<Integer> visited = new HashSet<>();

        int bfsLayers = CoreConfig.BFS_MAX_LAYERS;
        double layerDecay = CoreConfig.BFS_LAYER_DECAY;

        // 1. 获取种子维度（从旧 FeelingDimensionManager）
        if (fdm != null) {
            List<FeelingDimensionManager.DimensionScore> seeds =
                    fdm.getTargetDimensions(unit.getText(), true,
                            ConfigsManager.FEELING_DIMENSION_COUNT);

            for (FeelingDimensionManager.DimensionScore seed : seeds) {
                if (seed.dimId <= 0) continue;

                // BFS 扩展
                Set<Integer> expanded = hypergraph != null
                        ? hypergraph.expandDimension(seed.dimId, bfsLayers)
                        : new LinkedHashSet<>(Set.of(seed.dimId));

                // 获取所有维度详情
                List<MemoryDB.FeelingDimension> allDims = fdm.getAllDimensions();
                Map<Integer, MemoryDB.FeelingDimension> dimMap = new HashMap<>();
                for (MemoryDB.FeelingDimension d : allDims) dimMap.put(d.id, d);

                // BFS 分层处理
                // 简化：按连接层级给权重
                Queue<Integer> queue = new LinkedList<>();
                Map<Integer, Integer> layerMap = new HashMap<>();
                queue.add(seed.dimId);
                layerMap.put(seed.dimId, 0);
                visited.add(seed.dimId);

                while (!queue.isEmpty()) {
                    int current = queue.poll();
                    int layer = layerMap.get(current);

                    if (layer > bfsLayers) continue;

                    MemoryDB.FeelingDimension dim = dimMap.get(current);
                    if (dim != null) {
                        double noveltyW = FeelingsDB.noveltyCurve(dim.triggerCount);
                        double layerW = Math.pow(layerDecay, layer);
                        double contribution = noveltyW * layerW;
                        totalUE += contribution;

                        allUEUnits.add(UEUnit.builder()
                                .dimId(dim.id)
                                .concept(dim.concept)
                                .bfsLayer(layer)
                                .layerWeight(layerW)
                                .noveltyWeight(noveltyW)
                                .embedding(dim.vector)
                                .build());
                    }

                    // 扩展邻居（通过超图 BFS）
                    if (layer < bfsLayers && hypergraph != null) {
                        Set<Integer> neighbors = hypergraph.expandDimension(current, 1);
                        for (int n : neighbors) {
                            if (!visited.contains(n) && !layerMap.containsKey(n)) {
                                visited.add(n);
                                layerMap.put(n, layer + 1);
                                queue.add(n);
                            }
                        }
                    }
                }
            }
        }

        // 2. 同时查询 V4 FeelingsDB
        if (feelingsDB != null) {
            List<FeelingEntry> v4Feelings = feelingsDB.getAll();
            for (FeelingEntry f : v4Feelings) {
                double sim = CognitiveDB_CS(unitEmbedding, f.getEmbedding());
                if (sim > 0.5 && !visited.contains(f.getId())) {
                    double noveltyW = FeelingsDB.noveltyCurve(f.getActivationCount());
                    double contribution = sim * noveltyW;
                    totalUE += contribution;

                    allUEUnits.add(UEUnit.builder()
                            .dimId(f.getId())
                            .concept(f.getConcept())
                            .bfsLayer(0) // V4 感觉作为种子层
                            .layerWeight(1.0)
                            .noveltyWeight(noveltyW)
                            .embedding(f.getEmbedding())
                            .build());
                }
            }
        }

        // 兜底：当感觉数据库为空（冷启动）时，给予基于 SE 的默认 UE，
        // 确保单元仍能通过 selectionScore 的 baselineThreshold 检查。
        if (totalUE == 0.0 && unit.getStimulateEnergy() > 0) {
            totalUE = 0.6;
            allUEUnits.add(UEUnit.builder()
                    .dimId(-1)  // 合成节点，表示"无先验感觉匹配"
                    .concept("default_curiosity")
                    .bfsLayer(0)
                    .layerWeight(1.0)
                    .noveltyWeight(1.0)
                    .embedding(unitEmbedding)
                    .build());
            log.debug("[Pool] UE 冷启动兜底: SE=" + String.format("%.3f", unit.getStimulateEnergy()) + " → UE=0.6 (合成节点)");
        }

        unit.setUE(totalUE, allUEUnits);
        log.debug("[Pool] UE 计算完成: " + unit + " UE=" + String.format("%.3f", totalUE) + ", UEUnits=" + allUEUnits.size());
    }

    /**
     * 基于单元已匹配的感觉维度，计算注意力态度乘数。
     *
     * 对每个 UEUnit 取对应感觉维度的 attention_attitude，
     * 按匹配强度 (noveltyWeight × layerWeight) 加权求和，
     * 经全局缩放因子转换后得到乘数。
     *
     * multiplier = clamp(1.0 + rawModulation * ATTITUDE_SCALE, 0.3, 2.0)
     */
    private void computeAttentionMultiplier(CognitivePrepareUnit unit) {
        List<UEUnit> ueUnits = unit.getUeUnits();
        if (ueUnits == null || ueUnits.isEmpty()) {
            unit.setAttentionAttitudeMultiplier(1.0);
            return;
        }

        double totalUE = unit.getUnderstandEnergy();
        if (totalUE <= 0.0) {
            unit.setAttentionAttitudeMultiplier(1.0);
            return;
        }

        double rawModulation = 0.0;
        Map<Integer, Double> strengths = new HashMap<>();

        for (UEUnit u : ueUnits) {
            if (u.getDimId() <= 0) continue; // 跳过合成节点
            double contribution = u.getNoveltyWeight() * u.getLayerWeight();
            double matchStrength = contribution / totalUE;
            double attitude = feelingsDB != null ? feelingsDB.getAttentionAttitude(u.getDimId()) : 0.0;
            rawModulation += matchStrength * attitude;
            strengths.put(u.getDimId(), matchStrength);
        }

        unit.setFeelingMatchStrengths(strengths);

        double scale = CoreConfig.ATTENTION_ATTITUDE_SCALE;
        double multiplier = 1.0 + rawModulation * scale;
        unit.setAttentionAttitudeMultiplier(multiplier);

        if (Math.abs(multiplier - 1.0) > 0.01) {
            log.debug("[Pool] 注意力态度乘数: {:.2f} = 1 + ({:.3f} * {:.2f})",
                    multiplier, rawModulation, scale);
        }

        // ★ 心智日志
        MentalStateLogger.getInstance().attentionAttitudeMultiplier(
                unit.getUuid().toString(), multiplier, strengths.size());
    }

    /**
     * ★ 行为驱动态度变化：对单元匹配的每个感觉维度，按匹配强度分配 boost。
     *
     * 这是注意力态度系统的核心闭环——不是让 LLM 显式指定关注什么，
     * 而是让 Agent 的行为（next_actions、外部输入、被选中的任务）自然驱动态度变化。
     *
     * @param unit      已计算 UE 的准备单元
     * @param baseBoost 基准增量（由调用方按事件类型指定）
     */
    public void boostMatchedFeelings(CognitivePrepareUnit unit, double baseBoost) {
        if (unit == null || baseBoost == 0.0 || feelingsDB == null) return;

        List<UEUnit> ueUnits = unit.getUeUnits();
        if (ueUnits == null || ueUnits.isEmpty()) return;

        double totalUE = unit.getUnderstandEnergy();
        if (totalUE <= 0.0) return;

        int boosted = 0;
        for (UEUnit u : ueUnits) {
            if (u.getDimId() <= 0) continue;
            double contribution = u.getNoveltyWeight() * u.getLayerWeight();
            double matchStrength = contribution / totalUE;
            double delta = baseBoost * matchStrength;
            if (Math.abs(delta) < 0.001) continue;

            feelingsDB.adjustAttentionAttitude(u.getDimId(), delta);
            boosted++;

            MentalStateLogger.getInstance().attentionAttitudeBoosted(
                    u.getDimId(), u.getConcept(), delta);
        }

        if (boosted > 0) {
            log.debug("[Pool] boostMatchedFeelings: {} 个感觉维度各获得 boost={}, 共 {} 个",
                    boosted, String.format("%.3f", baseBoost), ueUnits.size());
        }
    }

    /** 获取 top-N UEUnit 的 dimId 列表 */
    private List<Integer> getTopDimIds(List<UEUnit> ueUnits, int n) {
        if (ueUnits == null || ueUnits.isEmpty()) return List.of();
        return ueUnits.stream()
                .filter(u -> u.getDimId() > 0)
                .sorted((a, b) -> Double.compare(b.getNoveltyWeight(), a.getNoveltyWeight()))
                .limit(n)
                .map(UEUnit::getDimId)
                .distinct()
                .toList();
    }

    /**
     * 通过 UUID 前缀匹配提升 ContinueWeight。
     * LLM 可能从池摘要中复制了截断的 UUID（如前 8 位），需要前缀匹配兜底。
     */
    public boolean boostContinueWeightByPrefix(String prefix, double boost) {
        if (prefix == null || prefix.isBlank()) return false;
        String lower = prefix.toLowerCase();
        double cap = Math.min(CoreConfig.SINGLE_BOOST_CAP, CoreConfig.MAX_CONTINUE_WEIGHT);
        double effectiveBoost = Math.min(boost, cap);

        synchronized (poolLock) {
            for (CognitivePrepareUnit unit : pool) {
                String fullUuid = unit.getUuid().toString().toLowerCase();
                if (fullUuid.startsWith(lower)) {
                    unit.boostContinueWeight(effectiveBoost, CoreConfig.MAX_CONTINUE_WEIGHT);
                    log.info("[Pool] LLM boost (prefix) unit {} ContinueWeight +{} = {}",
                            fullUuid.substring(0, 8), effectiveBoost, unit.getContinueWeight());
                    return true;
                }
            }
        }
        return false;
    }

    /** 提升指定单元的 ContinueWeight */
    public boolean boostContinueWeight(UUID uuid, double boost) {
        double cap = Math.min(CoreConfig.SINGLE_BOOST_CAP, CoreConfig.MAX_CONTINUE_WEIGHT);
        double effectiveBoost = Math.min(boost, cap);

        synchronized (poolLock) {
            for (CognitivePrepareUnit unit : pool) {
                if (unit.getUuid().equals(uuid)) {
                    unit.boostContinueWeight(effectiveBoost, CoreConfig.MAX_CONTINUE_WEIGHT);
                    log.info("[Pool] LLM boost unit {} ContinueWeight +{} = {}",
                            uuid.toString().substring(0, 8), effectiveBoost, unit.getContinueWeight());
                    return true;
                }
            }
        }
        log.debug("[Pool] boostContinueWeight: 单元已被选中或过期 UUID={}", uuid.toString().substring(0, 8));
        return false;
    }

    /** 获取池中所有单元（供 LLM 查看和 boost） */
    public List<CognitivePrepareUnit> getAllUnits() {
        synchronized (poolLock) {
            return new ArrayList<>(pool);
        }
    }

    /**
     * 按来源标识查找池中已有的单元。
     * 用于同源输入跨 tick 合并：当同一来源的新消息到达时，
     * 优先合并到已有单元而非创建新单元造成碎片化。
     *
     * @param sourceId 来源标识符（如 "qq_group:12345"）
     * @return 第一个匹配的单元，未找到返回 null
     */
    public CognitivePrepareUnit findBySource(String sourceId) {
        if (sourceId == null || sourceId.isBlank()) return null;
        synchronized (poolLock) {
            for (CognitivePrepareUnit unit : pool) {
                if (unit.getSourceIds() != null && unit.getSourceIds().contains(sourceId)) {
                    return unit;
                }
            }
        }
        return null;
    }

    /** 池大小 */
    public int size() {
        return pool.size();
    }

    /** 构建池状态摘要，供 LLM prompt 注入 */
    public String buildPoolSummary() {
        return buildPoolSummary(Integer.MAX_VALUE);
    }

    /**
     * 构建池状态摘要，限制最大单元数以控制 prompt 大小。
     * @param maxUnits 最多显示的单元数
     */
    public String buildPoolSummary(int maxUnits) {
        List<CognitivePrepareUnit> units = getAllUnits();
        if (units.isEmpty()) return "准备池为空";

        int shown = Math.min(units.size(), maxUnits);
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("【认知准备池】共 %d 个单元", units.size()));
        if (units.size() > maxUnits) {
            sb.append(String.format("（仅显示前 %d 个）", maxUnits));
        }
        sb.append(":\n");

        for (int i = 0; i < shown; i++) {
            CognitivePrepareUnit u = units.get(i);
            String text = u.getText();
            // 防止 null 在 format 中渲染为 "null" 字面量
            String displayText;
            if (text == null || text.isBlank()) {
                displayText = "(无文本)";
            } else if (text.length() > 40) {
                displayText = text.substring(0, 40) + "...";
            } else {
                displayText = text;
            }
            String tag = u.isEndogenous() ? "[内源]" : "";
            String uuidStr = u.getUuid().toString();
            sb.append(String.format("  - %s[%s] '%s' SE=%.2f attn=%.2f UE=%.2f tick=%d cw=%.2f\n",
                    tag,
                    uuidStr,
                    displayText,
                    u.getStimulateEnergy(), u.getAttentionEnergy(),
                    u.getUnderstandEnergy(),
                    u.getTick(), u.getContinueWeight()));
        }
        return sb.toString();
    }

    // 内联的余弦相似度（避免循环依赖 CognitiveDB）
    private static double CognitiveDB_CS(double[] a, double[] b) {
        if (a == null || b == null || a.length != b.length) return 0.0;
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return (na == 0 || nb == 0) ? 0.0 : dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}
