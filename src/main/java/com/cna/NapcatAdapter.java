package com.cna;

import com.cna.AgentInput.NapcatQQInput.QQGroupMessageInput;
import com.cna.AgentInput.NapcatQQInput.QQPrivateMessageInput;
import com.cna.config.ConfigsManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.*;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Slf4j
public class NapcatAdapter extends WebSocketClient {

    private static final ObjectMapper jsonMapper = new ObjectMapper();

    // ==========================================
    // 物理层资源：HTTP (正向请求 - 同步查询)
    // ==========================================
    private final OkHttpClient httpClient;
    private final String napcatHttpApiBase;

    // 安全凭证
    private final String accessToken;

    // ==========================================
    // 认知层资源：基础信息缓存
    // ==========================================
    private final Map<Long, String> groupNameCache = new ConcurrentHashMap<>();
    private final Map<Long, String> friendNameCache = new ConcurrentHashMap<>();
    private volatile boolean isFriendListCached = false;

    /**
     * 构造主动拨号的 WebSocket 客户端
     */
    public NapcatAdapter() throws URISyntaxException {
        // 构建目标服务器的物理地址，并主动在握手头中注入 Token
        super(buildWsUri(), buildAuthHeaders(ConfigsManager.NAPCAT_TOEKN));

        this.napcatHttpApiBase = ConfigsManager.NAPCAT_HTTP_URL;
        this.accessToken = ConfigsManager.NAPCAT_TOEKN;

        // WebSocket 底层机制保活设置
        this.setConnectionLostTimeout(60);

        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build();
    }

    /**
     * 拼接 WebSocket 目标物理地址
     */
    private static URI buildWsUri() throws URISyntaxException {
        String url = ConfigsManager.NAPCAT_WS_URL;
        if (!url.startsWith("ws://") && !url.startsWith("wss://")) {
            url = "ws://" + url + ":" + ConfigsManager.NAPCAT_WS_PORT;
        }
        return new URI(url);
    }

    /**
     * 在握手阶段主动出示物理通行证 (Token)
     */
    private static Map<String, String> buildAuthHeaders(String token) {
        Map<String, String> headers = new HashMap<>();
        if (token != null && !token.isBlank()) {
            headers.put("Authorization", "Bearer " + token);
        }
        return headers;
    }

    // ==========================================
    // 模块一：WebSocket 生命周期与事件分发
    // ==========================================

    @Override
    public void onOpen(ServerHandshake handshakedata) {
        log.info("[WS] 物理链路接入成功 (正向连接远端 Napcat)");
        log.info("[System] HTTP 查询引擎目标地址: {}", napcatHttpApiBase);

        if (accessToken != null && !accessToken.isBlank()) {
            log.info("[System] Token 鉴权机制已激活并验证通过");
        }

        // 链路打通后，立即预热底层数据缓存
        new Thread(this::refreshFriendListCache).start();
    }

