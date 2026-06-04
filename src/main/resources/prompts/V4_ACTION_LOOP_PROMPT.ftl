<#-- V4 认知动作循环 Prompt — 精简版 -->
<#-- LLM 只看到：action_text + 触发的感觉 + top 经验 + 预测经验 -->

【当前时间】${now_time}
来源: ${source_ids?join(", ")}<#if source_ids?size == 0>（未知）</#if>

【动作文本】
${action_text}

【触发的感觉维度】
<#if ue_concepts?size gt 0>
${ue_concepts?join(", ")}
<#else>
（无 — 冷启动或内源任务）
</#if>

<#if action_templates_text?has_content>
${action_templates_text}
</#if>

<#if action_predicts_text?has_content>
【类似场景的过往经验】
${action_predicts_text}
</#if>

<#if predicted_experiences_text?has_content>
【预测 — 基于顺序通道的后续经验】
${predicted_experiences_text}
</#if>

---
【你的任务】

本轮你需要进行工具调用。优先使用【行动模板】中推荐的最高频工具。
如果模板为空或不适用，自行选择工具。

返回格式：标准 OpenAI function-calling 格式的 tool_calls 数组。
如果确实不需要调用任何工具，返回空数组 []。

建议：完成必要工具调用后，调用 finish_action 结算本轮认知周期。
finish_action 参数：
{
  "thoughts": "你的思考过程",
  "stimulated_feelings": [{"concept": "概念", "embedding_text": "描述"}],
  "experience_scoring": [{"experience_id": 42, "score": 1}],
  "experience_annotations": [{"experience_id": 42, "annotation": "追评内容"}],
  "new_prepare_unit": {"text": "未完成事项", "sourceIds": ["qqid:xxx"]} 或 null,
  "next_actions": [{"text": "后续动作"}],
  "continue_weight_boosts": [{"unit_uuid": "...", "boost": 0.8}]
}

请以纯 JSON 返回，确保可以被直接解析。
