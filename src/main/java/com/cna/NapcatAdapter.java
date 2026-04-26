package com.cna;

import com.cna.AgentInput.NapcatQQInput.QQGroupMessageInput;
import com.cna.AgentInput.NapcatQQInput.QQPrivateMessageInput;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.*;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Slf4j
public class NapcatAdapter extends WebSocketServer {

    private static final ObjectMapper jsonMapper = new ObjectMapper();

    // ==========================================
    // 物理层资源：WebSocket (反向代理 - 收发事件)
    // ==========================================
    private final ConcurrentHashMap<String, WebSocket> activeConnections = new ConcurrentHashMap<>();

    // ==========================================
    // 物理层资源：HTTP (正向请求 - 同步查询)
    // ==========================================
    private final OkHttpClient httpClient;
    private final String napcatHttpApiBase; // 例如: "http://127.0.0.1:3000"

    // ==========================================
    // 认知层资源：基础信息缓存
    // ==========================================
    private final Map<Long, String> groupNameCache = new ConcurrentHashMap<>();
    private final Map<Long, String> friendNameCache = new ConcurrentHashMap<>();
    private volatile boolean isFriendListCached = false;

    /**
     * 初始化适配器
     *
     * @param wsPort          Java 监听的 WebSocket 端口 (Napcat 需配置 ws_reverse 指向这里)
     * @param napcatHttpUrl   Napcat 的 HTTP API 根地址 (如 http://127.0.0.1:3000)
     */
    public NapcatAdapter(int wsPort, String napcatHttpUrl) {
        super(new InetSocketAddress("0.0.0.0", wsPort));
        this.napcatHttpApiBase = napcatHttpUrl;

        // WebSocket 底层保活配置
        this.setConnectionLostTimeout(60);
        this.setReuseAddr(true);

        // HTTP 客户端配置：短超时，防止查询阻塞主线程太久
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build();
    }

    // ==========================================
    // 模块一：WebSocket 生命周期与事件分发 (Event Bus)
    // ==========================================

    @Override
    public void onStart() {
        log.info("[System] Napcat WS Server 启动于端口: {}", getPort());
        log.info("[System] HTTP 查询引擎目标地址: {}", napcatHttpApiBase);

        // 异步预热好友列表缓存，防止第一条消息阻塞
        new Thread(this::refreshFriendListCache).start();
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        String connId = conn.getRemoteSocketAddress().toString();
        activeConnections.put(connId, conn);
        log.info("[WS] 物理链路接入: {}", connId);
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        activeConnections.remove(conn.getRemoteSocketAddress().toString());
        log.warn("[WS] 物理链路断开: {}", reason);
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        log.error("[WS] 发生底层异常", ex);
    }