    @Override
    public void onMessage(String message) {
        try {
            JsonNode event = jsonMapper.readTree(message);

            // 过滤非消息事件
            if (!event.has("post_type") || !"message".equals(event.path("post_type").asText())) {
                return;
            }

            String messageType = event.path("message_type").asText();
            long senderId = event.path("user_id").asLong();

            String rawContent = parseMessageArray(event.path("message"));

            // 过滤自身发出的动作，阻断死循环
            if (event.has("self_id") && senderId == event.path("self_id").asLong()) {
                return;
            }

            if ("group".equals(messageType)) {
                handleGroupMessage(event, senderId, rawContent);
            } else if ("private".equals(messageType)) {
                handlePrivateMessage(event, senderId, rawContent);
            }

        } catch (Exception e) {
            log.error("解析 Napcat 事件流失败, payload: {}", message, e);
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

    private String parseMessageArray(JsonNode messageArray) {
        if (messageArray == null || !messageArray.isArray()) {
            return "";
        }

        StringBuilder parsedContent = new StringBuilder();

        for (JsonNode segment : messageArray) {
            String type = segment.path("type").asText("");
            JsonNode data = segment.path("data");

            switch (type) {
                case "text":
                    parsedContent.append(data.path("text").asText(""));
                    break;
                case "image":
                    String url = data.path("url").asText("");
                    parsedContent.append("[图片: ").append(url).append("]");
                    break;
                case "at":
                    String qq = data.path("qq").asText("");
                    parsedContent.append("[@").append(qq).append("]");
                    break;
                case "face":
                    parsedContent.append("[表情]");
                    break;
                default:
                    break;
            }
        }
        return parsedContent.toString().trim();
    }

    private void handleGroupMessage(JsonNode event, long senderId, String content) {
        long groupId = event.path("group_id").asLong();
        String groupName = getGroupNameSync(groupId);
        String senderName = getFriendNameSync(senderId);
        if (senderName.isBlank()) senderName = String.valueOf(senderId);

        System.out.println(String.format("[群聊|%s] %s: %s", groupName, senderName, content));

        QQGroupMessageInput input = new QQGroupMessageInput(
                Utils.getNowFormatted(),
                String.valueOf(groupId),
                groupName,
                String.valueOf(senderId),
                senderName,
                content
        );
        Main.AgentInputTasksQueue.add(input);
    }

    private void handlePrivateMessage(JsonNode event, long senderId, String content) {
        String senderName = getFriendNameSync(senderId);
        if (senderName.isBlank()) senderName = String.valueOf(senderId);

        System.out.println(String.format("[私聊|%s] -> %s", senderName, content));

        QQPrivateMessageInput input = new QQPrivateMessageInput(
                Utils.getNowFormatted(),
                String.valueOf(senderId),
                senderName,
                content
        );
        Main.AgentInputTasksQueue.add(input);
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

    public void sendPrivateMsg(long userId, String message) {
        ObjectNode params = jsonMapper.createObjectNode()
                .put("user_id", userId)
                .put("message", message);
        executeWsAction("send_private_msg", params);
    }

    private void executeWsAction(String action, ObjectNode params) {
        // 正向客户端：检查自身这条链路的连通性
        if (!this.isOpen()) {
            log.warn("[WS] 物理连接未就绪或已断开，无法下发指令: {}", action);
            return;
        }

        try {
            ObjectNode root = jsonMapper.createObjectNode();
            root.put("action", action);
            root.set("params", params);
            root.put("echo", UUID.randomUUID().toString());

            String jsonPayload = jsonMapper.writeValueAsString(root);

            // 直接通过当前的 Socket 通道发送字节流
            this.send(jsonPayload);
            log.debug("[WS] 指令已下发: {}", action);
        } catch (Exception e) {
            log.error("[WS] 封装动作 JSON 失败", e);
        }
    }

    // ==========================================
    // 模块三：HTTP 同步查询
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

        Request.Builder requestBuilder = new Request.Builder()
                .url(url)
                .post(body);

        if (accessToken != null && !accessToken.isBlank()) {
            requestBuilder.addHeader("Authorization", "Bearer " + accessToken);
        }

        try (Response response = httpClient.newCall(requestBuilder.build()).execute()) {
            if (response.isSuccessful() && response.body() != null) {
                return jsonMapper.readTree(response.body().string());
            } else if (response.code() == 401 || response.code() == 403) {
                log.error("[HTTP-Auth] 请求被 Napcat 拒绝 (HTTP {}), 请检查 Token 是否匹配", response.code());
            }
        }
        return null;
    }

    public java.util.List<String> getGroupHistorySync(long groupId, int count) {
        java.util.List<String> historyList = new java.util.ArrayList<>();
        String url = napcatHttpApiBase + "/get_group_msg_history";

        ObjectNode payload = jsonMapper.createObjectNode()
                .put("group_id", groupId)
                .put("message_seq", 0)
                .put("count", count);

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
                        if (senderName.isEmpty()) {
                            senderName = msgNode.path("user_id").asText("Unknown");
                        }

                        String rawMessage = parseMessageArray(msgNode.path("message"));
                        historyList.add(String.format("%s: %s", senderName, rawMessage));
                    }
                }
            }
        } catch (Exception e) {
            log.error("[HTTP] 获取群 {} 的历史消息失败", groupId, e);
        }

        return historyList;
    }

    public java.util.List<String> getFriendHistorySync(long userId, int count) {
        java.util.List<String> historyList = new java.util.ArrayList<>();
        String url = napcatHttpApiBase + "/get_friend_msg_history";

        ObjectNode payload = jsonMapper.createObjectNode()
                .put("user_id", userId)
                .put("message_seq", 0)
                .put("count", count);

        try {
            JsonNode root = executeHttpRequest(url, payload);

            if (root != null && "ok".equals(root.path("status").asText())) {
                JsonNode messagesArray = root.path("data").path("messages");

                if (messagesArray.isArray()) {
                    for (JsonNode msgNode : messagesArray) {
                        String senderName = "";
                        JsonNode senderNode = msgNode.path("sender");
                        if (!senderNode.isMissingNode()) {
                            senderName = senderNode.path("nickname").asText("");
                        }

                        if (senderName.isEmpty()) {
                            long msgSenderId = msgNode.path("user_id").asLong();
                            senderName = getFriendNameSync(msgSenderId);
                            if (senderName.isBlank()) {
                                senderName = String.valueOf(msgSenderId);
                            }
                        }

                        String rawMessage = parseMessageArray(msgNode.path("message"));
                        historyList.add(String.format("%s: %s", senderName, rawMessage));
                    }
                }
            }
        } catch (Exception e) {
            log.error("[HTTP] 获取私聊 {} 的历史消息失败", userId, e);
        }

        return historyList;
    }
}