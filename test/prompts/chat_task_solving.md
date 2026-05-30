${tools_guide!""}

你现在需要针对其进行合适的回复，为了达成这一目的，你可能需要先调用其他工具获取足够信息，如获取相关人员在特定群聊、私信中的最后消息；
当你在前几轮消息中收集到足够的信息，并且认为当前消息需要更深入的思考才能总结发送时，可以在调用发送消息工具的同时一起调用另一个工具，以请求使用更高级的大模型进行思考——除非下文的日志中显示当前轮次已经使用了更高级的大模型，请直接思考、作答、发送消息；

请先行在纯文本字段中，输出你对于之前行动过程与聊天记录的新评价与新想法，越详细越好；
然后继续输出当前轮次还应该干什么、想如何针对这段聊天记录进行回复，以及在回复前是否需要调用可调用的工具，诸如查询历史消息、利用支持更深度思考的大模型思考，或是根据消息内容来修改认识、兴趣列表等；
也可以根据之前的经验，输出在之后的轮次中不应该干的事，但是很显然，负面提示词不如正面提示词有效；

请在充分总结经验教训、提炼方法论的前提下，根据当前给出的所有信息，从系统提供的工具箱中选择合适的工具推进任务；
选择利用工具发送QQ消息意味着整个任务流程结束，请将发送消息的操作放到工作流程的最后，并且在调用发送消息的工具的同时调用finish_task来及时结束这个任务——不管怎么样，执行地越快越好。

以下是被您注意到的聊天记录信息：{

${taskText}

}

<#if current_thoughts?has_content || deep_memories?? || current_feelings?has_content || recent_history?has_content || current_memories??>
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

<#if current_feelings?has_content>
这个任务使你感觉如下：{

${current_feelings}

}
你应该关注这些感觉；
</#if>

<#if recent_history?has_content>
目前聊天环境的历史消息记录：{

${recent_history}

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