    /**
     * 核心中枢：处理 Napcat 推送的事件
     */
    @Override
    public void onMessage(WebSocket conn, String message) {
        try {
            JsonNode event = jsonMapper.readTree(message);

            // 【物理过滤】：我们只关心 post_type = "message" 的事件
            if (!event.has("post_type") || !"message".equals(event.path("post_type").asText())) {
                return; // 忽略心跳、自身状态更新等噪音
            }

            String messageType = event.path("message_type").asText();
            long senderId = event.path("user_id").asLong();
            String rawContent = event.path("raw_message").asText();

            // 防御机制：过滤掉自己发出的消息，防止死循环
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

    private void handleGroupMessage(JsonNode event, long senderId, String content) {
        long groupId = event.path("group_id").asLong();

        // 【查询总线】：使用 HTTP 同步获取缺失信息
        String groupName = getGroupNameSync(groupId);
        // 如果是群友但不是好友，这里暂时用 ID 兜底，后续如果需要可以补充 get_group_member_info 的 HTTP 查询
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
    // 模块二：WebSocket 动作下发 (Command Bus)
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

    /**
     * 将动作序列化并推送至长连接
     */
    private void executeWsAction(String action, ObjectNode params) {
        if (activeConnections.isEmpty()) {
            log.warn("[WS] 没有存活的 Napcat 连接，无法发送动作: {}", action);
            return;
        }

        try {
            ObjectNode root = jsonMapper.createObjectNode();
            root.put("action", action);
            root.set("params", params);
            root.put("echo", UUID.randomUUID().toString()); // 发送流水号

            String jsonPayload = jsonMapper.writeValueAsString(root);

            // 简单路由：拿第一个可用的连接发送
            WebSocket conn = activeConnections.values().iterator().next();
            if (conn.isOpen()) {
                conn.send(jsonPayload);
                log.debug("[WS] 指令已下发: {}", action);
            }
        } catch (Exception e) {
            log.error("[WS] 封装动作 JSON 失败", e);
        }
    }

    // ==========================================
    // 模块三：HTTP 同步查询 (Query Bus)
    // ==========================================

    public String getGroupNameSync(long groupId) {
        if (groupNameCache.containsKey(groupId)) return groupNameCache.get(groupId);

        ObjectNode payload = jsonMapper.createObjectNode().put("group_id", groupId);
        String url = napcatHttpApiBase + "/get_group_detail_info"; // 根据你提供的文档路径

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
        return friendNameCache.getOrDefault(userId, ""); // 根据你的要求，找不到或者非好友返回空串，外部用 ID 兜底
    }

    private synchronized void refreshFriendListCache() {
        if (isFriendListCached) return;

        String url = napcatHttpApiBase + "/get_friend_list";
        ObjectNode payload = jsonMapper.createObjectNode(); // 空 payload

        try {
            JsonNode root = executeHttpRequest(url, payload);
            if (root != null && "ok".equals(root.path("status").asText())) {
                JsonNode dataArray = root.path("data");
                if (dataArray.isArray()) {
                    for (JsonNode friend : dataArray) {
                        long uid = friend.path("user_id").asLong();
                        String nickname = friend.path("nickname").asText("");
                        String remark = friend.path("remark").asText("");
                        // 优先级：备注 > 昵称
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

    /**
     * 底层 HTTP 引擎：封装 OkHttp 调用逻辑
     */
    private JsonNode executeHttpRequest(String url, ObjectNode payload) throws IOException {
        RequestBody body = RequestBody.create(payload.toString(), MediaType.get("application/json"));
        Request request = new Request.Builder()
                .url(url)
                .post(body)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (response.isSuccessful() && response.body() != null) {
                return jsonMapper.readTree(response.body().string());
            }
        }
        return null;
    }
    /**
     * 同步获取群聊历史记录
     *
     * @param groupId 群号
     * @param count   需要获取的最新消息条数
     * @return 格式化后的消息列表 (例如: ["张三: 晚上吃啥", "李四: 烤肉"])
     */
    public java.util.List<String> getGroupHistorySync(long groupId, int count) {
        java.util.List<String> historyList = new java.util.ArrayList<>();
        String url = napcatHttpApiBase + "/get_group_msg_history";

        // 严格按照样例请求构造 JSON Payload
        ObjectNode payload = jsonMapper.createObjectNode()
                .put("group_id", groupId)
                .put("message_seq", 0) // message_seq 传 0 默认获取最新消息
                .put("count", count);

        try {
            // 复用你已经写好的 HTTP 底层引擎
            JsonNode root = executeHttpRequest(url, payload);

            if (root != null && "ok".equals(root.path("status").asText())) {
                JsonNode messagesArray = root.path("data").path("messages");

                if (messagesArray.isArray()) {
                    for (JsonNode msgNode : messagesArray) {
                        // 1. 提取发送者名称 (优先级：群名片 card > 昵称 nickname > QQ号)
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

                        // 2. 提取原始消息内容
                        String rawMessage = msgNode.path("raw_message").asText("");

                        // 3. 组装并加入列表
                        historyList.add(String.format("%s: %s", senderName, rawMessage));
                    }
                }
            }
        } catch (Exception e) {
            log.error("[HTTP] 获取群 {} 的历史消息失败", groupId, e);
        }

        return historyList;
    }

    /**
     * 同步获取私聊（好友）历史记录
     *
     * @param userId 目标好友的 QQ 号
     * @param count  需要获取的最新消息条数
     * @return 格式化后的消息列表 (例如: ["张三: 在吗？", "Konstantin: 怎么了？"])
     */
    public java.util.List<String> getFriendHistorySync(long userId, int count) {
        java.util.List<String> historyList = new java.util.ArrayList<>();
        String url = napcatHttpApiBase + "/get_friend_msg_history";

        // 严格按照样例请求构造 JSON Payload
        ObjectNode payload = jsonMapper.createObjectNode()
                .put("user_id", userId)
                .put("message_seq", 0) // message_seq 传 0 默认获取最新消息
                .put("count", count);

        try {
            // 复用 HTTP 底层引擎
            JsonNode root = executeHttpRequest(url, payload);

            if (root != null && "ok".equals(root.path("status").asText())) {
                JsonNode messagesArray = root.path("data").path("messages");

                if (messagesArray.isArray()) {
                    for (JsonNode msgNode : messagesArray) {
                        // 1. 提取发送者名称
                        String senderName = "";
                        JsonNode senderNode = msgNode.path("sender");
                        if (!senderNode.isMissingNode()) {
                            // 私聊没有群名片，直接拿 nickname
                            senderName = senderNode.path("nickname").asText("");
                        }

                        // 如果底层没返回昵称，用我们自己的缓存方法兜底
                        if (senderName.isEmpty()) {
                            long msgSenderId = msgNode.path("user_id").asLong();
                            senderName = getFriendNameSync(msgSenderId);
                            // 终极兜底：连缓存都没有，就显示 QQ 号
                            if (senderName.isBlank()) {
                                senderName = String.valueOf(msgSenderId);
                            }
                        }

                        // 2. 提取原始消息内容
                        String rawMessage = msgNode.path("raw_message").asText("");

                        // 3. 组装并加入列表
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