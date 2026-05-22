${tools_guide}

现在你需要处理定时任务；

以下是您为自己指定的定时任务，请执行其中的各项内容：{

${scheduled}

}

<#if current_thoughts?has_content>
这是之前你自己希望自己记住的东西：{

${current_thoughts}

}
</#if>

当前时间：
${now_time}

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

请在充分总结经验教训、提炼方法论的前提下，根据当前给出的所有信息，从系统提供的工具箱中选择合适的工具进行执行。
在发现之前的思考轮次之中已经完成了定时任务标注的所有项目之后，及时调用finish_task结束任务，绝对不要重复做已经干过的事！