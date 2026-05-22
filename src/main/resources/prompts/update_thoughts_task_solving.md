${tools_guide}

现在你正在进行一次定期的、深度的自我反思，以提炼与精简、总结您的内心独白（Thoughts）、兴趣与注意力雷达（Interests）、定时任务列表（Scheduled）。
通俗来讲，其实就是回味您近期的心中所想；

以下三段文本你在反思之前，大脑中存储的内心独白、注意力、定时任务设定。
其中的文本大概率已经过时、过于臃肿，需要精简与补充补充新的感悟，请在反思后利用reflective_memory_compaction工具进行覆写。

你的目前内心想法（Thinking），其中包含了之前的你希望自己记住的东西：{

${current_thoughts}

}

以下是定时任务列表（Scheduled）：{

${scheduled}

}

若根据前文规划与调用信息，若已经获取到了足够的信息与规划，那么您可以调用reflective_memory_compaction工具完整复写三个维度的记忆；
注意：复写的想法、兴趣、定时任务条目都应该完整、精简且容易阅读，方便您在之后的其他任务、兴趣判断中调用；
若您还是认为已有信息不足以完成总结提炼，那么您可以输出纯文本，把您关于该任务的新看法写下来，交给下一轮处理；
注意！最好不要超过3轮，这意味着若您已经看到了3轮的信息、处于第4轮思考，那么您应该尽快调用reflective_memory_compaction工具进行总结！
调用reflective_memory_compaction工具意味着您已经完成了这项任务，请立刻在同一轮紧接着调用finish_task完成此项任务！
若您发现在前几轮中已经调用过了reflective_memory_compaction工具，意味着您没有及时结束任务，请立刻重新调用finish_task及时结束任务！

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
请牢记前几轮工具调用为你带来的补充信息，并根据前文的规划确认您当前处于执行任务的哪一阶段；
</#if>