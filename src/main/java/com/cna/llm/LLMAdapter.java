package com.cna.llm;

import com.cna.config.ConfigsManager;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@Slf4j
public class LLMAdapter {

    private static final ObjectMapper jsonMapper = new ObjectMapper();
    private final OkHttpClient client;
    private final LLMConfig config;

    public LLMAdapter(LLMConfig config) {
        this.config = config;
        // 重新配置 HTTP 客户端，将读取超时拉长到 5 分钟，防止带有深度思维链的模型被强制断线
        this.client = new OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.MINUTES)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    /**
     * 对接嵌入模型 (Embedding)
     * 发送文本，阻塞等待，直接返回解析好的多维坐标数组
     */
    public double[] getEmbedding(String text) {
        ObjectNode payload = jsonMapper.createObjectNode();
        payload.put("model", config.getEmbeddingModel());
        payload.put("input", text);

        String url = config.getApiBase().endsWith("/") ? config.getApiBase() + "embeddings" : config.getApiBase() + "/embeddings";

        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + config.getApiKey())
                .header("Content-Type", "application/json")
                .post(RequestBody.create(payload.toString(), MediaType.get("application/json")))
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                log.error("Embedding 网络阻断，状态码: {}", response.code());
                return new double[0];
            }

            String responseBody = response.body().string();           // 只调一次
            log.debug("emb模型响应: " + responseBody);

            JsonNode rootNode = jsonMapper.readTree(responseBody);    // 复用变量

            JsonNode vectorNode = rootNode.path("data").get(0).path("embedding");

            return jsonMapper.convertValue(vectorNode, new TypeReference<double[]>() {});
        } catch (IOException e) {
            log.error("获取向量时发生底层 I/O 错误", e);
            return new double[0];
        }
    }

    /**
     * 对接文本模型 (Chat Completions) - SSE 流式接收
     */
    public String generateStreamResponse(String userMessage,
                                         String contextMemories,
                                         Consumer<String> chunkCallback) {

        ObjectNode payload = jsonMapper.createObjectNode();
        payload.put("model", config.getChatModel());
        payload.put("stream", true); // 强制开启流式输出
        payload.put("temperature", config.getTemperature());
        payload.put("max_tokens", config.getMax_tokens());
        if (config.getFrequencyPenalty() != 0.0) payload.put("frequency_penalty", config.getFrequencyPenalty());
        if (config.getPresencePenalty() != 0.0) payload.put("presence_penalty", config.getPresencePenalty());
        if (config.isEnableCoT()) {
            payload.put("enable_thinking", true);
            //finalSysPrompt += "\n\n【指令约束】在给出最终结果前，必须先进行严谨的逻辑推导。";
        }

        ArrayNode messages = payload.putArray("messages");

        // 1. 组装系统底层约束与记忆
        String finalSysPrompt = config.getSystemPrompt();
        if (contextMemories != null && !contextMemories.isEmpty()) {
            finalSysPrompt += contextMemories;
        }

        ObjectNode sysMsg = jsonMapper.createObjectNode();
        sysMsg.put("role", "system");
        sysMsg.put("content", finalSysPrompt);
        messages.add(sysMsg);

        // 2. 压入用户输入
        ObjectNode userMsg = jsonMapper.createObjectNode();
        userMsg.put("role", "user");
        userMsg.put("content", userMessage);
        messages.add(userMsg);

        String url = config.getApiBase().endsWith("/") ? config.getApiBase() + "chat/completions" : config.getApiBase() + "/chat/completions";

        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + config.getApiKey())
                .header("Content-Type", "application/json")
                .post(RequestBody.create(payload.toString(), MediaType.get("application/json")))
                .build();

        StringBuilder fullResponse = new StringBuilder();

        log.info("引擎点火，通过 OkHttp 建立 TCP 长连接...");
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                return "计算资源请求失败: " + response.code();
            }

            // 3. 物理剥离 SSE 协议的数据流
            InputStream inputStream = response.body().byteStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
            String line;

            while ((line = reader.readLine()) != null) {
                // 忽略空行
                if (line.trim().isEmpty()) {
                    continue;
                }

                // SSE 协议的结束信号
                if (line.equals("data: [DONE]")) {
                    break;
                }

                // 只处理以 "data: " 开头的物理载荷
                if (line.startsWith("data: ")) {
                    String jsonChunk = line.substring(6); // 截掉 "data: " 前缀

                    try {
                        JsonNode chunkNode = jsonMapper.readTree(jsonChunk);
                        JsonNode deltaNode = chunkNode.path("choices").get(0).path("delta");

                        // 优先抓取思维链字段 (处理 DeepSeek/GLM 等非标字段)
                        JsonNode reasoningNode = deltaNode.path("reasoning_content");
                        if (!reasoningNode.isMissingNode() && !reasoningNode.isNull()) {
                            String reasoning = reasoningNode.asText();
                            if (!reasoning.isEmpty()) {
                                chunkCallback.accept(reasoning);
                                //System.out.print(reasoning);
                            }
                        }

                        // 抓取最终的正式回复字段
                        JsonNode contentNode = deltaNode.path("content");
                        if (!contentNode.isMissingNode() && !contentNode.isNull()) {
                            String content = contentNode.asText();
                            if (!content.isEmpty()) {
                                chunkCallback.accept(content);
                                fullResponse.append(content);
                            }
                        }
                    } catch (Exception parseEx) {
                        log.warn("无法解析的数据块: {}", jsonChunk);
                    }
                }
            }
        } catch (IOException e) {
            log.error("流式网络 I/O 异常脱断", e);
            return fullResponse.append("\n[生成意外中断]").toString();
        }

        return fullResponse.toString();
    }
    /**
     * 对接文本模型 (非流式 Tool Calling 专用)
     * 传入工具定义的 JSON 数组，返回工具调用结果或普通文本
     * - 强制清洗 SSE 前缀与非法尾缀；
     * - 同时识别 tool_calls (数组) 和 function_call (旧格式)，并统一成 tool_calls 数组；
     * - 对所有 NullNode / MissingNode / 空数组 做防御，确保不会因格式差异导致工具调用信息丢失；
     * - 工具调用结果的闭合性由此方法完全保证，上层不再需要做额外清洗。
     */
    public CallResult generateResponseWithTools(String userMessage,
                                                String contextMemories,
                                                ArrayNode tools) {

        ObjectNode payload = jsonMapper.createObjectNode();
        payload.put("model", config.getChatModel());
        payload.put("stream", false);
        payload.put("temperature", config.getTemperature());
        payload.put("max_tokens", config.getMax_tokens());
        payload.put("thinking_budget", config.getMax_tokens());
        if (config.getFrequencyPenalty() != 0.0) payload.put("frequency_penalty", config.getFrequencyPenalty());
        if (config.getPresencePenalty() != 0.0) payload.put("presence_penalty", config.getPresencePenalty());
        if (config.isEnableCoT()) {
            payload.put("enable_thinking", true);
        }

        if (tools != null && !tools.isEmpty()) {
            payload.set("tools", tools);
            payload.put("tool_choice", "auto");
        }

        ArrayNode messages = payload.putArray("messages");

        String finalSysPrompt = config.getSystemPrompt();
        if (contextMemories != null && !contextMemories.isEmpty()) {
            finalSysPrompt += contextMemories;
        }

        ObjectNode sysMsg = jsonMapper.createObjectNode();
        sysMsg.put("role", "system");
        sysMsg.put("content", finalSysPrompt);
        messages.add(sysMsg);

        ObjectNode userMsg = jsonMapper.createObjectNode();
        userMsg.put("role", "user");
        userMsg.put("content", userMessage);
        messages.add(userMsg);

        String url = config.getApiBase().endsWith("/") ?
                config.getApiBase() + "chat/completions" :
                config.getApiBase() + "/chat/completions";

        String payloadStr = payload.toString();
        log.debug("请求体大小: {} bytes", payloadStr.length());

        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + config.getApiKey())
                .header("Content-Type", "application/json")
                .post(RequestBody.create(payloadStr, MediaType.get("application/json")))
                .build();

        CallResult result = new CallResult();

        // ---------- 关键：创建带强制总超时的临时客户端 ----------
        OkHttpClient timeoutClient = client.newBuilder()
                .callTimeout(90, TimeUnit.SECONDS)   // 整个请求最多等 90 秒
                .build();

        long startTime = System.currentTimeMillis();
        try (Response response = timeoutClient.newCall(request).execute()) {
            long elapsed = System.currentTimeMillis() - startTime;
            log.info("Tool Calling 收到响应，HTTP 状态码: {}, 耗时: {}ms", response.code(), elapsed);

            if (!response.isSuccessful() || response.body() == null) {
                log.error("计算资源请求失败，状态码: {}", response.code());
                result.setContent("计算资源请求失败: " + response.code());
                return result;
            }

            String responseBody = response.body().string();
            log.debug("【Tool Calling 原始响应】: {}", responseBody);

            // ---------- SSE 多段聚合：即使 stream=false，某些模型仍可能返回多段 data chunk ----------
            String cleanedBody = extractFirstValidChunk(responseBody.trim());
            if (cleanedBody == null || cleanedBody.isEmpty()) {
                log.error("无法从 SSE 响应中提取有效 JSON 数据: {}", responseBody);
                result.setContent("响应格式异常：无有效数据");
                return result;
            }

            JsonNode rootNode;
            try {
                rootNode = jsonMapper.readTree(cleanedBody);
            } catch (Exception e) {
                log.error("无法解析 JSON: {}", cleanedBody);
                result.setContent("响应格式异常");
                return result;
            }

            if (rootNode.has("error")) {
                String err = rootNode.path("error").path("message").asText("未知错误");
                log.error("API 返回错误: {}", err);
                result.setContent("API 错误: " + err);
                return result;
            }

            JsonNode choicesNode = rootNode.path("choices");
            if (choicesNode.isMissingNode() || !choicesNode.isArray() || choicesNode.isEmpty()) {
                log.error("缺少 choices 字段");
                result.setContent("响应缺少 choices");
                return result;
            }

            JsonNode choiceNode = choicesNode.get(0);
            JsonNode messageNode = choiceNode.path("message");
            if (messageNode.isMissingNode() || messageNode.isNull()) {
                JsonNode deltaNode = choiceNode.path("delta");
                if (!deltaNode.isMissingNode() && !deltaNode.isNull()) {
                    messageNode = deltaNode;
                } else {
                    log.error("无 message 和 delta");
                    result.setContent("响应结构异常");
                    return result;
                }
            }

            result.setReasoningContent(messageNode.path("reasoning_content").asText(null));

            JsonNode contentNode = messageNode.path("content");
            if (!contentNode.isMissingNode() && !contentNode.isNull()) {
                result.setContent(contentNode.asText());
            } else {
                result.setContent(null);
            }

            // --- 统一提取工具调用 ---
            ArrayNode toolCallsArray = null;

            if (messageNode.has("tool_calls") && !messageNode.get("tool_calls").isNull()) {
                JsonNode tcNode = messageNode.get("tool_calls");
                if (tcNode.isArray() && tcNode.size() > 0) {
                    toolCallsArray = jsonMapper.createArrayNode();
                    for (JsonNode call : tcNode) {
                        ObjectNode normalized = normalizeToolCall(call);
                        toolCallsArray.add(normalized);
                    }
                } else if (tcNode.isObject()) {
                    toolCallsArray = jsonMapper.createArrayNode().add(normalizeToolCall(tcNode));
                } else if (tcNode.isArray() && tcNode.size() == 0) {
                    log.warn("tool_calls 为空数组，忽略");
                }
            } else if (messageNode.has("function_call") && !messageNode.get("function_call").isNull()) {
                JsonNode fcNode = messageNode.get("function_call");
                if (fcNode.isObject()) {
                    ObjectNode converted = jsonMapper.createObjectNode();
                    converted.put("id", "call_" + System.currentTimeMillis());
                    converted.put("type", "function");
                    converted.set("function", fcNode);
                    toolCallsArray = jsonMapper.createArrayNode().add(converted);
                }
            }

            String finishReason = choiceNode.path("finish_reason").asText();
            if (toolCallsArray == null && "tool_calls".equals(finishReason)) {
                log.warn("finish_reason=tool_calls 但无 tool_calls 内容");
            }

            if (toolCallsArray != null && toolCallsArray.size() > 0) {
                result.setToolCall(true);
                result.setToolCalls(toolCallsArray);
            } else {
                result.setToolCall(false);
                result.setToolCalls(null);
            }

        } catch (java.net.SocketTimeoutException e) {
            long elapsed = System.currentTimeMillis() - startTime;
            log.error("Tool Calling 请求超时 ({}ms)，可能是 prompt 过长或模型响应太慢", elapsed);
            result.setContent("请求超时，请稍后重试或缩短上下文");
        } catch (IOException e) {
            log.error("Tool Calling 网络 I/O 异常", e);
            result.setContent("网络异常: " + e.getMessage());
        }

        return result;
    }

    private ObjectNode normalizeToolCall(JsonNode call) {
        ObjectNode normalized = call.deepCopy();
        ObjectNode func = (ObjectNode) normalized.get("function");
        if (func != null && func.has("arguments")) {
            JsonNode args = func.get("arguments");
            if (args.isObject() || args.isArray()) {
                func.put("arguments", args.toString());
            } else if (args.isTextual()) {
                // 检测并修复 DeepSeek 内层 JSON 未转义双引号的问题
                String raw = args.asText();
                String repaired = repairInnerJsonQuotes(raw);
                func.put("arguments", repaired);
            }
        }
        return normalized;
    }

    /**
     * 修复 DeepSeek 模型在 arguments 内层 JSON 中偶尔未转义双引号的问题。
     * 启发式策略：遍历字符，用简单的 JSON 上下文判断 " 是结构符还是内容符。
     * 标准解析已由上层 fail-safe 机制兜底。
     */
    public static String repairInnerJsonQuotes(String raw) {
        // 先尝试标准解析，成功则直接返回
        try {
            jsonMapper.readTree(raw);
            return raw;
        } catch (Exception ignored) {
            // 需要修复
        }

        StringBuilder sb = new StringBuilder(raw.length() + 32);
        boolean inString = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\\') {
                // 保留已有转义
                sb.append(c);
                if (i + 1 < raw.length()) {
                    sb.append(raw.charAt(++i));
                }
                continue;
            }
            if (c == '"') {
                if (!inString) {
                    inString = true;
                    sb.append(c);
                } else {
                    // 判断这个 " 是否属于 JSON 结构（后跟 : , } ] 或空白+结构符）
                    int j = i + 1;
                    while (j < raw.length() && (raw.charAt(j) == ' ' || raw.charAt(j) == '\t' || raw.charAt(j) == '\n' || raw.charAt(j) == '\r')) {
                        j++;
                    }
                    if (j < raw.length()) {
                        char next = raw.charAt(j);
                        if (next == ':' || next == ',' || next == '}' || next == ']') {
                            // 结构符号
                            inString = false;
                            sb.append(c);
                        } else {
                            // 内容中的引号，需要转义
                            sb.append("\\\"");
                        }
                    } else {
                        inString = false;
                        sb.append(c);
                    }
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 对接视觉大模型 (Vision)
     * 传入提示词和 Base64 格式的图片，返回对图片的文字描述
     */
    /**
     * 从可能包含多段 SSE data chunk 的原始响应体中，提取第一个包含有效 choices (非空数组) 的 JSON 片段。
     * 某些模型 (如 DeepSeek v4) 即使 stream=false，仍可能返回 SSE 格式的多段响应。
     *
     * @param rawResponse 原始 HTTP 响应体
     * @return 第一个有效 JSON 字符串，或 null 如果所有 chunk 都没有有效 choices
     */
    private String extractFirstValidChunk(String rawResponse) {
        if (rawResponse == null || rawResponse.isEmpty()) {
            return null;
        }

        // 如果不以 "data:" 开头，说明不是 SSE 格式，按原来的纯 JSON 处理
        if (!rawResponse.startsWith("data:")) {
            return rawResponse;
        }

        // 按行分割，收集所有 "data: {...}" 行
        String[] lines = rawResponse.split("\\r?\\n");
        for (String line : lines) {
            String trimmed = line.trim();

            // 跳过空行和结束标记
            if (trimmed.isEmpty() || trimmed.equals("data: [DONE]")) {
                continue;
            }

            if (!trimmed.startsWith("data: ")) {
                continue;
            }

            // 提取 JSON 部分
            String jsonPart = trimmed.substring(6).trim(); // 截掉 "data: "

            // 快速跳过明显不是 JSON 对象的内容
            if (!jsonPart.startsWith("{")) {
                continue;
            }

            try {
                JsonNode chunkNode = jsonMapper.readTree(jsonPart);

                // 跳过包含 error 的 chunk
                if (chunkNode.has("error")) {
                    log.warn("[SSE聚合] 跳过包含错误的 chunk: {}", jsonPart);
                    continue;
                }

                JsonNode choices = chunkNode.path("choices");
                // 跳过 choices 为空的 chunk，寻找有实际数据的 chunk
                if (choices.isMissingNode() || !choices.isArray() || choices.isEmpty()) {
                    log.debug("[SSE聚合] 跳过 choices 为空的 chunk");
                    continue;
                }

                // 找到第一个有效的 chunk
                log.debug("[SSE聚合] 找到有效 chunk，choices 数量: {}", choices.size());
                return jsonPart;

            } catch (Exception e) {
                log.debug("[SSE聚合] 跳过无法解析的 chunk: {}", jsonPart);
            }
        }

        // 所有 chunk 都没有有效 choices，返回 null
        log.warn("[SSE聚合] 所有 SSE chunk 均不包含有效的 choices 数据");
        return null;
    }

    /**
     * 对接视觉大模型 (Vision)
     * 传入提示词和 Base64 格式的图片，返回对图片的文字描述
     */
    public String generateVisionDescription(String promptText, String base64Image) {
        ObjectNode payload = jsonMapper.createObjectNode();
        // 注意：这里需要在你的 LLMConfig 中配置一个视觉模型，比如 qwen-vl-max 或 gpt-4o
        payload.put("model", config.getChatModel());
        payload.put("temperature", config.getTemperature());
        payload.put("max_tokens", config.getMax_tokens());

        ArrayNode messages = payload.putArray("messages");

        ObjectNode userMsg = jsonMapper.createObjectNode();
        userMsg.put("role", "user");

        // 视觉模型的 content 是一个数组
        ArrayNode contentArray = userMsg.putArray("content");

        // 1. 压入文本指令
        ObjectNode textNode = jsonMapper.createObjectNode();
        textNode.put("type", "text");
        textNode.put("text", promptText);
        contentArray.add(textNode);

        // 2. 压入 Base64 图片数据
        ObjectNode imageNode = jsonMapper.createObjectNode();
        imageNode.put("type", "image_url");
        ObjectNode imageUrlNode = imageNode.putObject("image_url");
        // 标准协议要求带上前缀
        imageUrlNode.put("url", "data:image/jpeg;base64," + base64Image);
        contentArray.add(imageNode);

        messages.add(userMsg);

        String url = config.getApiBase().endsWith("/") ? config.getApiBase() + "chat/completions" : config.getApiBase() + "/chat/completions";

        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + config.getApiKey())
                .header("Content-Type", "application/json")
                .post(RequestBody.create(payload.toString(), MediaType.get("application/json")))
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                log.error("视觉计算资源请求失败，状态码: {}", response.code());
                return "[图片解析失败: 服务端无响应]";
            }
            JsonNode rootNode = jsonMapper.readTree(response.body().string());
            return rootNode.path("choices").get(0).path("message").path("content").asText("[图片解析失败: 无内容]");
        } catch (Exception e) {
            log.error("视觉模型网络 I/O 异常", e);
            return "[图片解析失败: 网络异常]";
        }
    }
}