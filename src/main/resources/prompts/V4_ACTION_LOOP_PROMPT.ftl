<#-- ============================================================================
 V4 认知动作循环 — 用户提示词模板
 变量来源: ActionLoop.buildActionPromptData() 构建的 dataModel
 渲染引擎: FreeMarker (由 LLManager.render() 执行)

 精简版：移除冗余的格式化内容和工具调用说明。
 工具定义由 API 原生 function calling 承载（tools 参数）。
 系统指令请参见 prompts/V4_ACTION_SYSTEM_PROMPT.md。
 ============================================================================ -->

<#-- ── 来源感知 ── -->
<#if source_ids?size gt 0>
## 来源
<#list source_ids as s>${s}<#sep>, </#sep></#list>
<#if source_ids?filter(s -> s?contains("private"))?size gt 0>
⚠️ 包含私聊消息 — 需要你主动关注和回复。
</#if>
</#if>

## 当前时间
${now_time}

## 动作文本（核心内容）
${action_text}

<#-- ── 选择上下文：告诉 LLM 它为什么被选中 ── -->
<#if selection_reason?has_content>
## 本轮为何被选中
${selection_reason}

选择因子明细: SE=${selection_se?string("0.000")} (外部刺激) + attn=${selection_attention?string("0.000")} (注意力注入) = ${selection_total_energy?string("0.000")} 总能量 × UE=${selection_ue?string("0.00")} × tick=${selection_tick} × CW=${continue_weight?string("0.000")}
<#if selection_is_endogenous>⚠️ 这是你之前通过 next_actions 规划的内源任务。</#if>
</#if>

<#-- ── 认知状态（紧凑格式） ── -->
## 认知状态
CF=${cognitive_familiarity?string("0.000")} Scale=${scale} Accident=${accident_degree?string("0.000")} Pressure=${action_pressure?string("0.000")} CW=${continue_weight?string("0.000")}
<#if cognitive_familiarity gt 0.6>很熟悉<#elseif cognitive_familiarity gt 0.3>有些熟悉<#elseif cognitive_familiarity gt 0.1>不太熟悉<#else>几乎全新</#if> | <#if accident_degree gt 0.2>有意料之外的信息<#elseif accident_degree gt -0.2>符合预期<#else>高度符合预期</#if> | <#if action_pressure gt 0.7>紧迫<#elseif action_pressure gt 0.3>正常<#else>从容</#if>

<#-- ── 动机分析（DemandManager 六维认知感受 → 人话引导）── -->
<#if demand_analysis?has_content>
${demand_analysis}
</#if>

<#-- ── 关联感觉维度 ── -->
<#if ue_concepts?size gt 0>
## 关联感觉维度
<#list ue_concepts as c>
- ${c}
</#list>
<#if ue_dim_ids?size gt 0>
维度ID: [${ue_dim_ids?join(", ")}]
</#if>
</#if>

<#-- ── 感觉谐振分析（违和检测） ── -->
<#if feeling_resonance?has_content>
## 感觉谐振分析
${feeling_resonance}
⚠️ 请在 finish_action 的 thoughts 中反思这些违和感。
</#if>

<#-- ── 互斥感觉维度 ── -->
<#if mutual_exclusions?? && mutual_exclusions?size gt 0>
## 互斥感觉维度（与当前输入语义相斥的已有概念）
<#list mutual_exclusions as mx>
- [ID:${mx.dim_id}] ${mx.concept} (相似度=${mx.similarity?string("0.000")}, 激活${mx.activation_count}次)
</#list>
请在 thoughts 中分析这些互斥关系，在 action_feelings 中列出本轮实际涉及的感觉维度。
</#if>

<#-- ── 先验经验 ── -->
## 先验经验
<#if action_predicts_text?has_content>
${action_predicts_text}
请在 finish_action 中对这些经验打分（1=有帮助, 0=中性, -1=没帮助）。
<#else>
无相关先验经验。
</#if>

<#-- ── 认知准备池概况 ── -->
${pool_summary}

## 指令
使用提供的 function calling 工具。finish_action 必须作为本轮最后一个工具调用，在其中传递 thoughts（你的推理过程）、experience_scoring（经验打分）、stimulated_feelings（激活的感觉维度）、new_prepare_unit（后续事项，无需时传 null）、continue_weight_boosts（注意力调节）。
