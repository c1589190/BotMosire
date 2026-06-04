package com.cna.apcore.feeling;

import com.cna.agent.CuriosityListManager;
import com.cna.agent.FeelingResonanceAnalyzer;
import com.cna.apcore.MentalStateLogger;
import com.cna.apcore.config.CoreConfig;
import com.cna.apcore.db.CognitiveDB;
import com.cna.apcore.db.ExperiencesDB;
import com.cna.apcore.db.FeelingsDB;
import com.cna.apcore.model.FeelingEntry;
import com.cna.db.FeelingDimensionManager;
import com.cna.db.FeelingHypergraphManager;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * V4 感觉维度管理器 —— 封装所有感觉相关的操作。
 *
 * 职责：
 * 1. 互斥感觉维度检测（语义相斥的已有概念）
 * 2. 刺激感觉处理（LLM 输出的 stimulated_feelings → dedup + 激活）
 * 3. action_feelings 处理（finish_action 中的感觉维度列表）
 * 4. 经验打分 + 准确度传播到关联感觉维度
 * 5. 感觉谐振分析（超图 BFS + 拐点检测，找出违和感觉）
 * 6. 违和感积累到好奇心列表
 *
 * 从 ActionLoop 中提取，减轻主编排器的职责负担。
 */
@Slf4j
public class FeelingsManager {

    // ── 互斥感觉维度检测 v2（触发内距离法 / Intra-Triggered Dissonance）──
    //
    // 算法思路：
    // 旧版将 actionText 与全部感觉逐一比较，把"不相似"当作"互斥"。
    // 但 754 个维度里总有几十个与任意文本距离远，这是统计噪声，不是认知矛盾。
    //
    // v2 反转方向：先找 actionText 真正触发的感觉（高相似度），
    // 再分析这些触发感觉彼此之间的距离。
    // 如果同一个 action 同时触发了语义上很远的两个感觉群，
    // 那才是真正的认知失调——被拉在一起但彼此格格不入。

    private final FeelingsDB feelingsDB;
    private final ExperiencesDB experiencesDB;
    private final Function<String, double[]> embedder;

    public FeelingsManager(FeelingsDB feelingsDB, ExperiencesDB experiencesDB,
                           Function<String, double[]> embedder) {
        this.feelingsDB = feelingsDB;
        this.experiencesDB = experiencesDB;
        this.embedder = embedder;
    }

    // ==========================================
    // 互斥感觉维度检测 v2
    // ==========================================

