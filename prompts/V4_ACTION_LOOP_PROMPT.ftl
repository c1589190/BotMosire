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

【认知状态 — 6 个情绪维度】
认知熟悉度 (CognitiveFamiliarity): ${cognitive_familiarity}
  说明：ActionText 与所有关联感觉维度的加权相似度之和，越高表示越"似曾相识"。
认知规模 (Scale): ${scale}
  说明：根据关联的感觉节点数量决定，影响检索多少条先验经验。
意外度 (AccidentDegree): ${accident_degree}
  说明：UnderstandingEnergy - CognitiveFamiliarity，正值表示"意料之外"。
行动压力 (ActionPressure): ${action_pressure}
  说明：当前为 TODO，暂固定为 0。
持续权重 (ContinueWeight): ${continue_weight}
  说明：初始 1，每 tick 衰减。LLM 可通过 continue_weight_boosts 给池中感兴趣的单元加权。

【关联的感觉维度】
概念: ${ue_concepts?join(", ")}<#if ue_concepts?size == 0>（无）</#if>
维度ID: ${ue_dim_ids?join(", ")}<#if ue_dim_ids?size == 0>（无）</#if>

<#if mutual_exclusions?has_content>
【互斥感觉维度 — 与当前场景语义相斥的已有概念】
以下是你认知体系中已建立、但与当前输入高度不匹配的感觉概念，
可能指向矛盾情境、认知盲区或情境转变：
<#list mutual_exclusions as m>
  · 【${m.concept}】 (dim_id=${m.dim_id}, 相似度=${m.similarity}, 已激活${m.activation_count}次)
</#list>
</#if>

<#if feeling_resonance?has_content>
${feeling_resonance}
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
