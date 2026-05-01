现在你需要对一组消息进行注意力判定，也就是判定这组消息是否值得你注意；

当前时间：
${now_time}

<#if current_interests?has_content>
这是之前您自己希望自己关注的东西 （Interests）：{

${current_interests}
}
</#if>

你需要根据以上标准，提交应该注意的消息之编号；

以下是你需要判定的最新信息，它们按编号排列：{

${currentInputs}

}

你需要列出值得你注意的消息的编号，并将其输出为JSON数组；
你必须且只能调用系统提供的 `submit_attention_list` 工具来提交你的决策结果。如果没有任何消息值得关注，你可以提交一个空的列表。