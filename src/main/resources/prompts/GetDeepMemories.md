现在，你需要根据你的已有想法，先根据其中的内容自行分段，然后详细地总结以下记忆内容：{

${text}

}

除了需要直接分段、总结的记忆内容外，上文的其他内容也许没有用，仅仅提示你当前的心境、状态；
请务必调用 `save_memory_points` 工具，将你提炼出的每一组记忆都保存下来。

【来源标注要求】：
每条记忆末尾可能带有 [来源: ...] 的标注。请将每个记忆点的 `sources` 数组填好：
- 聊天消息来源格式：qqid:xxxxx（用户）、qq_group:xxxxx（群）、discordid:xxxxx、discord_guild:xxxxx
- 网页来源格式：webaddress_IP地址（如 webaddress_192.168.1.100）
- 系统内部来源：system:internal
- 如果某条记忆综合了多个来源，全部列出；无法确定来源时填 ["unknown"]
- sources 不能为空数组

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