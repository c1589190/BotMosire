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
@Deprecated
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
        new Thread(() -> {
            refreshFriendListCache();
            fetchSelfId();
        }).start();
    }

    /** 通过 get_login_info 获取 bot 自身 QQ 号 */
    private void fetchSelfId() {
        String url = napcatHttpApiBase + "/get_login_info";
        ObjectNode payload = jsonMapper.createObjectNode();
        try {
            JsonNode root = executeHttpRequest(url, payload);
            if (root != null && "ok".equals(root.path("status").asText())) {
                this.selfId = root.path("data").path("user_id").asLong();
                log.info("[NapcatAdapter] 从 API 获取自身 QQ: {}", selfId);
            }
        } catch (Exception e) {
            log.warn("[NapcatAdapter] 获取自身 QQ 失败，将回退到从事件中读取: {}", e.getMessage());
        }
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
    private record ParsedMessage(
            String content,
            long quotedMessageId,
            boolean isAtMe,
            java.util.List<String> atTargets,
            boolean hasForward,
            boolean hasImage,
            int imageCount,
            int faceCount,
            String richSummary,
            java.util.List<ChatMessageInput.FileAttachment> fileAttachments
    ) {}

    /** bot 自身 QQ 号，启动时通过 get_login_info 获取 */
    private volatile long selfId = -1;

    private void handleMessageEvent(JsonNode event) {
        String messageType = event.path("message_type").asText();
        long senderId = event.path("user_id").asLong();
        long eventSelfId = event.path("self_id").asLong();

        // 首次拿到 selfId 时缓存
        if (selfId < 0) {
            selfId = eventSelfId;
            log.info("[NapcatAdapter] 从事件中获取自身 QQ: {}", selfId);
        }

        if (senderId == selfId) {
            return; // 屏蔽自身消息
        }

        boolean isPrivate = "private".equals(messageType);
        boolean shouldParseVision = isPrivate; // 私聊必定解析图片；群聊先看是否 @自己

        // 🌟 一次遍历解析：返回完整结构化信息
        ParsedMessage parsed = parseMessageContent(event.path("message"), shouldParseVision, selfId);

        // 如果初始化时还没拿到 selfId 导致漏判，用解析结果的 isAtMe 补刀
        boolean effectiveParseVision = shouldParseVision || parsed.isAtMe();
        if (effectiveParseVision && !shouldParseVision) {
            // 有 @自己但第一次遍历时没解析图片 → 重新解析一遍
            parsed = parseMessageContent(event.path("message"), true, selfId);
        }

        if (parsed.content() == null || parsed.content().isBlank()) return;

        // sender 信息
        JsonNode senderNode = event.path("sender");
        String senderCard = senderNode.path("card").asText("");
        String senderGroupRole = senderNode.path("role").asText("member");
        String subType = event.path("sub_type").asText("normal");

        if ("group".equals(messageType)) {
            handleGroupMessage(event, senderId, parsed, senderCard, senderGroupRole, subType, isPrivate);
        } else if (isPrivate) {
            handlePrivateMessage(event, senderId, parsed, senderCard, senderGroupRole, subType);
        }
    }

    /**
     * 解析 OneBot v11 message array，返回完整结构化信息。
     * <p>
     * 支持的 segment 类型: text / at / image / face / reply / forward / json / xml / video / record / file / markdown
     */
    private ParsedMessage parseMessageContent(JsonNode messageNode, boolean shouldParseVision, long selfId) {
        if (messageNode == null || messageNode.isMissingNode())
            return new ParsedMessage("", 0, false, java.util.List.of(), false, false, 0, 0, "", java.util.List.of());
        if (messageNode.isTextual())
            return new ParsedMessage(messageNode.asText().trim(), 0, false, java.util.List.of(), false, false, 0, 0, "", java.util.List.of());
        if (!messageNode.isArray())
            return new ParsedMessage("", 0, false, java.util.List.of(), false, false, 0, 0, "", java.util.List.of());

        StringBuilder parsedContent = new StringBuilder();
        long quotedId = 0;
        boolean isAtMe = false;
        java.util.List<String> atTargets = new java.util.ArrayList<>();
        boolean hasForward = false;
        int imageCount = 0;
        int faceCount = 0;
        java.util.List<ChatMessageInput.FileAttachment> fileAttachments = new java.util.ArrayList<>();

        for (JsonNode segment : messageNode) {
            String type = segment.path("type").asText("");
            JsonNode data = segment.path("data");

            switch (type) {
                // ── 基础文本 ──
                case "text":
                    parsedContent.append(data.path("text").asText(""));
                    break;

                // ── @提及 ──
                case "at":
                    String atQq = data.path("qq").asText("");
                    atTargets.add(atQq);
                    if (!atQq.isBlank() && Long.parseLong(atQq) == selfId) {
                        isAtMe = true;
                        parsedContent.append("[@").append(atQq).append("(自身qq)]");
                    } else {
                        parsedContent.append("[@").append(atQq).append("]");
                    }
                    break;

                // ── 图片 ──
                case "image":
                    imageCount++;
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

                // ── QQ 表情 ──
                case "face":
                    faceCount++;
                    parsedContent.append("[表情]");
                    break;

                // ── 引用回复 ──
                case "reply":
                    String replyIdStr = data.path("id").asText("");
                    if (!replyIdStr.isBlank()) {
                        try {
                            quotedId = Long.parseLong(replyIdStr);
                            // 不再往正文塞 [引用回覆:xxx]，getInputText 会自动标注
                        } catch (NumberFormatException ignored) {}
                    }
                    break;

                // ── 合并转发 ──
                case "forward":
                    hasForward = true;
                    String forwardId = data.path("id").asText("");
                    parsedContent.append("\n【合并转发记录开始】\n");
                    parsedContent.append(getForwardMsgSync(forwardId));
                    parsedContent.append("【合并转发记录结束】\n");
                    break;

                // ── JSON 卡片（小程序/分享卡片等） ──
                case "json":
                    String jsonData = data.path("data").asText("");
                    parsedContent.append("[JSON卡片");
                    if (!jsonData.isBlank()) {
                        // 尝试提取 app 名作简短描述
                        try {
                            JsonNode cardJson = jsonMapper.readTree(jsonData);
                            String app = cardJson.path("app").asText("");
                            String desc = cardJson.path("desc").asText("");
                            if (!app.isBlank()) parsedContent.append(": ").append(app);
                            if (!desc.isBlank()) parsedContent.append(" - ").append(desc);
                        } catch (Exception e) {
                            // 不是合法 JSON，截取前60字符
                            String snippet = jsonData.length() > 60 ? jsonData.substring(0, 60) + "..." : jsonData;
                            parsedContent.append(": ").append(snippet);
                        }
                    }
                    parsedContent.append("]");
                    break;

                // ── XML 卡片（富媒体/音乐分享等） ──
                case "xml":
                    parsedContent.append("[XML卡片]");
                    break;

                // ── 视频 ──
                case "video":
                    parsedContent.append("[视频]");
                    break;

                // ── 语音 ──
                case "record":
                    parsedContent.append("[语音]");
                    break;

                // ── 文件 ──
                case "file":
                    String fileName = data.path("name").asText("");
                    String fileId = data.path("file_id").asText("");
                    long fileSize = data.path("size").asLong(0);
                    int busid = data.path("busid").asInt(0);
                    String realUrl = data.path("url").asText("");
                    parsedContent.append("[文件");
                    if (!fileName.isBlank()) parsedContent.append(": ").append(fileName);
                    if (fileSize > 0) {
                        String sizeStr = fileSize > 1_000_000
                                ? String.format("%.1fMB", fileSize / 1_000_000.0)
                                : fileSize > 1_000 ? String.format("%.1fKB", fileSize / 1_000.0)
                                : fileSize + "B";
                        parsedContent.append(" ").append(sizeStr);
                    }
                    parsedContent.append("]");
                    // ★ 提取文件元数据，构建虚拟链接供 LLM 下载
                    if (!fileId.isBlank() || !realUrl.isBlank()) {
                        String virtualLink = buildVirtualFileLink(fileId, fileName, fileSize);
                        ChatMessageInput.FileAttachment fa = new ChatMessageInput.FileAttachment(
                                fileName, fileId, fileSize, busid, virtualLink,
                                realUrl.isBlank() ? null : realUrl);
                        fileAttachments.add(fa);
                        // 将虚拟链接注册到映射表，供 download_chat_file 工具查找
                        if (!fileId.isBlank()) {
                            NapcatFileLinkRegistry.register(virtualLink, fileId, busid, fileName, fileSize);
                        }
                    }
                    break;

                // ── Markdown（Napcat 扩展） ──
                case "markdown":
                    String mdContent = data.path("content").asText("");
                    if (!mdContent.isBlank()) {
                        // 直接取 markdown 原文，LLM 能读懂
                        parsedContent.append("[Markdown消息] ").append(mdContent);
                    } else {
                        parsedContent.append("[Markdown消息]");
                    }
                    break;

                // ── 未知类型 ──
                default:
                    log.debug("[NapcatAdapter] 未识别的消息段类型: {}", type);
                    parsedContent.append("[未知消息类型:").append(type).append("]");
                    break;
            }
        }

        // 构建富媒体摘要
        StringBuilder summary = new StringBuilder();
        if (imageCount > 0) summary.append("[图片x").append(imageCount).append("]");
        if (faceCount > 0) summary.append("[表情x").append(faceCount).append("]");
        if (hasForward) summary.append("[合并转发]");
        if (!atTargets.isEmpty()) summary.append("[@提及]");
        String richSummary = summary.toString();

        return new ParsedMessage(
                parsedContent.toString().trim(), quotedId,
                isAtMe, atTargets, hasForward,
                imageCount > 0, imageCount, faceCount,
                richSummary,
                fileAttachments
        );
    }

    /**
     * Napcat 来源过滤 — 支持黑名单/白名单双模式。
     *
     * @param sourceId 群号或用户QQ号（字符串形式）
     * @param sourceLabel 来源标签，用于日志（如 "qq_group:123456"）
     * @return true=放行, false=丢弃
     */
    private boolean applySourceFilter(String sourceId, String sourceLabel) {
        java.util.Set<String> filterIds = com.cna.config.ConfigsManager.NAPCAT_FILTER_GROUP_IDS;
        String mode = com.cna.config.ConfigsManager.NAPCAT_FILTER_MODE;

        // 列表为空时：两种模式都放行所有消息
        if (filterIds.isEmpty()) return true;

        boolean inList = filterIds.contains(sourceId);

        if ("whitelist".equals(mode)) {
            // 白名单模式：只放行列表中的来源
            if (!inList) {
                log.debug("[NapcatAdapter] 🔒 白名单模式：{} 不在 napcat.filter.groupIds 中，跳过录入", sourceLabel);
                return false;
            }
            log.debug("[NapcatAdapter] ✅ 白名单模式：{} 在允许列表中，放行", sourceLabel);
            return true;
        } else {
            // 黑名单模式（默认）：排除列表中的来源
            if (inList) {
                log.debug("[NapcatAdapter] 🚫 黑名单模式：{} 在 napcat.filter.groupIds 中，跳过录入", sourceLabel);
                return false;
            }
            return true;
        }
    }

    private void handleGroupMessage(JsonNode event, long senderId, ParsedMessage parsed,
                                     String senderCard, String senderGroupRole, String subType,
                                     boolean isPrivate) {
        try {
            long groupId = event.path("group_id").asLong();

            // ★ Napcat 来源过滤（支持黑名单/白名单双模式）
            if (!applySourceFilter(String.valueOf(groupId), "qq_group:" + groupId)) {
                return;
            }

            String groupName = getGroupNameSync(groupId);
            String senderName = getFriendNameSync(senderId);
            if (senderName.isBlank()) senderName = String.valueOf(senderId);

            System.out.println(String.format("[群聊|%s] %s: %s", groupName, senderName, parsed.content()));

            ChatMessageInput input = new ChatMessageInput(
                    "qq_group:" + groupId,
                    groupName,
                    "qqid:" + senderId,
                    senderName,
                    parsed.content(),
                    parsed.quotedMessageId(),
                    isPrivate,
                    parsed.isAtMe(),
                    parsed.atTargets(),
                    senderCard,
                    senderGroupRole,
                    subType,
                    parsed.hasForward(),
                    parsed.richSummary()
            );
            // 注入最近聊天历史，让 ActionLoop 拿到完整的带环境上下文
            input.setRecentHistory(ChatAdaptersManager.getHistory("qq_group:" + groupId, ConfigsManager.CHATHISTORY_VIEW_AMOUNT));
            // ★ 注入文件附件（含下载链接）
            if (!parsed.fileAttachments().isEmpty()) {
                input.setFileAttachments(parsed.fileAttachments());
            }
            Main.offerInput(input, "QQ群:" + groupId);
        } catch (Throwable t) {
            log.error("[拦截追踪] 群消息推入主线队列失败", t);
        }
    }

    private void handlePrivateMessage(JsonNode event, long senderId, ParsedMessage parsed,
                                       String senderCard, String senderGroupRole, String subType) {
        try {
            // ★ Napcat 来源过滤（支持黑名单/白名单双模式）
            if (!applySourceFilter(String.valueOf(senderId), "qq_private:" + senderId)) {
                return;
            }

            String senderName = getFriendNameSync(senderId);
            if (senderName.isBlank()) senderName = String.valueOf(senderId);

            System.out.println(String.format("[私聊|%s] -> %s", senderName, parsed.content()));

            ChatMessageInput input = new ChatMessageInput(
                    "qq_private:" + senderId,
                    "QQ私聊",
                    "qqid:" + senderId,
                    senderName,
                    parsed.content(),
                    parsed.quotedMessageId(),
                    true,           // isPrivate
                    false,          // isAtMe — 私聊不涉及 @
                    parsed.atTargets(),
                    senderCard,
                    senderGroupRole,
                    subType,
                    parsed.hasForward(),
                    parsed.richSummary()
            );
            input.setRecentHistory(ChatAdaptersManager.getHistory("qq_private:" + senderId, ConfigsManager.CHATHISTORY_VIEW_AMOUNT));
            // ★ 注入文件附件（含下载链接）
            if (!parsed.fileAttachments().isEmpty()) {
                input.setFileAttachments(parsed.fileAttachments());
            }
            Main.offerInput(input, "QQ私聊:" + senderId);
        } catch (Throwable t) {
            log.error("[拦截追踪] 私聊消息推入主线队列失败", t);
        }
    }

    /** 构建虚拟文件链接 */
    private static String buildVirtualFileLink(String fileId, String fileName, long fileSize) {
        // 去掉 fileId 前后可能附带的斜杠，避免 napcat://file//xxx 双斜杠
        String cleanId = fileId.replaceAll("^/+|/+$", "");
        String safeName = fileName.replaceAll("[^a-zA-Z0-9._\\-\\u4e00-\\u9fff]", "_");
        return "napcat://file/" + cleanId + "/" + safeName;
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
                        String innerContent = parseMessageContent(contentNode, false, selfId).content();
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
                        String rawMessage = parseMessageContent(msgNode.path("message"), false, selfId).content();
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
                        String rawMessage = parseMessageContent(msgNode.path("message"), false, selfId).content();
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