package com.cna.db;

import com.cna.config.ConfigsManager;
import com.cna.db.MemoryDB.FeelingDimension;
import com.cna.db.MemoryDB.HypergraphEdge;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 感觉超图管理器。
 *
 * 核心职责：
 * 1. expandDimension — 从指定维度出发，n 层 BFS 漫游，返回 Gi（关联维度集合）
 * 2. upsertEdge    — 插入或加权更新超图边
 * 3. findConnectingNodes — 查找连接多个维度的枢纽节点
 * 4. onCoOccurrence — 一组维度同时被触发时，自动建立/加权它们之间的关联边
 */
@Slf4j
public class FeelingHypergraphManager {

    private static volatile FeelingHypergraphManager INSTANCE;

    public static FeelingHypergraphManager getInstance() {
        if (INSTANCE == null) {
            synchronized (FeelingHypergraphManager.class) {
                if (INSTANCE == null) {
                    INSTANCE = new FeelingHypergraphManager(new MemoryDB());
                }
            }
        }
        return INSTANCE;
    }

    /** 仅在 init 阶段使用，避免绕过单例多次创建 MemoryDB 实例 */
    public static synchronized void init(MemoryDB memoryDB) {
        if (INSTANCE == null) {
            INSTANCE = new FeelingHypergraphManager(memoryDB);
        }
    }

    private final MemoryDB db;
    private final double edgeWeightDecay;
    private final int maxExpandNodes;
    private static final double MIN_PROPAGATION_WEIGHT = 0.15; // BFS 传播最低门槛（比旧值 0.05 高 3 倍）

    private FeelingHypergraphManager(MemoryDB db) {
        this.db = db;
        this.edgeWeightDecay = ConfigsManager.FEELING_HYPERGRAPH_EDGE_WEIGHT_DECAY;
        this.maxExpandNodes = ConfigsManager.FEELING_HYPERGRAPH_MAX_EXPAND_NODES;
    }

    // =====================================================
    // 超图扩展
    // =====================================================

    /**
     * 从指定维度出发，n 层 BFS 扩展。
     * 返回该维度在超图中可达的所有维度 ID 集合（包含自身）。
     * 每层扩展时，边权重乘以 decay 因子作为进入下一层的门槛。
     */
    public Set<Integer> expandDimension(int dimId, int nLayers) {
        Set<Integer> visited = new LinkedHashSet<>();
        Queue<Integer> queue = new LinkedList<>();
        Map<Integer, Double> layerWeights = new HashMap<>(); // 到达该节点时的累积权重

        visited.add(dimId);
        queue.add(dimId);
        layerWeights.put(dimId, 1.0);

        int layers = 0;
        while (!queue.isEmpty() && layers < nLayers && visited.size() < maxExpandNodes) {
            int levelSize = queue.size();
            for (int i = 0; i < levelSize; i++) {
                int current = queue.poll();
                double currentWeight = layerWeights.getOrDefault(current, 1.0);

                // 查出边 + 入边（超图是无向的）
                List<HypergraphEdge> outEdges = db.getEdgesFrom(current);
                List<HypergraphEdge> inEdges = db.getEdgesTo(current);

                Set<Integer> neighbors = new LinkedHashSet<>();
                for (HypergraphEdge e : outEdges) neighbors.add(e.targetDimId);
                for (HypergraphEdge e : inEdges) neighbors.add(e.sourceDimId);

                for (int neighbor : neighbors) {
                    if (visited.contains(neighbor)) continue;
                    double edgeWeight = 0;
                    for (HypergraphEdge e : outEdges) { if (e.targetDimId == neighbor) edgeWeight = Math.max(edgeWeight, e.weight); }
                    for (HypergraphEdge e : inEdges) { if (e.sourceDimId == neighbor) edgeWeight = Math.max(edgeWeight, e.weight); }

                    double propagatedWeight = currentWeight * Math.max(edgeWeight * edgeWeightDecay, 0.1);
                    if (propagatedWeight >= MIN_PROPAGATION_WEIGHT) { // 权重太低的不扩展（避免噪音扩散）
                        visited.add(neighbor);
                        queue.add(neighbor);
                        layerWeights.put(neighbor, propagatedWeight);
                    }
                }
            }
            layers++;
        }

        log.debug("[Hypergraph] dimId={} 经过 {} 层扩展得到 {} 个关联节点", dimId, layers, visited.size());
        return visited;
    }

    /**
     * 对一组维度进行批量扩展，返回 Map<源维度ID, Gi集合>。
     */
    public Map<Integer, Set<Integer>> expandMulti(List<Integer> dimIds, int nLayers) {
        Map<Integer, Set<Integer>> result = new LinkedHashMap<>();
        for (int dimId : dimIds) {
            result.put(dimId, expandDimension(dimId, nLayers));
        }
        return result;
    }

    // =====================================================
    // 边操作
    // =====================================================

    /**
     * 插入或加权更新超图边。
     */
    public void upsertEdge(int srcId, int tgtId, String relationType, double weightInc) {
        if (srcId == tgtId) return; // 不自连
        db.upsertHypergraphEdge(srcId, tgtId, relationType, weightInc);
        log.debug("[Hypergraph] 边 {}→{} ({}) +{}", srcId, tgtId, relationType, weightInc);
    }

