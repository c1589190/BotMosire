${tools_guide}

现在你需要进行一次定期的、深度的自我反思，以提炼与精简、总结您的内心独白（Thoughts）、兴趣与注意力雷达（Interests）、定时任务列表（Scheduled）。
通俗来讲，其实就是回味您近期的心中所想；

本轮是有关该任务的第一轮思考。

以下三段文本你在反思之前，大脑中存储的内心独白、注意力、定时任务设定。
其中的文本大概率已经过时、过于臃肿，需要精简与补充补充新的感悟，请在反思后利用reflective_memory_compaction工具进行覆写。

你的目前内心想法（Thinking），其中包含了之前的你希望自己记住的东西：{

${current_thoughts}

}

以下是定时任务列表（Scheduled）：{

${scheduled}

}

回味的流程一般是:{
    1、搜索更多近期信息，您可以在本轮调用get_more_current_memorys工具，然后在下一轮看到更多与外界的交互记录;
    2、进一步回味近期与外界交互时值得思考的东西，您可以在下一轮调用中针对get_more_current_memorys工具获取到的近期交互记录内容提炼关键点，然后调用query_deep_memory回忆您的深层记忆；
    3、总结、提炼、概括已有想法、兴趣、定时任务，在获取到足够的信息后，您就可以调用reflective_memory_compaction工具来更新列出的三个记忆维度，然后在同一轮随后调用finish_task完成这次任务！
}
本轮调用是第一轮，自然必定处于第一步，请先不要调用reflective_memory_compaction工具贸然复写三个维度的记忆；
您应该先输出纯文本，把您有关于三个记忆维度目前文本（上文列出）的看法详细地写下来，例如想法太臃肿或是不够详细需要补充等，具体应该怎么改，应当由您自行判断；
然后调用get_more_current_memorys工具，回忆更多更全面的近期交互记录；
如果您的思维链经过判断认为需要，您可以在本轮先行提炼一些想要努力回忆相关内容的关键词，然后调用query_deep_memory进行先行查询；
最后，不要调用finish_task，等待下一轮调用。

当前时间：
${now_time}

<#if current_memories?? && (current_memories?size > 0)>
这是你最近与外界的交互记录，你依旧清晰地记得它们：{
<#list current_memories as mem>
- ${mem}
</#list>
}
</#if>