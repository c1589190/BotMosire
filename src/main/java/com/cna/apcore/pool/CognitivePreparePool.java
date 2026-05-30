package com.cna.apcore.pool;

import com.cna.apcore.config.CoreConfig;
import com.cna.apcore.db.ExperiencesDB;
import com.cna.apcore.db.FeelingsDB;
import com.cna.apcore.model.*;
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

    /** 推送一个准备单元到池中 */
    public void push(CognitivePrepareUnit unit) {
        if (unit == null) return;
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
            log.debug("[Pool] 推送新单元: {} (池大小: {})", unit, pool.size());
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
     * 1. 对每个单元计算 UE（理解能量）— BFS 搜索感觉维度超图
     * 2. 找到 selectionScore = max(SE×UE×tick) 且 SE×UE > baselineThreshold
     * 3. 计算 CognitiveAction 的 6 个情绪字段
     * 4. 从 ExperiencesDB 检索 ActionPredicts
     *
     * @param embedder    文本→embedding 函数
     * @param experiencesDB 经验数据库（用于检索先验经验）
     * @param feelingsDB  V4 感觉数据库（用于 UE 计算和去重）
     * @param hypergraph  感觉超图（用于 BFS 扩展）
     * @return 选中的 CognitiveAction，如果没有符合条件的单元则返回 null
     */
    public CognitiveAction selectAndConvert(
            Function<String, double[]> embedder,
            ExperiencesDB experiencesDB,
            FeelingsDB feelingsDB,
            FeelingHypergraphManager hypergraph) {

        List<CognitivePrepareUnit> units;
        synchronized (poolLock) {
            if (pool.isEmpty()) return null;
            units = new ArrayList<>(pool);
        }

        // 计算每个单元的 UE
        FeelingDimensionManager fdm = FeelingDimensionManager.getInstance();
        for (CognitivePrepareUnit unit : units) {
            if (unit.getUeUnits() == null || unit.getUeUnits().isEmpty()) {
                computeUE(unit, embedder, fdm, hypergraph, feelingsDB);
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

        log.info("[Pool] 选中单元: {} (score={:.3f})", best, bestScore);

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

        log.info("[Pool] 转换为 CognitiveAction — CF={:.3f}, Scale={}, Accident={:.3f}, Predicts={}",
                action.getCognitiveFamiliarity(), action.getScale(),
                action.getAccidentDegree(), action.getActionPredicts().size());

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
            Function<String, double[]> embedder,
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
            double[] textEmb = embedder.apply(unit.getText());
            for (FeelingEntry f : v4Feelings) {
                double sim = CognitiveDB_CS(textEmb, f.getEmbedding());
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
                    .embedding(embedder.apply(unit.getText()))
                    .build());
            log.debug("[Pool] UE 冷启动兜底: SE={:.3f} → UE=0.6 (合成节点)", unit.getStimulateEnergy());
        }

        unit.setUE(totalUE, allUEUnits);
        log.debug("[Pool] UE 计算完成: {} UE={:.3f}, UEUnits={}",
                unit, totalUE, allUEUnits.size());
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
        log.warn("[Pool] boostContinueWeight: 未找到 UUID={}", uuid.toString().substring(0, 8));
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
        List<CognitivePrepareUnit> units = getAllUnits();
        if (units.isEmpty()) return "准备池为空";

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("【认知准备池】共 %d 个单元:\n", units.size()));
        for (CognitivePrepareUnit u : units) {
            sb.append(String.format("  - [%s] '%s' SE=%.2f UE=%.2f tick=%d cw=%.2f\n",
                    u.getUuid().toString().substring(0, 8),
                    u.getText() != null && u.getText().length() > 40
                            ? u.getText().substring(0, 40) + "..." : u.getText(),
                    u.getStimulateEnergy(), u.getUnderstandEnergy(),
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