    /**
     * 检测与当前 action text 的认知失调（v2: 触发内距离法）。
     *
     * 算法步骤：
     * 1. 找触发感觉：取 actionText 相似度最高的 N 个感觉（高相似 = 被触发）
     * 2. 计算 pairwise 距离：触发感觉彼此之间的余弦距离矩阵
     * 3. 孤立检测：某个触发感觉到其他触发感觉的平均距离 > 阈值 → "触发但孤立"
     * 4. 远距对检测：两个触发感觉彼此距离很远 → "远距触发对"
     * 5. 可选聚类：对触发感觉做简单凝聚聚类，报告跨簇分离
     *
     * @param actionTextEmb action 文本的 embedding
     * @return 失调候选列表，按失调强度降序排列
     */
    public List<Map<String, Object>> detectMutualExclusions(double[] actionTextEmb) {
        // Step 1: 找触发感觉
        List<TriggeredFeeling> triggered = findTriggeredFeelings(actionTextEmb);
        if (triggered.size() < CoreConfig.INTRATRIGGER_MIN_TRIGGERED) {
            log.debug("[FeelingsManager] ⚔️ v2 触发感觉不足 ({} < {}), 跳过失调检测",
                    triggered.size(), CoreConfig.INTRATRIGGER_MIN_TRIGGERED);
            return List.of();
        }

        log.info("[FeelingsManager] ⚔️ v2 触发内距离检测: 触发 {} 个感觉 (threshold={})",
                triggered.size(), CoreConfig.INTRATRIGGER_TRIGGER_THRESHOLD);

        // Step 2: 计算触发感觉之间的 pairwise 余弦距离矩阵
        //    distanceMatrix[i][j] = 1 - cosineSimilarity(i, j)
        //    只计算上三角，下三角对称
        int n = triggered.size();
        double[][] peerDistances = new double[n][n]; // peerDistances[i][j] = 1 - sim(i,j)
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                double sim = CognitiveDB.cosineSimilarity(
                        triggered.get(i).embedding, triggered.get(j).embedding);
                peerDistances[i][j] = 1.0 - sim;
                peerDistances[j][i] = 1.0 - sim;
            }
        }

        // Step 3: 孤立触发感检测
        List<Map<String, Object>> candidates = new ArrayList<>();
        detectIsolatedFeelings(triggered, peerDistances, candidates);

        // Step 4: 远距触发对检测
        detectDissonantPairs(triggered, peerDistances, candidates);

        // Step 5: 聚类检测（可选）
        if (CoreConfig.INTRATRIGGER_CLUSTER_ENABLED && triggered.size() >= 6) {
            clusterTriggeredFeelings(triggered, peerDistances, candidates);
        }

        // 按失调强度降序排列，截断
        candidates.sort((a, b) -> Double.compare(
                ((Number) b.getOrDefault("dissonance_strength", 0.0)).doubleValue(),
                ((Number) a.getOrDefault("dissonance_strength", 0.0)).doubleValue()));

        if (candidates.size() > CoreConfig.INTRATRIGGER_MAX_REPORT) {
            candidates = candidates.subList(0, CoreConfig.INTRATRIGGER_MAX_REPORT);
        }

        if (!candidates.isEmpty()) {
            log.info("[FeelingsManager] ⚔️ v2 检测到 {} 个失调候选 (触发{}个感觉)",
                    candidates.size(), n);
        }
        return candidates;
    }

    // ── v2 内部数据模型 ──

    /** 触发感觉（带 peer 统计） */
    private static class TriggeredFeeling {
        final int dimId;
        final String concept;
        final double[] embedding;
        final double simToAction;  // 与 actionText 的相似度
        final int activationCount;
        double avgPeerDistance;    // 到其他触发感觉的平均距离
        List<Integer> closePeerIndices = new ArrayList<>();   // 近距同伴索引
        List<Integer> distantPeerIndices = new ArrayList<>(); // 远距同伴索引

        TriggeredFeeling(int dimId, String concept, double[] embedding,
                         double simToAction, int activationCount) {
            this.dimId = dimId;
            this.concept = concept;
            this.embedding = embedding;
            this.simToAction = simToAction;
            this.activationCount = activationCount;
        }
    }

    // ── v2 辅助方法 ──

    /** Step 1: 找被 actionText 触发的感觉（高相似度） */
    private List<TriggeredFeeling> findTriggeredFeelings(double[] actionTextEmb) {
        List<FeelingEntry> allFeelings = feelingsDB.getAll();
        if (allFeelings.isEmpty()) return List.of();

        double threshold = CoreConfig.INTRATRIGGER_TRIGGER_THRESHOLD;
        int maxN = CoreConfig.INTRATRIGGER_MAX_TRIGGERED;

        // 计算所有感觉与 action 的相似度，取 top-N
        List<TriggeredFeeling> candidates = new ArrayList<>();
        for (FeelingEntry f : allFeelings) {
            double sim = CognitiveDB.cosineSimilarity(actionTextEmb, f.getEmbedding());
            if (sim >= threshold) {
                candidates.add(new TriggeredFeeling(
                        f.getId(), f.getConcept(), f.getEmbedding(), sim, f.getActivationCount()));
            }
        }

        // 按 sim 降序，截断
        candidates.sort((a, b) -> Double.compare(b.simToAction, a.simToAction));
        if (candidates.size() > maxN) {
            candidates = candidates.subList(0, maxN);
        }
        return candidates;
    }

    /** Step 3: 检测"触发但孤立"的感觉 */
    private void detectIsolatedFeelings(List<TriggeredFeeling> triggered,
                                         double[][] peerDistances,
                                         List<Map<String, Object>> out) {
        int n = triggered.size();
        double isolationThreshold = CoreConfig.INTRATRIGGER_ISOLATION_THRESHOLD;
        double pairThreshold = CoreConfig.INTRATRIGGER_PAIR_THRESHOLD;

        // 计算每个触发感觉的 avgPeerDistance + close/distant peers
        for (int i = 0; i < n; i++) {
            TriggeredFeeling tf = triggered.get(i);
            double sum = 0;
            for (int j = 0; j < n; j++) {
                if (i == j) continue;
                double dist = peerDistances[i][j]; // 1 - sim
                sum += dist;
                if (dist > isolationThreshold) {
                    tf.distantPeerIndices.add(j);
                } else if (dist < 0.35) { // 距离 < 0.35 → sim > 0.65 → 语义相近
                    tf.closePeerIndices.add(j);
                }
            }
            tf.avgPeerDistance = sum / (n - 1);
        }

        // 查超图边：哪些触发感觉之间有超图连接
        FeelingHypergraphManager hgm = FeelingHypergraphManager.getInstance();
        Set<String> edgePairs = new HashSet<>(); // "i-j" 格式表示有边
        if (hgm != null) {
            for (int i = 0; i < n; i++) {
                for (int j = i + 1; j < n; j++) {
                    boolean edgeExists = hasHypergraphEdge(hgm,
                            triggered.get(i).dimId, triggered.get(j).dimId);
                    if (edgeExists) {
                        edgePairs.add(i + "-" + j);
                        edgePairs.add(j + "-" + i);
                    }
                }
            }
        }

        // 输出孤立感觉
        for (int i = 0; i < n; i++) {
            TriggeredFeeling tf = triggered.get(i);
            if (tf.avgPeerDistance < isolationThreshold) continue; // 不孤立

            int graphEdges = 0;
            for (int j = 0; j < n; j++) {
                if (i != j && edgePairs.contains(i + "-" + j)) graphEdges++;
            }

            double dissonanceStrength = tf.avgPeerDistance; // 基础强度
            // 如果有超图边但距离远 → 更强的失调信号（历史上共现过但语义不一致）
            if (graphEdges > 0 && tf.avgPeerDistance > isolationThreshold + 0.15) {
                dissonanceStrength += 0.2;
            }

            Map<String, Object> entry = new HashMap<>();
            entry.put("dim_id", tf.dimId);
            entry.put("concept", tf.concept);
            entry.put("dissonance_type", "isolated");
            entry.put("dissonance_strength", Math.round(dissonanceStrength * 1000.0) / 1000.0);
            entry.put("avg_peer_distance", Math.round(tf.avgPeerDistance * 1000.0) / 1000.0);
            entry.put("sim_to_action", Math.round(tf.simToAction * 1000.0) / 1000.0);
            entry.put("peer_count", n - 1);
            entry.put("activation_count", tf.activationCount);

            // 近距同伴（最多3个）
            List<String> closeNames = tf.closePeerIndices.stream()
                    .limit(3).map(j -> triggered.get(j).concept).toList();
            entry.put("close_peers", closeNames);

            // 远距同伴（最多3个）
            List<String> distantNames = tf.distantPeerIndices.stream()
                    .limit(3).map(j -> triggered.get(j).concept).toList();
            entry.put("distant_peers", distantNames);

            entry.put("graph_edge_to_peers", graphEdges);
            out.add(entry);
        }
    }

    /** Step 4: 检测远距触发对 */
    private void detectDissonantPairs(List<TriggeredFeeling> triggered,
                                       double[][] peerDistances,
                                       List<Map<String, Object>> out) {
        int n = triggered.size();
        double pairThreshold = CoreConfig.INTRATRIGGER_PAIR_THRESHOLD;
        FeelingHypergraphManager hgm = FeelingHypergraphManager.getInstance();

        // 找最远的 N/2 对
        List<int[]> distantPairs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                double sim = 1.0 - peerDistances[i][j];
                if (sim < pairThreshold) {
                    distantPairs.add(new int[]{i, j});
                }
            }
        }

        // 按距离降序
        distantPairs.sort((a, b) -> Double.compare(
                peerDistances[b[0]][b[1]], peerDistances[a[0]][a[1]]));

        int maxPairs = Math.min(CoreConfig.INTRATRIGGER_MAX_REPORT / 2, distantPairs.size());
        for (int p = 0; p < maxPairs; p++) {
            int[] pair = distantPairs.get(p);
            TriggeredFeeling a = triggered.get(pair[0]);
            TriggeredFeeling b = triggered.get(pair[1]);
            double dist = peerDistances[pair[0]][pair[1]];

            boolean hasEdge = false;
            if (hgm != null) {
                hasEdge = hasHypergraphEdge(hgm, a.dimId, b.dimId);
            }

            double strength = dist;
            if (hasEdge) strength += 0.15; // 有边但距离远 → 更强的矛盾信号

            Map<String, Object> entry = new HashMap<>();
            entry.put("dim_id", a.dimId);
            entry.put("concept", a.concept);
            entry.put("dissonance_type", "dissonant_pair");
            entry.put("dissonance_strength", Math.round(strength * 1000.0) / 1000.0);
            entry.put("pair_concept", b.concept);
            entry.put("pair_dim_id", b.dimId);
            entry.put("pair_distance", Math.round(dist * 1000.0) / 1000.0);
            entry.put("pair_similarity", Math.round((1.0 - dist) * 1000.0) / 1000.0);
            entry.put("pair_has_hypergraph_edge", hasEdge);
            entry.put("avg_peer_distance", Math.round(a.avgPeerDistance * 1000.0) / 1000.0);
            entry.put("peer_count", n - 1);
            out.add(entry);
        }
    }

    /** Step 5: 对触发感觉做简单凝聚聚类 */
    private void clusterTriggeredFeelings(List<TriggeredFeeling> triggered,
                                           double[][] peerDistances,
                                           List<Map<String, Object>> out) {
        int n = triggered.size();
        // 简单凝聚：用距离阈值 0.5 做连通分量
        double clusterThreshold = 0.5; // 距离 < 0.5 → sim > 0.5 → 同簇
        int[] clusterId = new int[n];
        Arrays.fill(clusterId, -1);

        int nextCluster = 0;
        for (int i = 0; i < n; i++) {
            if (clusterId[i] >= 0) continue;
            // 新建一个簇
            clusterId[i] = nextCluster;
            // BFS 扩展
            Deque<Integer> queue = new ArrayDeque<>();
            queue.add(i);
            while (!queue.isEmpty()) {
                int cur = queue.poll();
                for (int j = 0; j < n; j++) {
                    if (clusterId[j] >= 0 || j == cur) continue;
                    if (peerDistances[cur][j] < clusterThreshold) {
                        clusterId[j] = nextCluster;
                        queue.add(j);
                    }
                }
            }
            nextCluster++;
        }

        int numClusters = nextCluster;
        if (numClusters <= 1) return; // 只有一个簇，无跨簇失调

        // 计算簇间平均距离
        Map<Integer, List<Integer>> clusterMembers = new HashMap<>();
        for (int i = 0; i < n; i++) {
            clusterMembers.computeIfAbsent(clusterId[i], k -> new ArrayList<>()).add(i);
        }

        // 找出最远的两个簇
        double maxInterClusterDist = 0;
        int c1 = 0, c2 = 1;
        for (int ci = 0; ci < numClusters; ci++) {
            for (int cj = ci + 1; cj < numClusters; cj++) {
                double sum = 0;
                int count = 0;
                for (int i : clusterMembers.get(ci)) {
                    for (int j : clusterMembers.get(cj)) {
                        sum += peerDistances[i][j];
                        count++;
                    }
                }
                double avgDist = sum / count;
                if (avgDist > maxInterClusterDist) {
                    maxInterClusterDist = avgDist;
                    c1 = ci;
                    c2 = cj;
                }
            }
        }

        if (maxInterClusterDist < 0.5) return; // 簇间距离不够大，无明显失调

        // 报告跨簇分离
        List<String> clusterConcepts1 = clusterMembers.get(c1).stream()
                .limit(5).map(i -> triggered.get(i).concept).toList();
        List<String> clusterConcepts2 = clusterMembers.get(c2).stream()
                .limit(5).map(i -> triggered.get(i).concept).toList();

        Map<String, Object> entry = new HashMap<>();
        entry.put("dim_id", -1);
        entry.put("concept", "跨簇分离: " + numClusters + "个簇");
        entry.put("dissonance_type", "cluster_separation");
        entry.put("dissonance_strength", Math.round(maxInterClusterDist * 1000.0) / 1000.0);
        entry.put("num_clusters", numClusters);
        entry.put("inter_cluster_distance", Math.round(maxInterClusterDist * 1000.0) / 1000.0);
        entry.put("cluster_a_concepts", clusterConcepts1);
        entry.put("cluster_b_concepts", clusterConcepts2);
        entry.put("cluster_a_size", clusterMembers.get(c1).size());
        entry.put("cluster_b_size", clusterMembers.get(c2).size());
        out.add(entry);
    }

    /** 检查两个感觉维度在超图中是否存在边 */
    private boolean hasHypergraphEdge(FeelingHypergraphManager hgm, int dimA, int dimB) {
        try {
            return hgm.getMaxEdgeWeightBetween(dimA, dimB) > 0.0;
        } catch (Exception e) {
            // 超图查询失败属非关键路径，降级处理
            log.debug("[FeelingsManager] 超图边查询失败 dimA={} dimB={}: {}", dimA, dimB, e.getMessage());
        }
        return false;
    }

    // ==========================================
    // 刺激感觉处理
    // ==========================================

    /**
     * 处理 LLM 返回的 stimulated_feelings。
     * 对每个感觉维度：先 dedup（embedding），再插入或增加激活次数。
     *
     * @param feelingsArray stimulated_feelings JSON 数组
     * @return 所有关联的感觉维度 ID 列表
     */
    public List<Integer> processStimulatedFeelings(JsonNode feelingsArray) {
        List<Integer> dimIds = new ArrayList<>();
        if (feelingsArray == null || !feelingsArray.isArray()) {
            log.debug("[FeelingsManager] 无 stimulated_feelings");
            return dimIds;
        }

        int count = feelingsArray.size();
        log.info("[FeelingsManager] 🎯 处理 {} 个刺激感觉维度", count);

        for (JsonNode f : feelingsArray) {
            String concept = f.path("concept").asText();
            String embText = f.path("embedding_text").asText(concept);

            if (concept.isBlank()) continue;

            double[] emb = embedder.apply(embText);
            int id = feelingsDB.insertFeeling(concept, emb);
            if (id > 0) {
                feelingsDB.incrementActivation(id);
                dimIds.add(id);
                log.debug("[FeelingsManager]   刺激感觉: '{}' id={}", concept, id);

                // ★ 心智日志：感觉刺激
                FeelingEntry entry = feelingsDB.getById(id);
                MentalStateLogger.getInstance().feelingStimulated(
                        id, concept, true,
                        entry != null ? FeelingsDB.noveltyCurve(entry.getActivationCount()) : 1.0,
                        entry != null ? entry.getActivationCount() : 1);
            }
        }

        if (!dimIds.isEmpty()) {
            log.info("[FeelingsManager] ✅ 本轮刺激 {} 个感觉维度", dimIds.size());
        }
        return dimIds;
    }

    // ==========================================
    // action_feelings 处理（来自 finish_action）
    // ==========================================

    /**
     * 处理 finish_action 中的 action_feelings 字段。
     * 对已有维度验证存在后 incrementActivation，
     * 对新维度生成 embedding → insertFeeling (dedup) → 得到真实 ID。
     *
     * @param actionFeelingsArray finish_action 的 action_feelings JSON 数组
     * @return 解析出的所有维度 ID 列表
     */
    public List<Integer> processActionFeelings(JsonNode actionFeelingsArray) {
        List<Integer> resolvedDimIds = new ArrayList<>();
        if (actionFeelingsArray == null || !actionFeelingsArray.isArray()
                || actionFeelingsArray.size() == 0) {
            return resolvedDimIds;
        }

        int existingCount = 0;
        int newCount = 0;

        for (JsonNode af : actionFeelingsArray) {
            int dimId = af.path("dim_id").asInt(-1);
            String concept = af.path("concept").asText("");
            String embText = af.path("embedding_text").asText("");
            String relation = af.path("relation").asText("");

            if (dimId > 0) {
                // 已有维度：验证存在 → incrementActivation
                FeelingEntry existing = feelingsDB.getById(dimId);
                if (existing != null) {
                    feelingsDB.incrementActivation(dimId);
                    resolvedDimIds.add(dimId);
                    existingCount++;
                    MentalStateLogger.getInstance().feelingAction(dimId, existing.getConcept(), relation);
                } else {
                    log.warn("[FeelingsManager] action_feelings 引用不存在的 dim_id={}，跳过", dimId);
                }
            } else if (!concept.isBlank()) {
                // 新维度：生成 embedding → insertFeeling (dedup) → 得到真实 ID
                String textForEmb = !embText.isBlank() ? embText : concept;
                double[] emb = embedder.apply(textForEmb);
                if (emb != null) {
                    int realId = feelingsDB.insertFeeling(concept, emb);
                    if (realId > 0) {
                        resolvedDimIds.add(realId);
                        newCount++;
                        log.info("[FeelingsManager]   🆕 action_feeling 新维度: '{}' → id={}, relation={}",
                                concept, realId, relation);
                        MentalStateLogger.getInstance().feelingAction(realId, concept, relation);
                    }
                }
            }
        }

        log.info("[FeelingsManager] 📋 action_feelings 处理完成: {} 已有 + {} 新增 = {} 个感觉维度",
                existingCount, newCount, resolvedDimIds.size());
        return resolvedDimIds;
    }

    // ==========================================
    // 经验打分 + 准确度传播
    // ==========================================

    /**
     * 应用 LLM 返回的经验打分，并传播准确度增量到关联的感觉维度。
     */
    public void applyExperienceScoring(JsonNode scoringArray) {
        if (scoringArray == null || !scoringArray.isArray()) {
            log.debug("[FeelingsManager] 无 experience_scoring");
            return;
        }

        int count = scoringArray.size();
        log.info("[FeelingsManager] 📝 应用 {} 条经验打分", count);

        for (JsonNode s : scoringArray) {
            int expId = s.path("experience_id").asInt(-1);
            double score = s.path("score").asDouble(0.0);

            if (expId < 0) continue;

            // 限制 LLM 只用 1/0/-1
            double clamped = score > 0 ? 1.0 : (score < 0 ? -1.0 : 0.0);

            // 更新经验 HelpfulDegree
            experiencesDB.updateHelpfulDegree(expId, clamped);

            // 传播准确度到关联的感觉维度
            ExperiencesDB.ExperienceEntry exp = experiencesDB.getById(expId);
            if (exp != null && !exp.feelingDimIds.isEmpty()) {
                double delta = clamped / exp.feelingDimIds.size();
                for (int dimId : exp.feelingDimIds) {
                    feelingsDB.propagateAccuracy(dimId, delta);
                }
                log.info("[FeelingsManager]   经验 id=" + expId + " 打分=" + clamped
                        + ", 准确度传播到 " + exp.feelingDimIds.size() + " 个感觉维度 (delta=" + String.format("%.4f", delta) + ")");

                MentalStateLogger.getInstance().feelingExperienceScored(
                        expId, clamped, exp.feelingDimIds.size());
            }
        }
    }

    // ==========================================
    // 感觉谐振分析
    // ==========================================

    /**
     * 对 action text 做超图 BFS + 拐点检测，找出违和感觉维度。
     *
     * @param actionText 动作文本
     * @return 谐振分析结果（含 llmPromptBlock），失败返回 null
     */
    public FeelingResonanceAnalyzer.ResonanceAnalysisResult analyzeResonance(String actionText) {
        try {
            FeelingDimensionManager fdm = FeelingDimensionManager.getInstance();
            FeelingHypergraphManager hgm = FeelingHypergraphManager.getInstance();
            if (fdm != null && hgm != null) {
                FeelingResonanceAnalyzer analyzer = new FeelingResonanceAnalyzer(fdm, hgm, null);
                FeelingResonanceAnalyzer.ResonanceAnalysisResult resonance = analyzer.analyze(actionText);
                if (resonance != null) {
                    log.info("[FeelingsManager] 🔍 感觉谐振分析完成: {} 组, 有违和={}",
                            resonance.groups.size(), resonance.hasDissonance());

                    // ★ 心智日志：谐振分析
                    int dissonantCount = (int) resonance.groups.stream()
                            .mapToLong(g -> g.getDissonant().size()).sum();
                    int consonantCount = (int) resonance.groups.stream()
                            .mapToLong(g -> g.getConsonant().size()).sum();
                    double maxDissonanceScore = resonance.groups.stream()
                            .flatMap(g -> g.getDissonant().stream())
                            .mapToDouble(ds -> ds.similarity)
                            .max().orElse(0.0);
                    MentalStateLogger.getInstance().feelingResonance(
                            dissonantCount, consonantCount, maxDissonanceScore);
                }
                return resonance;
            }
        } catch (Exception e) {
            log.warn("[FeelingsManager] 感觉谐振分析失败，跳过: {}", e.getMessage());
        }
        return null;
    }

    // ==========================================
    // 违和感积累
    // ==========================================

    /**
     * 将有违和的谐振分析结果持久化到好奇心列表。
     */
    public void accumulateDissonance(
            FeelingResonanceAnalyzer.ResonanceAnalysisResult resonance,
            String actionText) {
        if (resonance == null || !resonance.hasDissonance()) return;

        try {
            CuriosityListManager clm = CuriosityListManager.getInstance();
            if (clm != null) {
                clm.accumulateFromResonance(resonance, actionText);
                log.info("[FeelingsManager] 📝 违和感已积累到好奇心列表");

                // ★ 心智日志：违和感积累
                int dissonantGroupCount = (int) resonance.groups.stream()
                        .filter(g -> g.hasDissonance()).count();
                double peakDissonance = resonance.groups.stream()
                        .flatMap(g -> g.getDissonant().stream())
                        .mapToDouble(ds -> ds.similarity)
                        .max().orElse(0.0);
                MentalStateLogger.getInstance().feelingDissonanceAccumulated(
                        dissonantGroupCount, peakDissonance);
            }
        } catch (Exception e) {
            log.warn("[FeelingsManager] 违和感积累失败: {}", e.getMessage());
        }
    }
}
