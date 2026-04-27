以下是被您注意到的聊天记录信息：

${taskText}

您的最终任务是针对其进行合适的回复，为了达成这一目的，您可能需要先调用其他工具获取足够信息，如获取相关人员在特定群聊、私信中的最后消息；

当前时间：
${now_time}

<#if current_thoughts?has_content>
这是之前您自己希望自己记住的东西：{

${current_thoughts}
}
</#if>

<#if turnsAddition?has_content>
本轮思考并不是第一轮，以下是您之前几轮的思考结果：{

${turnsAddition}
}
请牢记前几轮工具调用为您带来的补充信息；
</#if>

<#if current_memories?? && (current_memories?size > 0)>
这是您最近与外界的交互记录，你依旧清晰地记得它们：{
<#list current_memories as mem>
- ${mem}
</#list>
}
</#if>

<#if deep_memories?? && (deep_memories?size > 0)>
以下是你脑海中浮现的与当前事件相关的以往经验：{

<#list deep_memories as d_mem>
- ${d_mem}
</#list>
}
</#if>

请在充分总结经验教训、提炼方法论的前提下，根据当前给出的所有信息，从系统提供的工具箱中选择合适的工具推进任务；
选择利用工具发送QQ消息意味着整个任务流程结束，请将发送消息的操作放到工作流程的最后。