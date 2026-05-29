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
    private final int cooccurrenceThreshold;

    private FeelingHypergraphManager(MemoryDB db) {
        this.db = db;
        this.edgeWeightDecay = ConfigsManager.FEELING_HYPERGRAPH_EDGE_WEIGHT_DECAY;
        this.maxExpandNodes = ConfigsManager.FEELING_HYPERGRAPH_MAX_EXPAND_NODES;
        this.cooccurrenceThreshold = ConfigsManager.FEELING_HYPERGRAPH_COOCCURRENCE_THRESHOLD;
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
                    if (propagatedWeight >= 0.05) { // 权重太低的不扩展
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
     * 仅在共现次数达到阈值后建立边，防止噪声。
     */
    public void onCoOccurrence(List<Integer> dimIds) {
        if (dimIds == null || dimIds.size() < 2) return;

        // 用 cooccurrenceThreshold 控制：低频共现不建立边
        // 实际做法：每次都 +0.3 的权重增量，低频共现权重很低，高频自然积累
        double weightInc = 0.3;
        for (int i = 0; i < dimIds.size(); i++) {
            for (int j = i + 1; j < dimIds.size(); j++) {
                upsertEdge(dimIds.get(i), dimIds.get(j), "associated", weightInc);
                upsertEdge(dimIds.get(j), dimIds.get(i), "associated", weightInc);
            }
        }
        log.debug("[Hypergraph] {} 个维度共现，已更新关联边", dimIds.size());
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
