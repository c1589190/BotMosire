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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@Slf4j
public class LLMAdapter {

    private static final ObjectMapper jsonMapper = new ObjectMapper();
    private final OkHttpClient client;
    private final LLMConfig config;

    public LLMAdapter(LLMConfig config) {
        this.config = config;
        // 从 config 读 per-model timeout，避免 Gatekeeper 慢响应卡住整个线程池
        int connectSec = config != null ? config.getConnectTimeoutSec() : 30;
        int readSec    = config != null ? config.getReadTimeoutSec()    : 300;
        int writeSec   = config != null ? config.getWriteTimeoutSec()   : 30;
        this.client = new OkHttpClient.Builder()
                .callTimeout(10, TimeUnit.MINUTES)
                .connectionPool(new ConnectionPool(5, 10, TimeUnit.SECONDS))
                .retryOnConnectionFailure(true)
                .connectTimeout(connectSec, TimeUnit.SECONDS)
                .readTimeout(readSec,       TimeUnit.SECONDS)
                .writeTimeout(writeSec,     TimeUnit.SECONDS)
                .build();
    }

    /**
     * 带 429 exponential backoff 的 HTTP 调用。最多重试 maxRetries 次（总共 maxRetries+1 次尝试）。
     * 注意：caller 应该用 try-with-resources 包裹返回的 Response。
     * 429 触顶（仍然 429）后会回传最后一个 Response，由 caller 处理。
     */
    private Response executeWithRetry(Request request, int maxRetries) throws IOException {
        long backoff = 1000;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            Response response = client.newCall(request).execute();
            if (response.code() != 429 || attempt == maxRetries) {
                return response;
            }
            // 429 且还有重试次数 → 关闭当前 response 再 sleep
            response.close();
            log.warn("[LLMAdapter] HTTP 429 rate limit, attempt {}/{}, backoff {}ms",
                    attempt + 1, maxRetries + 1, backoff);
            try {
                Thread.sleep(backoff);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("429 retry interrupted", e);
            }
            backoff *= 2;
        }
        // 理论上不会到这里
        return client.newCall(request).execute();
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
                .header("Connection", "close")
                .post(RequestBody.create(payload.toString(), MediaType.get("application/json")))
                .build();

        try (Response response = executeWithRetry(request, 2)) {
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
            ObjectNode thinking = jsonMapper.createObjectNode();
            thinking.put("type", "enabled");
            payload.set("thinking", thinking);
            payload.put("reasoning_effort", config.getReasoningEffort());
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
        int maxRetries = 2;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            if (attempt > 0) {
                log.warn("SSE 流式请求第 {} 次重试 (总共最多 {} 次重试)...", attempt, maxRetries);
                // 清理连接池中的陈旧连接，强制使用全新 TCP 连接
                client.connectionPool().evictAll();
                // 短暂等待，避免立即重试触发限流
                try { Thread.sleep(1000L * attempt); } catch (InterruptedException ignored) {}
            }

            log.info("引擎点火，通过 OkHttp 建立 TCP 长连接... (attempt={})", attempt + 1);
            try (Response response = executeWithRetry(request, 2)) {
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
                // 正常完成，跳出重试循环
                return fullResponse.toString();

            } catch (java.net.SocketException e) {
                log.error("SSE 流式连接被重置，attempt={}/{}", attempt + 1, maxRetries + 1, e);
                if (attempt >= maxRetries) {
                    return fullResponse.append("\n[连接重置，已重试" + maxRetries + "次]").toString();
                }
                // 未达重试上限则继续循环
            } catch (IOException e) {
                log.error("流式网络 I/O 异常脱断", e);
                return fullResponse.append("\n[生成意外中断]").toString();
            }
        }

