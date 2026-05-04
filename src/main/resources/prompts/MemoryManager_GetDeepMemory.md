现在，您需要根据你的已有想法，详细地总结以下记忆内容：{

${text}

}

当前时间：
${now_time}

你的目前内心想法（Thinking），其中包含了之前的你希望自己记住的东西：{

${current_thoughts}

}

<#if current_memories?? && (current_memories?size > 0)>
这是您最近与外界的交互记录，你依旧清晰地记得它们：{
<#list current_memories as mem>
- ${mem}
</#list>
} 
</#if>

除了需要直接回味总结的记忆内容外，上文的其他内容也许没有用，仅仅提示你当前的心境、状态；
注意不要输出除了被总结的目标文本以外的其他东西。