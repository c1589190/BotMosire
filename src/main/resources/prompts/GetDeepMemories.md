现在，您需要根据你的已有想法，先根据其中的内容自行分段，然后详细地总结以下记忆内容：{

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

除了需要直接分段、总结的记忆内容外，上文的其他内容也许没有用，仅仅提示你当前的心境、状态；
请务必调用 `save_memory_points` 工具，将你提炼出的每一组记忆都保存下来。