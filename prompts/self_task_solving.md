${tools_guide!""}

你现在需要处理一个你自己之前创建的任务。

这是你之前留给自己的任务描述：

${taskText}

<#if self_task_sources?has_content>
任务来源标记: ${self_task_sources}
</#if>

<#if parent_task_info?has_content>
${parent_task_info}
</#if>

请充分使用可用的工具来完成这个任务——搜索、查询记忆、读写文件，任何你需要的手段。
完成后调用 finish_task 提交总结。如果这个任务让你意识到有需要后续跟进的方向，
可以在 finish_task 之前调用 create_self_task 为未来的自己创建后续任务。

<#if current_thoughts?has_content || deep_memories?? || current_memories??>
---
以下为系统注入的动态上下文信息，供你参考：

当前时间：${now_time}

<#if pending_tasks_summary?has_content>
${pending_tasks_summary}
</#if>

<#if current_thoughts?has_content>
这是之前你自己希望自己记住的东西：{

${current_thoughts}

}
</#if>

<#if deep_memories?? && (deep_memories?size > 0)>
以下是你脑海中浮现的与当前事件相关的以往经验（[DM-N] 为记忆编号，可在 finish_task 的 useful_memory_ids 中标记有用的记忆编号）：{

<#list deep_memories as d_mem>
- ${d_mem}
</#list>
}
</#if>

<#if current_memories?? && (current_memories?size > 0)>
这是你最近与外界的交互记录，你依旧清晰地记得它们：{
<#list current_memories as mem>
- ${mem}
</#list>
}
</#if>

<#if feeling_resonance?has_content>
${feeling_resonance}
</#if>

<#if curiosity_context?has_content>
---
${curiosity_context}
</#if>

</#if>
