<#-- ============================================================================
 V4 认知动作循环 — 用户提示词模板
 变量来源: ActionLoop.buildActionPrompt() 构建的 dataModel
 渲染引擎: FreeMarker (由 LLManager.render() 执行)

 此模板会被注入当前认知动作的上下文数据，然后发送给 LLM。
 系统指令部分请参见 prompts/V4_ACTION_SYSTEM_PROMPT.md。
 ============================================================================ -->

══════════════════════════════════════════════════════════════
📋 当前认知动作上下文
══════════════════════════════════════════════════════════════

🕐 时间：${now_time}

<#-- ── 消息来源 ── -->
<#if source_ids?size gt 0>
📡 来源：<#list source_ids as s>${s}<#sep>, </#sep></#list>
</#if>

## 动作文本（ActionText）—— 这是你要处理的核心内容

${action_text}

<#-- ── 认知状态仪表盘 ── -->

──────────────────────────────────────────────────────────────
📊 认知状态仪表盘
──────────────────────────────────────────────────────────────

| 维度 | 数值 | 解读 |
|------|------|------|
| 认知熟悉度 (CF) | ${cognitive_familiarity?string("0.000")} | <#if cognitive_familiarity gt 0.6>🔵 很熟悉 — 可以信赖先验经验<#elseif cognitive_familiarity gt 0.3>🟡 有些熟悉 — 结合经验和当前具体情况判断<#elseif cognitive_familiarity gt 0.1>🟠 不太熟悉 — 谨慎分析，不要盲信先验经验<#else>🔴 几乎全新 — 以开放心态探索</#if> |
| 规模 (Scale) | ${scale} | <#if scale gt 5>📦 复杂问题 — 多角度思考，可能需要多轮处理<#elseif scale gt 2>📦 中等复杂度 — 正常处理<#else>📦 简单 — 快速决策</#if> |
| 意外度 (Accident) | ${accident_degree?string("0.000")} | <#if accident_degree gt 0.2>⚠️ 有意料之外的信息 — 重新评估<#elseif accident_degree gt -0.2>➡️ 符合预期<#else>✅ 高度符合预期 — 可参考历史处理方式</#if> |
| 行动压力 (Pressure) | ${action_pressure?string("0.000")} | <#if action_pressure gt 0.7>🔴 紧迫 — 快速有效行动<#elseif action_pressure gt 0.3>🟡 正常节奏<#else>🟢 从容 — 可深度分析</#if> |
| 持续权重 (CW) | ${continue_weight?string("0.000")} | <#if continue_weight gt 0.7>🟢 生命力充足<#elseif continue_weight gt 0.3>🟡 中等 — 如果该单元重要请考虑 boost<#else>🔴 即将淘汰 — 若仍有价值请尽快 boost</#if> |

<#-- ── 关联感觉维度 ── -->

──────────────────────────────────────────────────────────────
💡 关联感觉维度（系统从感觉超图中检索到的相关概念）
──────────────────────────────────────────────────────────────

<#if ue_concepts?size gt 0>
  <#list ue_concepts as c>
  • ${c}
  </#list>
  <#if ue_dim_ids?size gt 0>
  对应数据库ID: [${ue_dim_ids?join(", ")}]
  </#if>
<#else>
  ⚠️ 没有关联的感觉维度。这是一个全新领域的动作，你可以创建新的感觉维度。
</#if>

<#-- ── 先验经验 ── -->

──────────────────────────────────────────────────────────────
📚 先验经验（从经验库按感觉维度检索的历史经验）
──────────────────────────────────────────────────────────────

<#if action_predicts_text?has_content>
${action_predicts_text}

  ⚠️ 请对这些经验逐一打分（1=有帮助, 0=中性, -1=没帮助）。你的打分会影响未来经验检索的准确度。
<#else>
  📭 没有检索到相关的先验经验。这可能是新领域的问题。
</#if>

<#-- ── 认知准备池概况 ── -->

${pool_summary}

══════════════════════════════════════════════════════════════
📝 请返回以下 JSON 结构（直接输出纯 JSON，不包裹代码块）
══════════════════════════════════════════════════════════════

{
  "thoughts": "你的推理过程：分析当前情境 → 评估先验经验的价值 → 解释你的行动决策",
  "tool_calls": [
    {
      "id": "call_N",
      "type": "function",
      "function": {
        "name": "工具名称",
        "arguments": "{\"参数名\": \"参数值\"}"
      }
    }
  ],
  "stimulated_feelings": [
    {
      "concept": "简短的语义标签（5-15字）",
      "embedding_text": "更详细的描述文本，包含上下文细节，用于生成向量嵌入"
    }
  ],
  "new_prepare_unit": {
    "text": "需要后续处理的事项描述",
    "sourceIds": ["来源ID"]
  },
  "experience_scoring": [
    {
      "experience_id": 经验ID（整数）,
      "score": 1
    }
  ],
  "continue_weight_boosts": [
    {
      "unit_uuid": "准备池中单元的UUID",
      "boost": 0.6
    }
  ]
}

提醒：
  - tool_calls 不需要时传空数组 []，不要省略该字段
  - new_prepare_unit 不需要时传 null，不要省略该字段
  - experience_scoring 中的 experience_id 必须与上面先验经验中的 ID 一致
  - continue_weight_boosts 中 boost 推荐范围 0.3~1.0，不确定时传空数组 []
  - 只输出 JSON，不要有任何前言、后语或解释
