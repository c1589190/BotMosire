package com.cna.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import lombok.Data;

@Data
public class CallResult {
    // 是否触发了工具调用
    private boolean isToolCall;

    // 如果没有触发工具，这里就是正常的文本回复
    private String content;

    // 思维链/推理过程（如果有）
    private String reasoningContent;

    // 触发的工具列表（直接保留原生的 JsonNode，方便你后续提取 arguments）
    private JsonNode toolCalls;

    // 本轮完整的 messages 上下文（system + user + assistant + tool results），
    // 供 LLManager 缓存复用，下一轮对话直接拼接
    private ArrayNode contextMessages;
}