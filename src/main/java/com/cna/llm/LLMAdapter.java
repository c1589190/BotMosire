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
            // 根据 SiliconFlow 的输出格式，物理定位到 data[0].embedding 节点
            JsonNode rootNode = jsonMapper.readTree(response.body().string());
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
     */
    public CallResult generateResponseWithTools(String userMessage,
                                                String contextMemories,
                                                ArrayNode tools) {

        ObjectNode payload = jsonMapper.createObjectNode();
        payload.put("model", config.getChatModel());
        payload.put("stream", false); // 【物理阻断流式】：确保工具调用的 JSON 是一次性完整返回的
        payload.put("temperature", config.getTemperature());
        payload.put("max_tokens", config.getMax_tokens());
        if (config.isEnableCoT()) {
            payload.put("enable_thinking", true);
            //finalSysPrompt += "\n\n【指令约束】在给出最终结果前，必须先进行严谨的逻辑推导。";
        }

        // 挂载工具说明书
        if (tools != null && !tools.isEmpty()) {
            payload.set("tools", tools);
            payload.put("tool_choice", "auto"); // 让模型自己决定用不用工具
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

        CallResult result = new CallResult();

        log.info("发起 Tool Calling 计算请求...");
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                log.error("计算资源请求失败，状态码: {}", response.code());
                result.setContent("计算资源请求失败: " + response.code());
                return result;
            }

            // 读取完整的一整块 JSON
            String responseBody = response.body().string();
            JsonNode rootNode = jsonMapper.readTree(responseBody);
            JsonNode choiceNode = rootNode.path("choices").get(0);
            JsonNode messageNode = choiceNode.path("message");

            // 提取推理内容（兼容非标字段）
            result.setReasoningContent(messageNode.path("reasoning_content").asText(null));

            // 提取常规文本
            JsonNode contentNode = messageNode.path("content");
            if (!contentNode.isNull() && !contentNode.isMissingNode()) {
                result.setContent(contentNode.asText());
            }

            // 【核心拦截】：检查是否命中了工具调用
            String finishReason = choiceNode.path("finish_reason").asText();
            if ("tool_calls".equals(finishReason) || messageNode.has("tool_calls")) {
                result.setToolCall(true);
                result.setToolCalls(messageNode.path("tool_calls"));
            } else {
                result.setToolCall(false);
            }

        } catch (Exception e) {
            log.error("Tool Calling 网络 I/O 异常", e);
            result.setContent("网络异常");
        }

        return result;
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