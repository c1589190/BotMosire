package com.cna;

import com.cna.agent.AgentInput.ChatMessageInput;
import com.cna.config.ConfigsManager;
import com.cna.llm.LLMAdapter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.*;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Slf4j
public class NapcatAdapter extends WebSocketClient {

    private static final ObjectMapper jsonMapper = new ObjectMapper();

    private final OkHttpClient httpClient;
    private final String napcatHttpApiBase;
    private final String accessToken;

    // 单线程异步队列：防乱序、防掉线阻塞
    private final ExecutorService messageProcessorThread = Executors.newSingleThreadExecutor();
    private volatile boolean isShuttingDown = false;

    // 视觉大模型复用实例
    private LLMAdapter visionLLM;

    private final Map<Long, String> groupNameCache = new ConcurrentHashMap<>();
    private final Map<Long, String> friendNameCache = new ConcurrentHashMap<>();
    private volatile boolean isFriendListCached = false;

    // LRU 缓存：防止重复解析同一张图片（容量 200）
    private final Map<String, String> imageVisionCache = Collections.synchronizedMap(
            new LinkedHashMap<String, String>(100, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > 200;
                }
            }
    );

    public NapcatAdapter() throws URISyntaxException {
        super(buildWsUri(), buildAuthHeaders(ConfigsManager.NAPCAT_TOEKN));

        this.napcatHttpApiBase = ConfigsManager.NAPCAT_HTTP_URL;
        this.accessToken = ConfigsManager.NAPCAT_TOEKN;
        this.setConnectionLostTimeout(60);

        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build();

        try {
            this.visionLLM = new LLMAdapter(ConfigsManager.VISION_MODEL);
        } catch (Exception e) {
            log.warn("[System] 视觉大模型初始化失败，图片解析将被禁用。");
        }
    }

    private static URI buildWsUri() throws URISyntaxException {
        String url = ConfigsManager.NAPCAT_WS_URL;
        if (!url.startsWith("ws://") && !url.startsWith("wss://")) {
            url = "ws://" + url + ":" + ConfigsManager.NAPCAT_WS_PORT;
        }
        return new URI(url);
    }

    private static Map<String, String> buildAuthHeaders(String token) {
        Map<String, String> headers = new HashMap<>();
        if (token != null && !token.isBlank()) {
            headers.put("Authorization", "Bearer " + token);
        }
        return headers;
    }

    @Override
    public void onOpen(ServerHandshake handshakedata) {
        log.info("[WS] 物理链路接入成功 (正向连接远端 Napcat)");
        new Thread(this::refreshFriendListCache).start();
    }

    @Override
    public void onMessage(String message) {
        messageProcessorThread.submit(() -> {
            try {
                JsonNode event = jsonMapper.readTree(message);
                String postType = event.path("post_type").asText();

                if ("message".equals(postType)) {
                    handleMessageEvent(event);
                } else if ("notice".equals(postType)) {
                    // handleNoticeEvent(event); // 预留的通知事件
                }

            } catch (Throwable t) {
                log.error("[拦截追踪] 异步解析事件流发生崩溃, payload: {}", message, t);
            }
        });
    }

    // ==========================================
    // 🌟 核心重构：JDK 21 内部 Record DTO
    // ==========================================
    private record ParsedMessage(String content, long quotedMessageId) {}

    private void handleMessageEvent(JsonNode event) {
        String messageType = event.path("message_type").asText();
        long senderId = event.path("user_id").asLong();
        long selfId = event.path("self_id").asLong();

        if (senderId == selfId) {
            return; // 屏蔽自身消息
        }

        // 🌟 视觉注意力防瘫痪阀门
        boolean isPrivate = "private".equals(messageType);
        boolean isAtMe = checkIsAtMe(event.path("message"), selfId);
        boolean shouldParseVision = isPrivate || isAtMe;

        // 🌟 一次遍历解析：文本、图片AI、引用ID、合并转发
        ParsedMessage parsed = parseMessageContent(event.path("message"), shouldParseVision);

        if (parsed.content() == null || parsed.content().isBlank()) return;

        if ("group".equals(messageType)) {
            handleGroupMessage(event, senderId, parsed.content(), parsed.quotedMessageId());
        } else if (isPrivate) {
            handlePrivateMessage(event, senderId, parsed.content(), parsed.quotedMessageId());
        }
    }

    private ParsedMessage parseMessageContent(JsonNode messageNode, boolean shouldParseVision) {
        // 修复点：直接 new 出来，干掉之前的 empty() 导致找不到符号的报错
        if (messageNode == null || messageNode.isMissingNode()) return new ParsedMessage("", 0);
        if (messageNode.isTextual()) return new ParsedMessage(messageNode.asText().trim(), 0);
        if (!messageNode.isArray()) return new ParsedMessage("", 0);

        StringBuilder parsedContent = new StringBuilder();
        long quotedId = 0;

        for (JsonNode segment : messageNode) {
            String type = segment.path("type").asText("");
            JsonNode data = segment.path("data");

            switch (type) {
                case "text":
                    parsedContent.append(data.path("text").asText(""));
                    break;

                case "image":
                    if (!shouldParseVision) {
                        parsedContent.append("[图片]");
                        break;
                    }

                    String imageUrl = data.path("url").asText("");
                    String cacheKey = data.has("md5") ? data.path("md5").asText() :
                            (data.path("file").asText("").isBlank() ? imageUrl.split("\\?")[0] : data.path("file").asText(""));

                    if (imageVisionCache.containsKey(cacheKey)) {
                        log.info("[感知引擎] 命中图片解析缓存，跳过 LLM 调用以节省 Token");
                        parsedContent.append("[图片(AI解析): ").append(imageVisionCache.get(cacheKey)).append("]");
                        break;
                    }

                    log.info("[感知引擎] 捕捉到与 Bot 相关的图片，移交视觉中枢深度处理...");
                    String base64 = downloadImageToBase64(imageUrl);
                    if (base64 != null && this.visionLLM != null) {
                        try {
                            String visionResult = this.visionLLM.generateVisionDescription(
                                    "请简短客观地描述这张图片，若有明显文字请提取。", base64
                            );
                            imageVisionCache.put(cacheKey, visionResult);
                            parsedContent.append("[图片(AI解析): ").append(visionResult).append("]");
                        } catch (Exception e) {
                            parsedContent.append("[图片: 解析服务异常]");
                        }
                    } else {
                        parsedContent.append("[图片: 下载或模型装载失败]");
                    }
                    break;

                case "at":
                    parsedContent.append("[@").append(data.path("qq").asText("")).append("]");
                    break;

                case "face":
                    parsedContent.append("[表情]");
                    break;

                case "forward":
                    String forwardId = data.path("id").asText("");
                    parsedContent.append("\n【合并转发记录开始】\n");
                    parsedContent.append(getForwardMsgSync(forwardId));
                    parsedContent.append("【合并转发记录结束】\n");
                    break;

                case "reply":
                    String replyIdStr = data.path("id").asText("");
                    if (!replyIdStr.isBlank()) {
                        try {
                            quotedId = Long.parseLong(replyIdStr);
                            parsedContent.append("[引用回覆: ").append(replyIdStr).append("]");
                        } catch (NumberFormatException ignored) {}
                    }
                    break;
            }
        }
        return new ParsedMessage(parsedContent.toString().trim(), quotedId);
    }

    private boolean checkIsAtMe(JsonNode messageNode, long selfId) {
        if (messageNode == null || !messageNode.isArray()) return false;
        for (JsonNode segment : messageNode) {
            if ("at".equals(segment.path("type").asText())) {
                if (segment.path("data").path("qq").asLong(0) == selfId) {
                    return true;
                }
            }
        }
        return false;
    }

    private void handleGroupMessage(JsonNode event, long senderId, String content, long replyToMessageId) {
        try {
            long groupId = event.path("group_id").asLong();
            String groupName = getGroupNameSync(groupId);
            String senderName = getFriendNameSync(senderId);
            if (senderName.isBlank()) senderName = String.valueOf(senderId);

            System.out.println(String.format("[群聊|%s] %s: %s", groupName, senderName, content));

            ChatMessageInput input = new ChatMessageInput(
                    "qq_group:" + groupId,
                    groupName,
                    "qqid:" + senderId,
                    senderName,
                    content,
                    replyToMessageId
            );
            Main.offerInput(input, "QQ群:" + groupId);
        } catch (Throwable t) {
            log.error("[拦截追踪] 群消息推入主线队列失败", t);
        }
    }

    private void handlePrivateMessage(JsonNode event, long senderId, String content, long replyToMessageId) {
        try {
            String senderName = getFriendNameSync(senderId);
            if (senderName.isBlank()) senderName = String.valueOf(senderId);

            System.out.println(String.format("[私聊|%s] -> %s", senderName, content));

            ChatMessageInput input = new ChatMessageInput(
                    "qq_private:" + senderId,
                    "QQ私聊",
                    "qqid:" + senderId,
                    senderName,
                    content,
                    replyToMessageId
            );
            Main.offerInput(input, "QQ私聊:" + senderId);
        } catch (Throwable t) {
            log.error("[拦截追踪] 私聊消息推入主线队列失败", t);
        }
    }

    // ==========================================
    // 模块二：WebSocket 动作下发
    // ==========================================

    public void sendGroupMsg(long groupId, String message) {
        ObjectNode params = jsonMapper.createObjectNode()
                .put("group_id", groupId)
                .put("message", message);
        executeWsAction("send_group_msg", params);
    }

    public void sendGroupMsgWithReply(long groupId, String message, long replyToMessageId) {
        ArrayNode messageArray = jsonMapper.createArrayNode();
        messageArray.addObject().put("type", "reply").putObject("data").put("id", replyToMessageId);
        messageArray.addObject().put("type", "text").putObject("data").put("text", message);

        ObjectNode params = jsonMapper.createObjectNode().put("group_id", groupId);
        params.set("message", messageArray);
        executeWsAction("send_group_msg", params);
    }

    public void sendPrivateMsg(long userId, String message) {
        ObjectNode params = jsonMapper.createObjectNode()
                .put("user_id", userId)
                .put("message", message);
        executeWsAction("send_private_msg", params);
    }

    public void sendPrivateMsgWithReply(long userId, String message, long replyToMessageId) {
        ArrayNode messageArray = jsonMapper.createArrayNode();
        messageArray.addObject().put("type", "reply").putObject("data").put("id", replyToMessageId);
        messageArray.addObject().put("type", "text").putObject("data").put("text", message);

        ObjectNode params = jsonMapper.createObjectNode().put("user_id", userId);
        params.set("message", messageArray);
        executeWsAction("send_private_msg", params);
    }

    private void executeWsAction(String action, ObjectNode params) {
        if (!this.isOpen()) {
            log.warn("[WS] 物理连接未就绪或已断开，无法下发指令: {}", action);
            return;
        }
        try {
            ObjectNode root = jsonMapper.createObjectNode();
            root.put("action", action);
            root.set("params", params);
            root.put("echo", UUID.randomUUID().toString());
            this.send(jsonMapper.writeValueAsString(root));
            log.debug("[WS] 指令已下发: {}", action);
        } catch (Exception e) {
            log.error("[WS] 封装动作 JSON 失败", e);
        }
    }

    // ==========================================
    // 模块三：HTTP 同步查询与解析
    // ==========================================

    public String getGroupNameSync(long groupId) {
        if (groupNameCache.containsKey(groupId)) return groupNameCache.get(groupId);

        ObjectNode payload = jsonMapper.createObjectNode().put("group_id", groupId);
        String url = napcatHttpApiBase + "/get_group_detail_info";

        try {
            JsonNode root = executeHttpRequest(url, payload);
            if (root != null && "ok".equals(root.path("status").asText())) {
                String name = root.path("data").path("group_name").asText();
                if (!name.isBlank()) {
                    groupNameCache.put(groupId, name);
                    return name;
                }
            }
        } catch (Exception e) {
            log.error("[HTTP] 获取群 {} 名称失败", groupId, e);
        }
        return String.valueOf(groupId);
    }

    public String getFriendNameSync(long userId) {
        if (!isFriendListCached) refreshFriendListCache();
        return friendNameCache.getOrDefault(userId, "");
    }

    private synchronized void refreshFriendListCache() {
        if (isFriendListCached) return;

        String url = napcatHttpApiBase + "/get_friend_list";
        ObjectNode payload = jsonMapper.createObjectNode();

        try {
            JsonNode root = executeHttpRequest(url, payload);
            if (root != null && "ok".equals(root.path("status").asText())) {
                JsonNode dataArray = root.path("data");
                if (dataArray.isArray()) {
                    for (JsonNode friend : dataArray) {
                        long uid = friend.path("user_id").asLong();
                        String nickname = friend.path("nickname").asText("");
                        String remark = friend.path("remark").asText("");
                        friendNameCache.put(uid, !remark.isBlank() ? remark : nickname);
                    }
                }
                isFriendListCached = true;
                log.info("[HTTP] 好友列表缓存刷新完毕，共 {} 人", friendNameCache.size());
            }
        } catch (Exception e) {
            log.error("[HTTP] 拉取好友列表失败", e);
        }
    }

    private JsonNode executeHttpRequest(String url, ObjectNode payload) throws IOException {
        RequestBody body = RequestBody.create(payload.toString(), MediaType.get("application/json"));
        Request.Builder requestBuilder = new Request.Builder().url(url).post(body);
        if (accessToken != null && !accessToken.isBlank()) {
            requestBuilder.addHeader("Authorization", "Bearer " + accessToken);
        }
        try (Response response = httpClient.newCall(requestBuilder.build()).execute()) {
            if (response.isSuccessful() && response.body() != null) {
                return jsonMapper.readTree(response.body().string());
            } else if (response.code() == 401 || response.code() == 403) {
                log.error("[HTTP-Auth] 请求被拒绝 (HTTP {}), 检查 Token", response.code());
            }
        }
        return null;
    }

    public String getForwardMsgSync(String messageId) {
        if (messageId == null || messageId.isBlank()) return "";
        String url = napcatHttpApiBase + "/get_forward_msg";
        ObjectNode payload = jsonMapper.createObjectNode().put("message_id", messageId);
        StringBuilder sb = new StringBuilder();
        try {
            JsonNode root = executeHttpRequest(url, payload);
            if (root != null && "ok".equals(root.path("status").asText())) {
                JsonNode messagesArray = root.path("data").path("messages");
                if (messagesArray.isMissingNode() || !messagesArray.isArray()) {
                    messagesArray = root.path("data");
                }
                if (messagesArray.isArray()) {
                    for (JsonNode msgNode : messagesArray) {
                        String senderName = msgNode.path("sender").path("nickname").asText("Unknown");
                        JsonNode contentNode = msgNode.has("content") ? msgNode.path("content") : msgNode.path("message");
                        String innerContent = parseMessageContent(contentNode, false).content();
                        sb.append("  - ").append(senderName).append(": ").append(innerContent).append("\n");
                    }
                }
            }
        } catch (Exception e) {
            log.error("[HTTP] 获取合并转发内容失败", e);
            return "[解析合并转发记录失败]";
        }
        return sb.toString();
    }

    public java.util.List<String> getGroupHistorySync(long groupId, int count) {
        java.util.List<String> historyList = new java.util.ArrayList<>();
        String url = napcatHttpApiBase + "/get_group_msg_history";
        ObjectNode payload = jsonMapper.createObjectNode()
                .put("group_id", groupId).put("message_seq", 0).put("count", count);
        try {
            JsonNode root = executeHttpRequest(url, payload);
            if (root != null && "ok".equals(root.path("status").asText())) {
                JsonNode messagesArray = root.path("data").path("messages");
                if (messagesArray.isArray()) {
                    for (JsonNode msgNode : messagesArray) {
                        String senderName = "";
                        JsonNode senderNode = msgNode.path("sender");
                        if (!senderNode.isMissingNode()) {
                            String card = senderNode.path("card").asText("");
                            String nickname = senderNode.path("nickname").asText("");
                            senderName = !card.isEmpty() ? card : nickname;
                        }
                        if (senderName.isEmpty()) senderName = msgNode.path("user_id").asText("Unknown");
                        String rawMessage = parseMessageContent(msgNode.path("message"), false).content();
                        historyList.add(String.format("%s: %s", senderName, rawMessage));
                    }
                }
            }
        } catch (Exception e) {
            log.error("[HTTP] 获取群历史失败", e);
        }
        return historyList;
    }

    public java.util.List<String> getFriendHistorySync(long userId, int count) {
        java.util.List<String> historyList = new java.util.ArrayList<>();
        String url = napcatHttpApiBase + "/get_friend_msg_history";
        ObjectNode payload = jsonMapper.createObjectNode()
                .put("user_id", userId).put("message_seq", 0).put("count", count);
        try {
            JsonNode root = executeHttpRequest(url, payload);
            if (root != null && "ok".equals(root.path("status").asText())) {
                JsonNode messagesArray = root.path("data").path("messages");
                if (messagesArray.isArray()) {
                    for (JsonNode msgNode : messagesArray) {
                        String senderName = msgNode.path("sender").path("nickname").asText("");
                        if (senderName.isEmpty()) {
                            long msgSenderId = msgNode.path("user_id").asLong();
                            senderName = getFriendNameSync(msgSenderId);
                            if (senderName.isBlank()) senderName = String.valueOf(msgSenderId);
                        }
                        String rawMessage = parseMessageContent(msgNode.path("message"), false).content();
                        historyList.add(String.format("%s: %s", senderName, rawMessage));
                    }
                }
            }
        } catch (Exception e) {
            log.error("[HTTP] 获取私聊历史失败", e);
        }
        return historyList;
    }

    // ==========================================
    // 🌟 终极文件发送修复：Base64 内存直传，无视环境与路径
    // ==========================================

    public String sendGroupFile(long groupId, java.nio.file.Path filePath) {
        if (!java.nio.file.Files.exists(filePath)) return "ERROR: 文件不存在: " + filePath;
        String url = napcatHttpApiBase + "/upload_group_file";

        try {
            // 将文件直接读入内存并转为 Base64 字符串
            byte[] fileBytes = java.nio.file.Files.readAllBytes(filePath);
            String base64Data = java.util.Base64.getEncoder().encodeToString(fileBytes);

            // 使用 base64:// 协议发送，彻底避开底层路径解析
            ObjectNode payload = jsonMapper.createObjectNode()
                    .put("group_id", groupId)
                    .put("file", "base64://" + base64Data)
                    .put("name", filePath.getFileName().toString());

            JsonNode root = executeHttpRequest(url, payload);
            if (root != null && "ok".equals(root.path("status").asText())) {
                log.info("[Napcat] 群文件直传成功: {} → 群{}", filePath.getFileName(), groupId);
                return "SUCCESS";
            }
            String msg = root != null ? root.path("message").asText("unknown") : "no response";
            return "ERROR: Napcat 返回失败: " + msg;
        } catch (Exception e) {
            log.error("[Napcat] 上传群文件异常", e);
            return "ERROR: " + e.getMessage();
        }
    }

    public String sendPrivateFile(long userId, java.nio.file.Path filePath) {
        if (!java.nio.file.Files.exists(filePath)) return "ERROR: 文件不存在: " + filePath;
        String url = napcatHttpApiBase + "/upload_private_file";

        try {
            // 将文件直接读入内存并转为 Base64 字符串
            byte[] fileBytes = java.nio.file.Files.readAllBytes(filePath);
            String base64Data = java.util.Base64.getEncoder().encodeToString(fileBytes);

            ObjectNode payload = jsonMapper.createObjectNode()
                    .put("user_id", userId)
                    .put("file", "base64://" + base64Data)
                    .put("name", filePath.getFileName().toString());

            JsonNode root = executeHttpRequest(url, payload);
            if (root != null && "ok".equals(root.path("status").asText())) {
                log.info("[Napcat] 私聊文件直传成功: {} → 用户{}", filePath.getFileName(), userId);
                return "SUCCESS";
            }
            String msg = root != null ? root.path("message").asText("unknown") : "no response";
            return "ERROR: Napcat 返回失败: " + msg;
        } catch (Exception e) {
            log.error("[Napcat] 上传私聊文件异常", e);
            return "ERROR: " + e.getMessage();
        }
    }

    private String downloadImageToBase64(String imageUrl) {
        Request request = new Request.Builder().url(imageUrl).build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (response.isSuccessful() && response.body() != null) {
                byte[] imageBytes = response.body().bytes();
                return java.util.Base64.getEncoder().encodeToString(imageBytes);
            }
        } catch (Exception e) {
            log.error("[HTTP] 下载图片转码失败: {}", imageUrl, e);
        }
        return null;
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        log.warn("[WS] 物理链路被切断: Code: {}, Reason: {}, 由远端发起: {}", code, reason, remote);
    }

    public void shutdown() {
        isShuttingDown = true;
        try {
            this.closeConnection(1000, "Shutting down");
        } catch (Exception ignored) {}
        messageProcessorThread.shutdown();
        try {
            if (!messageProcessorThread.awaitTermination(2, TimeUnit.SECONDS)) {
                messageProcessorThread.shutdownNow();
            }
        } catch (InterruptedException e) {
            messageProcessorThread.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("[NapcatAdapter] 消息处理线程已关闭。");
    }

    @Override
    public void onError(Exception ex) {
        log.error("[WS] 发生底层网络异常", ex);
    }
}