现在您需要进行一次定期的、深度的自我反思，以精简、总结您的内心独白（Thoughts）、兴趣与注意力雷达（Interests）、定时任务列表（Scheduled）。

这是你在此次反思之前，大脑中存储的内心独白与注意力设定。
如果你觉得它们已经过时，或者需要补充新的感悟，请在反思后进行覆写。

当前时间：
${now_time}

<#if turnsAddition?has_content>
本轮思考并不是第一轮，以下是您之前几轮的思考结果：{

${turnsAddition}

}
请牢记前几轮工具调用为您带来的补充信息；
</#if>

你的目前内心想法（Thinking），其中包含了之前的你希望自己记住的东西：{

${current_thoughts}

}

你的当前兴趣与注意力雷达 （Interests）：{

${current_interests}

}

以下是定时任务列表（Scheduled）：{

${scheduled}

}


<#if current_memories?? && (current_memories?size > 0)>
以下是你最近经历的交互记录，请仔细体会其中的情绪、群友的行为模式以及交流的氛围：{
<#list current_memories as mem>
- ${mem}
</#list>
}
<#else>
近期暂无任何交互记录，你的记忆深处一片平静。
</#if>

以上信息远远不够您完成这项任务；
在更新以上三个文件之前，请先调用get_more_current_memorys工具搜索更多近期信息；
然后，找出在近期的记忆与互动中频繁出现的关键词，调用query_deep_memory工具搜索更多深层的记忆；
最后根据以上所有信息，在保留原本的想法、兴趣、日程文件之中所有重要内容的情况下，选择性为其中条目注释新的经验，根据需要调用reflective_memory_compaction工具，覆写这三个文件；
注意，如果没有什么需要更新的条目、文件，那可以选择不调用reflective_memory_compaction工具、不复写其中的任何东西，或者仅作精简文本处理；