        return fullResponse.append("\n[所有重试均失败]").toString();
    }
    /**
     * 对接文本模型 (非流式 Tool Calling 专用) - 原始签名，内部委托给完整 messages 版本
     */
    /*
    public CallResult generateResponseWithTools(ArrayNode userMessage,
                                                ArrayNode tools) {
        // 构建初始 messages: [system, user]
        ObjectMapper mapper = new ObjectMapper();
        ArrayNode messages = mapper.createArrayNode();

        String finalSysPrompt = config.getSystemPrompt();
        if (contextMemories != null && !contextMemories.isEmpty()) {
            finalSysPrompt += contextMemories;
        }

        ObjectNode sysMsg = mapper.createObjectNode();
        sysMsg.put("role", "system");
        sysMsg.put("content", finalSysPrompt);
        messages.add(sysMsg);

        ObjectNode userMsg = mapper.createObjectNode();
        userMsg.put("role", "user");
        userMsg.put("content", userMessage);
        messages.add(userMsg);

        return generateResponseWithTools(messages, tools);
    }

     */

    /**
     * 对接文本模型 (Tool Calling 专用) - 完整 messages 版本（无回调，兼容旧调用）
     */
    public CallResult generateResponseWithTools(ArrayNode messages,
                                                ArrayNode tools) {
        return generateResponseWithTools(messages, tools, null);
    }

    /**
     * 对接文本模型 (Tool Calling 专用) - 完整 messages 版本，支持流式回调。
     * 当 config.stream=true 时使用 SSE 流式请求，实时回调 chunkCallback；
     * 当 config.stream=false 时使用非流式请求。
     * 接收预先构建好的 messages 数组（可包含多轮历史），直接发送给 API。
     * 返回的 CallResult.contextMessages 包含本轮完整上下文，供 LLManager 缓存复用。
     * - 强制清洗 SSE 前缀与非法尾缀；
     * - 同时识别 tool_calls (数组) 和 function_call (旧格式)，并统一成 tool_calls 数组；
     * - 对所有 NullNode / MissingNode / 空数组 做防御，确保不会因格式差异导致工具调用信息丢失；
     * - 工具调用结果的闭合性由此方法完全保证，上层不再需要做额外清洗。
     */
    public CallResult generateResponseWithTools(ArrayNode messages,
                                                ArrayNode tools,
                                                Consumer<String> chunkCallback) {
        if (config.isStream()) {
            return generateResponseWithToolsStreaming(messages, tools, chunkCallback);
        }

        // 深拷贝 messages，避免污染调用方缓存的原始引用
        ArrayNode finalMessages = messages.deepCopy();

        ObjectNode payload = jsonMapper.createObjectNode();
        payload.put("model", config.getChatModel());
        payload.put("stream", false);
        payload.put("temperature", config.getTemperature());
        payload.put("max_tokens", config.getMax_tokens());
        if (config.getFrequencyPenalty() != 0.0) payload.put("frequency_penalty", config.getFrequencyPenalty());
        if (config.getPresencePenalty() != 0.0) payload.put("presence_penalty", config.getPresencePenalty());
        if (config.isEnableCoT()) {
            ObjectNode thinking = jsonMapper.createObjectNode();
            thinking.put("type", "enabled");
            payload.set("thinking", thinking);
            payload.put("reasoning_effort", config.getReasoningEffort());
        }

        if (tools != null && !tools.isEmpty()) {
            payload.set("tools", tools);
            payload.put("tool_choice", "auto");
        }

        // 直接使用传入的完整 messages 数组（已包含多轮历史）
        payload.set("messages", finalMessages);

        String url = config.getApiBase().endsWith("/") ?
                config.getApiBase() + "chat/completions" :
                config.getApiBase() + "/chat/completions";

        String payloadStr = payload.toString();
        log.info("Tool Calling 请求体大小: {} bytes ({} KB)", payloadStr.length(), String.format("%.1f", payloadStr.length() / 1024.0));
        log.debug("请求体: {} ", payloadStr);

        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + config.getApiKey())
                .header("Content-Type", "application/json")
                .header("Connection", "close")
                .post(RequestBody.create(payloadStr, MediaType.get("application/json")))
                .build();

        CallResult result = new CallResult();

        // 直接用 base client，timeout 由 LLMConfig.readTimeoutSec 统一控制（避免 callTimeout 90s 覆盖 brain 的 180s readTimeout）
        long startTime = System.currentTimeMillis();
        int maxRetries = 2;
        boolean success = false;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            if (attempt > 0) {
                log.warn("Tool Calling 第 {} 次重试 (总共最多 {} 次重试)...", attempt, maxRetries);
                // 清理连接池中的陈旧连接，强制使用全新 TCP 连接
                client.connectionPool().evictAll();
                // 短暂等待，避免立即重试触发限流
                try { Thread.sleep(1000L * attempt); } catch (InterruptedException ignored) {}
            }

            startTime = System.currentTimeMillis();
            try (Response response = executeWithRetry(request, 2)) {
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
                    log.error("无法从 SSE 响应中提取有效 JSON 数据。该 API 可能仅支持流式 (stream=true)，当前使用非流式模式。" +
                            " 响应前 500 字符: {}", responseBody.length() > 500 ? responseBody.substring(0, 500) : responseBody);
                    result.setContent("响应格式异常：该模型 API 可能不支持非流式调用，请将 llm.brain.stream 设为 true");
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

                // 构建本轮 assistant 回复消息，追加到 messages 后写入 contextMessages
                ObjectNode assistantMsg = jsonMapper.createObjectNode();
                assistantMsg.put("role", "assistant");
                if (result.getContent() != null) {
                    assistantMsg.put("content", result.getContent());
                }
                if (result.getReasoningContent() != null) {
                    assistantMsg.put("reasoning_content", result.getReasoningContent());
                }
                if (toolCallsArray != null && toolCallsArray.size() > 0) {
                    assistantMsg.set("tool_calls", toolCallsArray);
                }
                finalMessages.add(assistantMsg);
                result.setContextMessages(finalMessages);
                success = true;
                break; // 成功，跳出重试循环

            } catch (java.net.SocketTimeoutException e) {
                long elapsed = System.currentTimeMillis() - startTime;
                log.error("Tool Calling 请求超时 ({}ms)，可能是 prompt 过长或模型响应太慢", elapsed);
                result.setContent("请求超时，请稍后重试或缩短上下文");
                // 超时不需要重试（只会继续超时）
                break;
            } catch (java.net.SocketException e) {
                long elapsed = System.currentTimeMillis() - startTime;
                log.error("Tool Calling 连接被重置 ({}ms)，attempt={}/{}", elapsed, attempt + 1, maxRetries + 1, e);
                if (attempt >= maxRetries) {
                    result.setContent("网络异常 (连接重置，已重试" + maxRetries + "次): " + e.getMessage());
                }
                // 未达重试上限则继续循环
            } catch (IOException e) {
                long elapsed = System.currentTimeMillis() - startTime;
                log.error("Tool Calling 网络 I/O 异常 ({}ms)", elapsed, e);
                result.setContent("网络异常: " + e.getMessage());
                break;
            }
        }

        if (!success && result.getContent() == null) {
            result.setContent("网络异常: 所有重试均失败");
        }

        return result;
    }

    /**
     * SSE 流式 Tool Calling —— 实时回调 chunkCallback，同时正确累积 tool_calls 增量。
     */
    private CallResult generateResponseWithToolsStreaming(ArrayNode messages, ArrayNode tools,
                                                          Consumer<String> chunkCallback) {
        ArrayNode finalMessages = messages.deepCopy();

        ObjectNode payload = jsonMapper.createObjectNode();
        payload.put("model", config.getChatModel());
        payload.put("stream", true);
        payload.put("temperature", config.getTemperature());
        payload.put("max_tokens", config.getMax_tokens());
        if (config.getFrequencyPenalty() != 0.0) payload.put("frequency_penalty", config.getFrequencyPenalty());
        if (config.getPresencePenalty() != 0.0) payload.put("presence_penalty", config.getPresencePenalty());
        if (config.isEnableCoT()) {
            ObjectNode thinking = jsonMapper.createObjectNode();
            thinking.put("type", "enabled");
            payload.set("thinking", thinking);
            payload.put("reasoning_effort", config.getReasoningEffort());
        }

        if (tools != null && !tools.isEmpty()) {
            payload.set("tools", tools);
            payload.put("tool_choice", "auto");
        }

        payload.set("messages", finalMessages);

        String url = config.getApiBase().endsWith("/") ?
                config.getApiBase() + "chat/completions" :
                config.getApiBase() + "/chat/completions";

        String payloadStr = payload.toString();
        log.info("Tool Calling 流式请求体大小: {} bytes ({} KB)", payloadStr.length(), String.format("%.1f", payloadStr.length() / 1024.0));
        log.debug("请求体: {} ", payloadStr);

        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + config.getApiKey())
                .header("Content-Type", "application/json")
                .post(RequestBody.create(payloadStr, MediaType.get("application/json")))
                .build();

        CallResult result = new CallResult();

        int maxRetries = 2;
        boolean success = false;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            if (attempt > 0) {
                log.warn("Tool Calling 流式第 {} 次重试...", attempt);
                client.connectionPool().evictAll();
                try { Thread.sleep(1000L * attempt); } catch (InterruptedException ignored) {}
            }

            long startTime = System.currentTimeMillis();
            try (Response response = executeWithRetry(request, 2)) {
                long elapsed = System.currentTimeMillis() - startTime;
                log.info("Tool Calling 流式收到响应，HTTP 状态码: {}, 耗时: {}ms", response.code(), elapsed);

                if (!response.isSuccessful() || response.body() == null) {
                    log.error("流式请求失败，状态码: {}", response.code());
                    result.setContent("计算资源请求失败: " + response.code());
                    return result;
                }

                InputStream inputStream = response.body().byteStream();
                BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));

                StringBuilder fullContent = new StringBuilder();
                StringBuilder fullReasoning = new StringBuilder();
                Map<Integer, String> tcIds = new LinkedHashMap<>();
                Map<Integer, String> tcNames = new LinkedHashMap<>();
                Map<Integer, StringBuilder> tcArgs = new LinkedHashMap<>();
                String finishReason = null;

                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.trim().isEmpty()) continue;
                    if (line.equals("data: [DONE]")) break;
                    if (!line.startsWith("data: ")) continue;

                    String jsonChunk = line.substring(6);
                    try {
                        JsonNode chunkNode = jsonMapper.readTree(jsonChunk);
                        JsonNode choicesNode = chunkNode.path("choices");
                        if (choicesNode.isMissingNode() || !choicesNode.isArray() || choicesNode.isEmpty()) continue;

                        JsonNode choiceNode = choicesNode.get(0);
                        JsonNode deltaNode = choiceNode.path("delta");

                        String fr = choiceNode.path("finish_reason").asText();
                        if (!fr.isEmpty()) finishReason = fr;

                        JsonNode reasoningNode = deltaNode.path("reasoning_content");
                        if (!reasoningNode.isMissingNode() && !reasoningNode.isNull()) {
                            String r = reasoningNode.asText();
                            if (!r.isEmpty()) {
                                fullReasoning.append(r);
                                if (chunkCallback != null) chunkCallback.accept(r);
                            }
                        }

                        JsonNode contentNode = deltaNode.path("content");
                        if (!contentNode.isMissingNode() && !contentNode.isNull()) {
                            String c = contentNode.asText();
                            if (!c.isEmpty()) {
                                fullContent.append(c);
                                if (chunkCallback != null) chunkCallback.accept(c);
                            }
                        }

                        JsonNode tcNode = deltaNode.path("tool_calls");
                        if (!tcNode.isMissingNode() && tcNode.isArray()) {
                            // 某些 API 的后继 chunk 可能不带 index，用 lastIdx 兜底
                            int lastIdx = tcIds.isEmpty() ? 0 : tcIds.keySet().stream().max(Integer::compareTo).orElse(0);
                            for (JsonNode tc : tcNode) {
                                int idx = tc.path("index").asInt(-1);
                                if (idx < 0) {
                                    if (tc.has("id") || tc.has("function")) {
                                        idx = lastIdx;
                                    } else {
                                        continue;
                                    }
                                }
                                lastIdx = idx;

                                String id = tc.path("id").asText(null);
                                if (id != null && !id.isEmpty()) tcIds.put(idx, id);

                                JsonNode funcNode = tc.path("function");
                                String fname = funcNode.path("name").asText(null);
                                if (fname != null && !fname.isEmpty()) tcNames.put(idx, fname);

                                String args = funcNode.path("arguments").asText(null);
                                if (args != null && !args.isEmpty()) {
                                    tcArgs.computeIfAbsent(idx, k -> new StringBuilder()).append(args);
                                }
                            }
                        }
                    } catch (Exception parseEx) {
                        log.warn("流式 Tool Calling 无法解析的数据块: {}", jsonChunk);
                    }
                }

                result.setReasoningContent(fullReasoning.length() > 0 ? fullReasoning.toString() : null);

                if (!fullContent.isEmpty()) {
                    result.setContent(fullContent.toString());
                } else {
                    result.setContent(null);
                }

                if (!tcIds.isEmpty()) {
                    ArrayNode toolCallsArray = jsonMapper.createArrayNode();
                    for (Map.Entry<Integer, String> entry : tcIds.entrySet()) {
                        int idx = entry.getKey();
                        ObjectNode tc = jsonMapper.createObjectNode();
                        tc.put("id", entry.getValue());
                        tc.put("type", "function");
                        ObjectNode func = tc.putObject("function");
                        func.put("name", tcNames.getOrDefault(idx, ""));
                        String args = tcArgs.containsKey(idx) ? tcArgs.get(idx).toString() : "{}";
                        func.put("arguments", args);
                        toolCallsArray.add(tc);
                    }
                    result.setToolCall(true);
                    result.setToolCalls(toolCallsArray);
                } else if ("tool_calls".equals(finishReason)) {
                    log.warn("流式 finish_reason=tool_calls 但未收到 tool_calls 内容");
                    result.setToolCall(false);
                    result.setToolCalls(null);
                } else {
                    result.setToolCall(false);
                    result.setToolCalls(null);
                }

                ObjectNode assistantMsg = jsonMapper.createObjectNode();
                assistantMsg.put("role", "assistant");
                if (result.getContent() != null) {
                    assistantMsg.put("content", result.getContent());
                }
                if (result.getReasoningContent() != null) {
                    assistantMsg.put("reasoning_content", result.getReasoningContent());
                }
                if (result.isToolCall() && result.getToolCalls() != null && result.getToolCalls().size() > 0) {
                    assistantMsg.set("tool_calls", result.getToolCalls());
                }
                finalMessages.add(assistantMsg);
                result.setContextMessages(finalMessages);
                success = true;
                break;

            } catch (java.net.SocketTimeoutException e) {
                log.error("Tool Calling 流式请求超时");
                result.setContent("请求超时，请稍后重试或缩短上下文");
                break;
            } catch (java.net.SocketException e) {
                log.error("Tool Calling 流式连接被重置，attempt={}/{}", attempt + 1, maxRetries + 1, e);
                if (attempt >= maxRetries) {
                    result.setContent("网络异常 (连接重置，已重试" + maxRetries + "次): " + e.getMessage());
                }
            } catch (IOException e) {
                log.error("Tool Calling 流式网络 I/O 异常", e);
                result.setContent("网络异常: " + e.getMessage());
                break;
            }
        }

        if (!success && result.getContent() == null) {
            result.setContent("网络异常: 所有重试均失败");
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

        try (Response response = executeWithRetry(request, 2)) {
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