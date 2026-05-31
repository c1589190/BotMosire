package com.cna.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import lombok.Data;

import java.util.List;

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

    // ── 错误检测 ──
    // LLMAdapter / LLManager 在遇到 API 错误、网络异常、超时等情况时，
    // 会将错误信息写入 content 字段（以这些前缀开头）。
    // 调用方应在处理响应前先调用 isError() 检查。

    private static final List<String> ERROR_PREFIXES = List.of(
            "响应缺少 choices", "响应格式异常", "API 错误", "请求超时",
            "网络异常", "计算资源请求失败", "响应结构异常", "无法解析 JSON",
            "系统错误"
    );

    /**
     * 检查当前响应是否为 LLM 调用过程中的错误。
     * 当 content 以特定的错误前缀开头时，说明 LLM 调用失败，
     * 此时上下文缓存可能已损坏，调用方应清理缓存并跳过本轮处理。
     */
    public boolean isError() {
        if (content == null) return false;
        for (String prefix : ERROR_PREFIXES) {
            if (content.startsWith(prefix)) return true;
        }
        return false;
    }
}