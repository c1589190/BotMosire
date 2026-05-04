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

    // 【关键修复 1】：改用单线程异步队列。既不阻塞 WebSocket 掉线，又保证消息严格按时间线排队，防止小模型因乱序拒收消息！
    private final ExecutorService messageProcessorThread = Executors.newSingleThreadExecutor();

    // 【关键修复 2】：把视觉大模型设为单例复用，防止每次收到图片都 new 导致 OkHttp 线程池爆炸卡死
    private LLMAdapter visionLLM;

    private final Map<Long, String> groupNameCache = new ConcurrentHashMap<>();
    private final Map<Long, String> friendNameCache = new ConcurrentHashMap<>();
    private volatile boolean isFriendListCached = false;

    // 【关键优化 3】：建立图片解析的 LRU 缓存，最大存储 200 条，超限自动淘汰最旧的数据
    private final Map<String, String> imageVisionCache = Collections.synchronizedMap(
            new LinkedHashMap<String, String>(100, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > 200; // 缓存最大容量，可根据需要调整
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

        // 在构造函数中初始化视觉模型，全生命周期仅实例化一次
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

                // --- 原有的消息处理逻辑 ---
                if ("message".equals(postType)) {
                    handleMessageEvent(event);
                }
                // --- 新增：处理通知事件（如撤回） ---
                else if ("notice".equals(postType)) {
                    //handleNoticeEvent(event);
                    //对撤回消息的适配未完工
                }

            } catch (Throwable t) {
                log.error("[拦截追踪] 异步解析事件流发生崩溃, payload: {}", message, t);
            }
        });
    }

    private void handleMessageEvent(JsonNode event) {
        String messageType = event.path("message_type").asText();
        long senderId = event.path("user_id").asLong();

        if (event.has("self_id") && senderId == event.path("self_id").asLong()) {
            return;
        }

        ParsedMessage parsed = ParsedMessage.parseMessageArray(event.path("message"));
        if (parsed.content == null || parsed.content.isBlank()) return;

        if ("group".equals(messageType)) {
            handleGroupMessage(event, senderId, parsed.content, parsed.quotedMessageId);
        } else if ("private".equals(messageType)) {
            handlePrivateMessage(event, senderId, parsed.content, parsed.quotedMessageId);
        }
    }

    private void handleNoticeEvent(JsonNode event) {
        String noticeType = event.path("notice_type").asText();

        // 兼容群撤回和好友撤回
        if ("group_recall".equals(noticeType) || "friend_recall".equals(noticeType)) {
            long userId = event.path("user_id").asLong();         // 消息发送者
            long operatorId = event.path("operator_id").asLong(); // 执行撤回的人
            long messageId = event.path("message_id").asLong();   // 被撤回的消息ID

            String operatorName = getFriendNameSync(operatorId);
            if (operatorName.isBlank()) operatorName = String.valueOf(operatorId);

            String senderName = getFriendNameSync(userId);
            if (senderName.isBlank()) senderName = String.valueOf(userId);

            String noticeContent;
            if (userId == operatorId) {
                noticeContent = String.format("[系统提示] %s 撤回了一条自己的消息。", operatorName);
            } else {
                noticeContent = String.format("[系统提示] 管理员 %s 撤回了 %s 的消息。", operatorName, senderName);
            }

            // 确定 Namespace
            String targetNamespace;
            String sceneName;

            if ("group_recall".equals(noticeType)) {
                long groupId = event.path("group_id").asLong();
                targetNamespace = "qq_group:" + groupId;
                sceneName = getGroupNameSync(groupId);
            } else {
                targetNamespace = "qq_private";
                sceneName = "QQ私聊";
            }

            // 生成一条特殊的系统输入压入队列
            ChatMessageInput input = new ChatMessageInput(
                    targetNamespace,
                    sceneName,
                    "qqid:"+operatorId,  // 标记为系统发送
                    "有关系统提示",
                    noticeContent
            );

            log.info("[感知引擎] 检测到撤回事件: {}", noticeContent);
            Main.AgentInputTasksQueue.add(input);
        }
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        log.warn("[WS] 物理链路被切断: Code: {}, Reason: {}, 由远端发起: {}", code, reason, remote);
    }

    @Override
    public void onError(Exception ex) {
        log.error("[WS] 发生底层网络异常", ex);
    }

    private String parseMessageArray(JsonNode messageNode) {
        if (messageNode == null || messageNode.isMissingNode()) return "";
        if (messageNode.isTextual()) return messageNode.asText().trim();
        if (!messageNode.isArray()) return "";

        StringBuilder parsedContent = new StringBuilder();
        for (JsonNode segment : messageNode) {
            String type = segment.path("type").asText("");
            JsonNode data = segment.path("data");

            switch (type) {
                case "text":
                    parsedContent.append(data.path("text").asText(""));
                    break;
                case "image":
                    String imageUrl = data.path("url").asText("");

                    // 获取唯一标识作为 Cache Key
                    String fileId = data.path("file").asText("");
                    String cacheKey = fileId.isBlank() ? imageUrl.split("\\?")[0] : fileId;

                    // 检查缓存是否命中
                    if (imageVisionCache.containsKey(cacheKey)) {
                        log.info("[感知引擎] 命中图片解析缓存，跳过 LLM 调用以节省 Token");
                        parsedContent.append("[图片(AI解析): ").append(imageVisionCache.get(cacheKey)).append("]");
                        break;
                    }

                    // 未命中缓存，执行下载和模型请求
                    log.info("[感知引擎] 捕捉到新图片，正在移交视觉中枢处理...");
                    String base64 = downloadImageToBase64(imageUrl);
                    if (base64 != null && this.visionLLM != null) {
                        try {
                            String visionResult = this.visionLLM.generateVisionDescription(
                                    "请简短客观地描述这张图片，若有明显文字请提取。", base64
                            );

                            // 将解析结果存入缓存
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
                    // 引用回复：记录被引用消息的 ID，供上层判断是否要引用回复
                    String replyId = data.path("id").asText("");
                    if (!replyId.isBlank()) {
                        parsedContent.append("[引用回复: ").append(replyId).append("]");
                    }
                    break;
            }
        }
        return parsedContent.toString().trim();
    }

    private void handleGroupMessage(JsonNode event, long senderId, String content, long replyToMessageId) {
        try {
            long groupId = event.path("group_id").asLong();
            String groupName = getGroupNameSync(groupId);
            String senderName = getFriendNameSync(senderId);
            if (senderName.isBlank()) senderName = String.valueOf(senderId);

            System.out.println(String.format("[群聊|%s] %s: %s", groupName, senderName, content));

            ChatMessageInput input = new ChatMessageInput(
                    "qq_group:"+String.valueOf(groupId),
                    groupName,
                    "qqid:"+String.valueOf(senderId),
                    senderName,
                    content,
                    replyToMessageId
            );
            Main.AgentInputTasksQueue.add(input);
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
                    "qq_private",
                    "QQ私聊",
                    "qqid:"+String.valueOf(senderId),
                    senderName,
                    content,
                    replyToMessageId
            );
            Main.AgentInputTasksQueue.add(input);
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
        // Napcat/Oicq 的引用回覆格式：message 是一個 array，第一個元素是 reply
        ArrayNode messageArray = jsonMapper.createArrayNode();
        messageArray.addObject().put("type", "reply").putObject("data").put("id", replyToMessageId);
        messageArray.addObject().put("type", "text").putObject("data").put("text", message);

        ObjectNode params = jsonMapper.createObjectNode()
                .put("group_id", groupId);
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
        // Napcat/Oicq 的引用回覆格式：message 是一個 array，第一個元素是 reply
        ArrayNode messageArray = jsonMapper.createArrayNode();
        messageArray.addObject().put("type", "reply").putObject("data").put("id", replyToMessageId);
        messageArray.addObject().put("type", "text").putObject("data").put("text", message);

        ObjectNode params = jsonMapper.createObjectNode()
                .put("user_id", userId);
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
                        String innerContent = parseMessageArray(contentNode);
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
                        String rawMessage = parseMessageArray(msgNode.path("message"));
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
                        String rawMessage = parseMessageArray(msgNode.path("message"));
                        historyList.add(String.format("%s: %s", senderName, rawMessage));
                    }
                }
            }
        } catch (Exception e) {
            log.error("[HTTP] 获取私聊历史失败", e);
        }
        return historyList;
    }

    /**
     * 同步下载图片并转换为 Base64
     */
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
}