    /**
     * 一组感觉维度同时被触发时调用。
     * 为其中任意两两组合建立/加权 associated 边。
     * 权重增量基于两个维度的 embedding 余弦相似度（0.1 ~ 0.6），
     * 语义越相似、共现证据越强；弱相关对获得较小增量，低频自然衰减消失。
     */
    public void onCoOccurrence(List<Integer> dimIds) {
        // 无向量版本：回退到统一 +0.3（兼容旧调用）
        onCoOccurrence(dimIds, null);
    }

    /**
     * @param dimIds      共现的维度 ID 列表
     * @param dimProvider 维度 ID → FeelingDimension 的查询函数，传 null 则退化为固定 +0.3
     */
    public void onCoOccurrence(List<Integer> dimIds,
                                java.util.function.Function<Integer, MemoryDB.FeelingDimension> dimProvider) {
        if (dimIds == null || dimIds.size() < 2) return;

        int pairCount = 0;
        double totalInc = 0;
        for (int i = 0; i < dimIds.size(); i++) {
            for (int j = i + 1; j < dimIds.size(); j++) {
                double weightInc;
                if (dimProvider != null) {
                    MemoryDB.FeelingDimension dimA = dimProvider.apply(dimIds.get(i));
                    MemoryDB.FeelingDimension dimB = dimProvider.apply(dimIds.get(j));
                    if (dimA != null && dimB != null && dimA.vector != null && dimB.vector != null) {
                        double sim = cosineSimilarity(dimA.vector, dimB.vector);
                        // sim ∈ [0,1] → weightInc ∈ [0.1, 0.6]
                        weightInc = 0.1 + sim * 0.5;
                    } else {
                        weightInc = 0.3;
                    }
                } else {
                    weightInc = 0.3;
                }
                upsertEdge(dimIds.get(i), dimIds.get(j), "associated", weightInc);
                upsertEdge(dimIds.get(j), dimIds.get(i), "associated", weightInc);
                pairCount += 2;
                totalInc += weightInc * 2;
            }
        }
        log.debug("[Hypergraph] {} 个维度共现，{} 对边加权，平均增量 {:.3f}",
                dimIds.size(), pairCount, pairCount > 0 ? totalInc / pairCount : 0);
    }

    /** 获取两个维度之间的最大边权重（双向查找）。 */
    public double getMaxEdgeWeightBetween(int dimIdA, int dimIdB) {
        return db.getMaxEdgeWeightBetween(dimIdA, dimIdB);
    }

    /**
     * 超图边全局衰减 + 弱边清理。
     * 建议在 FeelingDimensionManager.tick() 中周期性调用。
     */
    public void decayAllEdges(double decayFactor, double minWeight) {
        int deleted = db.decayHypergraphEdges(decayFactor, minWeight);
        if (deleted > 0) {
            log.info("[Hypergraph] 清理 {} 条弱边 (minWeight < {})", deleted, minWeight);
        }
    }

    private double cosineSimilarity(double[] a, double[] b) {
        if (a == null || b == null || a.length != b.length) return 0.0;
        double dot = 0, na = 0, nb = 0;
        for (int k = 0; k < a.length; k++) {
            dot += a[k] * b[k];
            na += a[k] * a[k];
            nb += b[k] * b[k];
        }
        return (na == 0 || nb == 0) ? 0.0 : dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    // =====================================================
    // 枢纽查找
    // =====================================================

    /**
     * 在超图中查找同时连接多个输入维度的枢纽节点。
     * @return Map<hubDimId, 连通的输入维度数量>
     */
    public Map<Integer, Integer> findConnectingNodes(List<Integer> dimIds) {
        return db.findHubNodes(dimIds);
    }

    /**
     * 获取从 src 到 tgt 之间的路径（简单 BFS，返回中间节点 ID 列表）。
     * 用于 LLM 解释"为什么这两个感觉被关联了"。
     */
    public List<Integer> findPath(int srcId, int tgtId, int maxHops) {
        if (srcId == tgtId) return List.of(srcId);

        Map<Integer, Integer> parent = new HashMap<>();
        Queue<Integer> queue = new LinkedList<>();
        Set<Integer> visited = new HashSet<>();

        queue.add(srcId);
        visited.add(srcId);

        int hops = 0;
        while (!queue.isEmpty() && hops < maxHops) {
            int levelSize = queue.size();
            for (int i = 0; i < levelSize; i++) {
                int current = queue.poll();
                if (current == tgtId) {
                    // 回溯路径
                    List<Integer> path = new ArrayList<>();
                    int node = tgtId;
                    while (node != srcId) {
                        path.add(0, node);
                        node = parent.get(node);
                    }
                    path.add(0, srcId);
                    return path;
                }

                Set<Integer> neighbors = new LinkedHashSet<>();
                for (HypergraphEdge e : db.getEdgesFrom(current)) neighbors.add(e.targetDimId);
                for (HypergraphEdge e : db.getEdgesTo(current)) neighbors.add(e.sourceDimId);

                for (int neighbor : neighbors) {
                    if (!visited.contains(neighbor)) {
                        visited.add(neighbor);
                        parent.put(neighbor, current);
                        queue.add(neighbor);
                    }
                }
            }
            hops++;
        }
        return List.of(); // 无路径
    }

    /**
     * 获取超图统计信息。
     */
    public String getStats() {
        List<HypergraphEdge> edges = db.getAllHypergraphEdges();
        int nodeCount = db.getAllFeelingDimensions().size();
        int edgeCount = edges.size();
        return String.format("超图: %d 节点, %d 边", nodeCount, edgeCount);
    }
}
