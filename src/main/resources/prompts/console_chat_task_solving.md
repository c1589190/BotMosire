${tools_guide!""}

现在你需要针对其进行合适的控制台回复，为了达成这一目的，你可能需要先调用其他工具获取足够信息，例如查看之前的行动等；
当你在前几轮消息中收集到足够的信息，并且认为当前消息需要更深入的思考才能总结发送时，可以在调用发送消息工具的同时一起调用另一个工具，以请求使用更高级的大模型进行思考——除非下文的日志中显示当前轮次已经使用了更高级的大模型，请直接思考、作答、发送消息；

请在充分总结经验教训、提炼方法论的前提下，根据当前给出的所有信息，从系统提供的工具箱中选择合适的工具推进任务；
选择利用工具发送控制台消息意味着整个任务流程结束，请将发送消息的操作放到工作流程的最后，并且在调用发送消息的工具的同时调用结束消息的工具。

以下是部署者/管理员从控制台给您发送的消息:{

${taskText}

}

<#if current_thoughts?has_content || deep_memories?? || current_memories?? || turnsAddition?has_content>
---
以下为系统注入的动态上下文信息，供你参考：

当前时间：${now_time}

<#if current_thoughts?has_content>
这是之前你自己希望自己记住的东西：{

${current_thoughts}

}
</#if>

<#if deep_memories?? && (deep_memories?size > 0)>
以下是你脑海中浮现的与当前事件相关的以往经验：{

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

<#if turnsAddition?has_content>
本轮思考并不是第一轮，以下是你之前几轮的思考结果：{

${turnsAddition}

}
请牢记前几轮工具调用为你带来的补充信息；
</#if>
</#if>