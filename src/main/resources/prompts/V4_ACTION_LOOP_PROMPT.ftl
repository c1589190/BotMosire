<#-- V4 认知动作循环 Prompt 模板 -->
<#-- 上下文：LLM 接收到一个 CognitiveAction，需要执行工具、刺激感觉维度、存储经验 -->

【当前时间】${now_time}
来源: ${source_ids?join(", ")}<#if source_ids?size == 0>（未知）</#if>

【动作文本（ActionText）】
${action_text}

【你为什么被选中执行本轮动作】
${selection_reason}

<#if demand_analysis?has_content>
【动机状态】
${demand_analysis}
</#if>

【认知状态】
语义权重 (SemanticWeight): ${selection_semantic_weight}
  说明：ActionText 在感觉维度超图 BFS 匹配结果（外源有值，内源为 0）。
来源优先级 (SourcePriority): ${selection_src_priority}
  说明：消息的实践重要性（私聊>群聊、@提及>无@、多消息>少消息；内源=继承父权重+定值）。
实践加成 (PracticalBonus): ${selection_practical_bonus}
  说明：来源优先级 + 挂起时间加成 + 注意力蓄能。
认知熟悉度 (CF): ${cognitive_familiarity}
认知规模 (Scale): ${scale}
意外度 (AccidentDegree): ${accident_degree}
持续权重 (ContinueWeight): ${continue_weight}

【关联的感觉维度】
概念: ${ue_concepts?join(", ")}<#if ue_concepts?size == 0>（无）</#if>
维度ID: ${ue_dim_ids?join(", ")}<#if ue_dim_ids?size == 0>（无）</#if>

<#if mutual_exclusions?has_content>
【认知失调检测 — 同一场景触发但彼此语义遥远的感受维度】
当前 ActionText 同时触发了以下感受，但它们在被激活的感受群中处于孤立/远距位置，
可能指向认知矛盾、情境冲突或需要整合的认知盲区：

<#list mutual_exclusions as m>
  <#if m.dissonance_type == "isolated">
  · ⚡孤立触发感: 【${m.concept}】
    到其他触发感觉的平均距离: ${m.avg_peer_distance}（越低越近，越高越孤）
    与action的相似度: ${m.sim_to_action}
    近距同伴: <#if m.close_peers?has_content>${m.close_peers?join(", ")}<#else>（无）</#if>
    远距同伴: <#if m.distant_peers?has_content>${m.distant_peers?join(", ")}<#else>（无）</#if>
    超图中与同伴的连接边: ${m.graph_edge_to_peers} 条
    <#if m.graph_edge_to_peers gt 0>⚠️ 虽然超图中有连接但语义距离远，可能指向深层矛盾</#if>

  <#elseif m.dissonance_type == "dissonant_pair">
  · 🔗 远距触发对: 【${m.concept}】 ⟷ 【${m.pair_concept}】
    两感之间的相似度: ${m.pair_similarity}（越低越远）
    超图边: <#if m.pair_has_hypergraph_edge>有（历史上共现过但语义远→熟悉张力）<#else>无（首次被同一action拉到一起→新颖张力）</#if>

  <#elseif m.dissonance_type == "cluster_separation">
  · 🧩 跨簇分离: ${m.num_clusters}个触发感群，簇间距离 ${m.inter_cluster_distance}
    A簇 (${m.cluster_a_size}个): ${m.cluster_a_concepts?join(", ")}
    B簇 (${m.cluster_b_size}个): ${m.cluster_b_concepts?join(", ")}
    这两簇被同一action同时触发但语义遥远，可能指示一个需要桥接的认知裂缝

  </#if>
</#list>
</#if>

<#if feeling_resonance?has_content>
${feeling_resonance}
</#if>

<#if curiosity_context?has_content>
${curiosity_context}
</#if>

【先验经验（从经验库按感觉维度检索）】
<#if action_predicts_text?has_content>
${action_predicts_text}
<#else>
（无相关先验经验）
</#if>

<#if related_experiences_text?has_content>
${related_experiences_text}
</#if>

<#if predicted_experiences_text?has_content>
${predicted_experiences_text}
</#if>

<#if action_templates_text?has_content>
${action_templates_text}
</#if>

<#if tools_guide?has_content>
【工具使用指南】
${tools_guide}

</#if>
${pool_summary}

---
【你的任务】

你需要基于以上认知上下文，以 JSON 格式返回以下内容：

1. **thoughts**: 你的思考过程（字符串）

2. **tool_calls**: 工具调用列表（标准 OpenAI function-calling 格式）
   ```json
   [{
     "id": "call_1",
     "type": "function",
     "function": {
       "name": "send_chat_message",
       "arguments": "{\"target\": \"...\", \"message\": \"...\"}"
     }
   }]
   ```
   如果本轮不需要调用工具，传空数组 []。

3. **stimulated_feelings**: 本次行动重点刺激的感觉维度（数组）
   列出本次行动让你"想到"或"激活"的语义感觉概念。可以添加新的。
   ```json
   [{
     "concept": "用户对天气的关心",
     "embedding_text": "用户询问今天天气如何，表现出对天气的关注"
   }]
   ```
   不要求输出语义正负极性。被返回后这些单元的刺激程度会被加权。

4. **new_prepare_unit**: 新的预备认识（对象或 null）
   如果本轮行动有未完成的事项，描述之；否则传 null。
   ```json
   {
     "text": "需要继续关注用户关于出行的后续问题",
     "sourceIds": ["qqid:12345"]
   }
   ```
   如果是 null 则表示本轮所有事情已完成。

5. **experience_scoring**: 对引用的先验经验打分（数组）
   评估【先验经验】中每条经验对本次行动是否有帮助：
   - 1 = 有帮助
   - 0 = 中性/不确定
   - -1 = 没帮助/误导
   ```json
   [{
     "experience_id": 42,
     "score": 1
   }]
   ```
   你的打分会被用于实时更新经验库的 helpful_degree 和 score_count。
   被验证次数越多的经验在后续检索中越靠前 — 不需要打负分来淘汰，
   常被打分的自然上浮，不打分的自然下沉。

6. **continue_weight_boosts**: 给准备池中的单元加权（数组）
   你可以给【认知准备池】中感兴趣的单元增加 ContinueWeight，让它们更早被选中。
   推荐单次 boost 值：0.5 ~ 1.0。
   ```json
   [{
     "unit_uuid": "a1b2c3d4-...",
     "boost": 0.8
   }]
   ```
   如果不确定该不该 boost，传空数组 []。

---
请以纯 JSON 返回（不要包裹在 ```json 代码块中），确保可以被直接解析。
