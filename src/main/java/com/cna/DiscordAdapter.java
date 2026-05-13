package com.cna;

import com.cna.agent.AgentInput.ChatMessageInput;
import com.cna.agent.AgentInput.DefaultAgentInputUnit;
import com.cna.config.ConfigsManager;
import com.cna.llm.LLMAdapter;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.entities.channel.*;
import net.dv8tion.jda.api.entities.channel.attribute.*;
import net.dv8tion.jda.api.entities.channel.concrete.PrivateChannel;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Discord Adapter for BotMosire
 * 
 * 接收 Discord 消息 → 轉為 ChatMessageInput → 推入 Main.AgentInputTasksQueue
 * 發送時根據 target 前綴分流到對應頻道
 * 
 * source 格式：
 *   discord_dm:{userId}           — 私聊
 *   discord_guild:{guildId}:{channelId}  — 伺服器頻道
 * 
 * role 格式：
 *   discordid:{userId}            — 發送者
 */
@Slf4j
public class DiscordAdapter extends ListenerAdapter {

    private static final Logger logger = log;

    private JDA jda;
    private final String token;
    private volatile boolean connected = false;

    /** userId → channelId (for DM replies) */
    private final Map<String, Long> dmChannelCache = new ConcurrentHashMap<>();

    /** source 標識 → 最近的 channelId (for guild channel replies) */
    private final Map<String, Long> guildChannelCache = new ConcurrentHashMap<>();

    /** guildId 白名單（空 = 允許所有） */
    private final Set<Long> allowedGuilds;

    /** bot 自己不回覆自己 */
    private volatile long selfUserId = -1;

    /** Vision LLM，用於描述圖片附件 */
    private LLMAdapter visionLLM;

    /** 圖片描述 LRU 快取，key = attachment ID，最多 200 條 */
    private final Map<String, String> imageVisionCache = Collections.synchronizedMap(
            new LinkedHashMap<String, String>(100, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > 200;
                }
            }
    );

    /** 圖片下載 + Vision 呼叫專用執行緒，避免阻塞 JDA event thread */
    private final ExecutorService imageProcessingExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "discord-vision");
        t.setDaemon(true);
        return t;
    });

    /** 圖片下載用 HTTP client（timeout 比 LLM client 短） */
    private static final OkHttpClient IMAGE_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();

    /** 抓取文字訊息中直接貼入的圖片 URL（Discord CDN、一般圖片連結） */
    private static final Pattern IMAGE_URL_PATTERN = Pattern.compile(
            "https?://\\S+\\.(?:gif|png|jpg|jpeg|webp)(?:\\?\\S*)?",
            Pattern.CASE_INSENSITIVE
    );

    // ── 構造函數 ────────────────────────────────────────────────────────

    public DiscordAdapter(String token) {
        this.token = token;
        this.allowedGuilds = parseAllowedGuilds(
            com.cna.config.ConfigsManager.getConfig("discord.allowedGuilds", "")
        );
        logger.info("[DiscordAdapter] 初始化，token 前綴: {}..., 白名單伺服器: {}",
            token.length() > 10 ? token.substring(0, 10) : "???",
            allowedGuilds.isEmpty() ? "全部" : allowedGuilds);

        try {
            this.visionLLM = new LLMAdapter(ConfigsManager.VISION_MODEL);
            logger.info("[DiscordAdapter] 視覺模型已掛載。");
        } catch (Exception e) {
            logger.warn("[DiscordAdapter] 視覺模型初始化失敗，圖片描述將不可用: {}", e.getMessage());
        }
    }

    // ── 連線 / 斷線 ─────────────────────────────────────────────────────

    public void connect() {
        try {
            JDABuilder builder = JDABuilder.createDefault(token)
                .enableIntents(
                    GatewayIntent.GUILD_MESSAGES,
                    GatewayIntent.MESSAGE_CONTENT,
                    GatewayIntent.GUILD_MESSAGE_REACTIONS,
                    GatewayIntent.DIRECT_MESSAGES,
                    GatewayIntent.GUILD_MEMBERS
                )
                .disableCache(CacheFlag.ACTIVITY, CacheFlag.VOICE_STATE, CacheFlag.SCHEDULED_EVENTS)
                .addEventListeners(this);

            jda = builder.build();
            jda.awaitReady();
            this.selfUserId = jda.getSelfUser().getIdLong();
            this.connected = true;
            logger.info("[DiscordAdapter] ✅ Discord Gateway 連線成功，Bot: {} ({})",
                jda.getSelfUser().getName(), selfUserId);

        } catch (Exception e) {
            logger.error("[DiscordAdapter] ❌ 連線失敗: {}", e.getMessage(), e);
            throw new RuntimeException("Discord adapter connect failed", e);
        }
    }

    public void disconnect() {
        imageProcessingExecutor.shutdown();
        if (jda != null) {
            connected = false;
            jda.shutdown();
            logger.info("[DiscordAdapter] 已斷開連線");
        }
    }

    public boolean isConnected() {
        return connected && jda != null && jda.getStatus() == JDA.Status.CONNECTED;
    }

    // ── JDA 事件 ────────────────────────────────────────────────────────

    @Override
    public void onReady(ReadyEvent event) {
        logger.info("[DiscordAdapter] onReady — 已連接至 {} 個伺服器",
            event.getJDA().getGuilds().size());
        for (Guild guild : event.getJDA().getGuilds()) {
            logger.info("  └─ {} ({})", guild.getName(), guild.getId());
        }
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (!event.isFromType(ChannelType.PRIVATE) && !event.isFromType(ChannelType.TEXT)) {
            return; // 忽略其他類型
        }

        Message message = event.getMessage();
        User author = event.getAuthor();

        // 忽略 Bot 自己
        if (author.getIdLong() == selfUserId) {
            return;
        }

        // 白名單檢查
        if (event.isFromType(ChannelType.TEXT)) {
            long guildId = event.getGuild().getIdLong();
            if (!allowedGuilds.isEmpty() && !allowedGuilds.contains(guildId)) {
                logger.debug("[DiscordAdapter] 忽略來自未白名單伺服器 {} 的消息", guildId);
                return;
            }
        }

        // ── 構建 source / role ───────────────────────────────────────────
        String source;
        String sourceName;
        final String role = "discordid:" + author.getId();
        final String authorName = author.getName();

        if (event.isFromType(ChannelType.PRIVATE)) {
            source = "discord_dm:" + author.getId();
            sourceName = "Discord私聊";
            dmChannelCache.put(author.getId(), event.getChannel().getIdLong());
        } else {
            long guildId = event.getGuild().getIdLong();
            long channelId = event.getChannel().getIdLong();
            source = "discord_guild:" + guildId + ":" + channelId;
            sourceName = event.getGuild().getName() + "#" + event.getChannel().getName();
            guildChannelCache.put(source, channelId);
        }

        final String finalSource = source;
        final String finalSourceName = sourceName;

        // ── 組裝基礎文字 ─────────────────────────────────────────────────
        StringBuilder contentBuilder = new StringBuilder(message.getContentRaw().trim());

        // 提前宣告，embed loop 和文字掃描都會往這裡加
        final List<String> extraImageUrls = new ArrayList<>();   // embed thumbnail + 文字貼入的 URL
        final Map<String, String> urlToCacheKey = new LinkedHashMap<>();

        // Tenor / Giphy GIF 以 Embed 形式存在，不在 Attachments 裡
        for (MessageEmbed embed : message.getEmbeds()) {
            String embedTypeName = embed.getType() != null ? embed.getType().name() : "";
            if ("GIFV".equals(embedTypeName) || "IMAGE".equals(embedTypeName)) {
                // 嘗試拿 thumbnail URL，送給 Vision 描述（比單純標注 title 更有用）
                String thumbUrl = null;
                if (embed.getThumbnail() != null) thumbUrl = embed.getThumbnail().getUrl();
                if (thumbUrl == null && embed.getImage() != null) thumbUrl = embed.getImage().getUrl();

                if (thumbUrl != null) {
                    extraImageUrls.add(thumbUrl);
                    urlToCacheKey.put(thumbUrl, thumbUrl);
                    contentBuilder.append(" [GIF]"); // 佔位符，Vision 描述會附在後面
                } else {
                    String title = embed.getTitle();
                    contentBuilder.append(" [GIF: ").append(title != null ? title : "动图").append("]");
                }
            }
        }

        // 非圖片附件（PDF、影片等）只記名稱
        for (Message.Attachment att : message.getAttachments()) {
            if (!att.isImage()) {
                contentBuilder.append(" [附件: ").append(att.getFileName()).append("]");
            }
        }

        // ── 掃描文字中直接貼入的圖片 URL（貼 CDN 連結而非上傳檔案時） ──────
        Matcher urlMatcher = IMAGE_URL_PATTERN.matcher(contentBuilder.toString());
        StringBuffer cleanedText = new StringBuffer();
        while (urlMatcher.find()) {
            String url = urlMatcher.group();
            extraImageUrls.add(url);
            urlToCacheKey.put(url, url);
            urlMatcher.appendReplacement(cleanedText, "[图片]");
        }
        urlMatcher.appendTail(cleanedText);
        if (!extraImageUrls.isEmpty()) {
            contentBuilder = new StringBuilder(cleanedText.toString());
        }

        // ── 圖片附件分流 ─────────────────────────────────────────────────
        List<Message.Attachment> imageAttachments = message.getAttachments().stream()
                .filter(Message.Attachment::isImage)
                .collect(Collectors.toList());

        // 合併：上傳的附件圖片 + embed thumbnail + 文字貼入的圖片
        final List<String> allImageUrls = new ArrayList<>();
        imageAttachments.forEach(att -> {
            allImageUrls.add(att.getUrl());
            urlToCacheKey.put(att.getUrl(), att.getId()); // 附件用 ID 當 cache key
        });
        allImageUrls.addAll(extraImageUrls);

        if (!allImageUrls.isEmpty()) {
            // 有圖片 → 丟給背景執行緒處理，避免阻塞 JDA event thread
            final String baseText = contentBuilder.toString();
            imageProcessingExecutor.submit(() -> {
                StringBuilder full = new StringBuilder(baseText);
                for (String imgUrl : allImageUrls) {
                    String cacheKey = urlToCacheKey.get(imgUrl);
                    String desc = imageVisionCache.get(cacheKey);
                    if (desc == null) {
                        String base64 = downloadImageToBase64(imgUrl);
                        if (base64 != null && visionLLM != null) {
                            try {
                                desc = visionLLM.generateVisionDescription(
                                        "请简短客观地描述这张图片，若有明显文字请提取。", base64);
                                imageVisionCache.put(cacheKey, desc);
                            } catch (Exception e) {
                                logger.warn("[DiscordAdapter] Vision 描述失敗: {}", e.getMessage());
                                desc = "图片解析失败";
                            }
                        } else {
                            desc = "图片下载失败";
                        }
                    }
                    full.append(" [图片(AI解析): ").append(desc).append("]");
                }
                String finalContent = full.toString().trim();
                if (finalContent.isEmpty()) return;
                ChatMessageInput input = new ChatMessageInput(
                        finalSource, finalSourceName, role, authorName, finalContent);
                Main.AgentInputTasksQueue.offer(input);
                logger.info("[DiscordAdapter] 📥(視覺) 推入訊息: {} / {} → {}",
                        finalSourceName, authorName, truncate(finalContent, 60));
            });
        } else {
            // 無圖片 → 直接推入
            String finalContent = contentBuilder.toString().trim();
            if (finalContent.isEmpty()) return;
            ChatMessageInput input = new ChatMessageInput(
                    source, sourceName, role, authorName, finalContent);
            Main.AgentInputTasksQueue.offer(input);
            logger.info("[DiscordAdapter] 📥 推入訊息: {} / {} → {}",
                    sourceName, authorName, truncate(finalContent, 50));
        }

        maybeAcknowledge(event, message.getContentRaw());
    }

    // ── 發送消息 ────────────────────────────────────────────────────────

    /**
     * 發送文字訊息到指定 Discord 目標
     * 
     * @param targetNamespace  格式: discord_dm:{userId}  或  discord_guild:{guildId}:{channelId}
     * @param text             要發送的文字
     * @return                 成功訊息或錯誤描述
     */
    public String sendMessage(String targetNamespace, String text) {
        if (!connected || jda == null) {
            return "ERROR: Discord adapter not connected";
        }

        try {
            if (targetNamespace.startsWith("discord_dm:")) {
                String userId = targetNamespace.substring("discord_dm:".length());
                return sendDm(userId, text);

            } else if (targetNamespace.startsWith("discord_guild:")) {
                // 格式: discord_guild:{guildId}:{channelId}
                String[] parts = targetNamespace.split(":");
                if (parts.length < 3) {
                    return "ERROR: discord_guild format requires guildId:channelId";
                }
                long guildId = Long.parseLong(parts[1]);
                long channelId = Long.parseLong(parts[2]);
                return sendGuild(channelId, text);

            } else {
                return "ERROR: unknown Discord target format: " + targetNamespace;
            }
        } catch (NumberFormatException e) {
            return "ERROR: invalid ID number in target: " + targetNamespace;
        } catch (Exception e) {
            logger.error("[DiscordAdapter] sendMessage failed: {}", e.getMessage(), e);
            return "ERROR: " + e.getMessage();
        }
    }

    private String sendDm(String userId, String text) {
        Long cachedChannelId = dmChannelCache.get(userId);
        if (cachedChannelId != null) {
            try {
                MessageChannel channel = jda.getChannelById(MessageChannel.class, cachedChannelId);
                if (channel != null) {
                    channel.sendMessage(text).queue();
                    logger.info("[DiscordAdapter] 📤 DM → {}: {}", userId, truncate(text, 30));
                    return "SUCCESS";
                }
            } catch (Exception ignored) {}
        }

        // 動態建立 DM
        try {
            User user = jda.retrieveUserById(userId).complete();
            if (user == null) return "ERROR: user not found: " + userId;

            PrivateChannel channel = user.openPrivateChannel().complete();
            dmChannelCache.put(userId, channel.getIdLong());
            channel.sendMessage(text).queue();
            logger.info("[DiscordAdapter] 📤 DM(新建) → {}: {}", userId, truncate(text, 30));
            return "SUCCESS";
        } catch (Exception e) {
            logger.error("[DiscordAdapter] DM 發送失敗: {}", e.getMessage());
            return "ERROR: DM failed: " + e.getMessage();
        }
    }

    private String sendGuild(long channelId, String text) {
        try {
            MessageChannel channel = jda.getChannelById(MessageChannel.class, channelId);
            if (channel == null) {
                return "ERROR: channel not found: " + channelId;
            }
            channel.sendMessage(text).queue();
            logger.info("[DiscordAdapter] 📤 Guild#{}: {}", channelId, truncate(text, 30));
            return "SUCCESS";
        } catch (Exception e) {
            logger.error("[DiscordAdapter] Guild 發送失敗: {}", e.getMessage());
            return "ERROR: " + e.getMessage();
        }
    }

    // ── Typing Indicator ────────────────────────────────────────────────

    private static final ScheduledExecutorService typingScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "discord-typing");
                t.setDaemon(true);
                return t;
            });

    /**
     * 開始向目標發送 typing 指示器，每 8 秒自動續送（Discord 顯示 10 秒後消失）。
     * 回傳 Runnable，呼叫後停止。
     */
    public Runnable startTyping(String targetNamespace) {
        if (!connected || jda == null || targetNamespace == null) return () -> {};
        MessageChannel channel = resolveChannel(targetNamespace);
        if (channel == null) return () -> {};

        ScheduledFuture<?> future = typingScheduler.scheduleAtFixedRate(
                () -> channel.sendTyping().queue(null, e -> {}),
                0, 8, TimeUnit.SECONDS);
        return () -> future.cancel(false);
    }

    private MessageChannel resolveChannel(String targetNamespace) {
        try {
            if (targetNamespace.startsWith("discord_dm:")) {
                String userId = targetNamespace.substring("discord_dm:".length());
                Long channelId = dmChannelCache.get(userId);
                if (channelId != null) return jda.getChannelById(MessageChannel.class, channelId);
            } else if (targetNamespace.startsWith("discord_guild:")) {
                String[] parts = targetNamespace.split(":");
                if (parts.length >= 3) return jda.getChannelById(MessageChannel.class, Long.parseLong(parts[2]));
            }
        } catch (Exception e) {
            logger.warn("[DiscordAdapter] resolveChannel 失敗: {}", e.getMessage());
        }
        return null;
    }

    // ── 圖片下載 ────────────────────────────────────────────────────────

    private String downloadImageToBase64(String imageUrl) {
        Request request = new Request.Builder()
                .url(imageUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Accept", "image/gif,image/webp,image/png,image/jpeg,*/*")
                .header("Referer", "https://discord.com/")
                .build();
        try (Response response = IMAGE_CLIENT.newCall(request).execute()) {
            if (response.isSuccessful() && response.body() != null) {
                return Base64.getEncoder().encodeToString(response.body().bytes());
            }
            logger.warn("[DiscordAdapter] 圖片下載失敗 HTTP {} URL: {}", response.code(), imageUrl);
        } catch (Exception e) {
            logger.warn("[DiscordAdapter] 圖片下載異常: {} msg: {}", imageUrl, e.getMessage());
        }
        return null;
    }

    // ── 歷史查詢 ────────────────────────────────────────────────────────

    /**
     * 拉取 Discord 頻道的最近 N 條訊息（REST API，無需額外 Intent）
     */
    public List<String> getChannelHistorySync(long channelId, int count) {
        List<String> result = new ArrayList<>();
        if (!connected || jda == null) return result;
        try {
            MessageChannel channel = jda.getChannelById(MessageChannel.class, channelId);
            if (channel == null) {
                logger.warn("[DiscordAdapter] getChannelHistorySync: 找不到頻道 {}", channelId);
                return result;
            }
            List<net.dv8tion.jda.api.entities.Message> messages =
                    channel.getHistory().retrievePast(Math.min(count, 100)).complete();
            Collections.reverse(messages);
            for (net.dv8tion.jda.api.entities.Message msg : messages) {
                result.add(msg.getAuthor().getName() + ": " + msg.getContentRaw());
            }
        } catch (Exception e) {
            logger.error("[DiscordAdapter] 获取频道历史失败: {}", e.getMessage());
        }
        return result;
    }

    /**
     * 拉取與指定 userId 的 DM 最近 N 條訊息
     */
    public List<String> getDmHistorySync(String userId, int count) {
        List<String> result = new ArrayList<>();
        if (!connected || jda == null) return result;
        try {
            Long channelId = dmChannelCache.get(userId);
            if (channelId == null) {
                logger.warn("[DiscordAdapter] getDmHistorySync: 無 DM 緩存 userId={}", userId);
                return result;
            }
            return getChannelHistorySync(channelId, count);
        } catch (Exception e) {
            logger.error("[DiscordAdapter] 获取DM历史失败: {}", e.getMessage());
        }
        return result;
    }

    // ── 工具方法 ────────────────────────────────────────────────────────

    /** 解析白名單 guild ID */
    private Set<Long> parseAllowedGuilds(String config) {
        Set<Long> result = new HashSet<>();
        if (config == null || config.trim().isEmpty()) {
            return result;
        }
        for (String part : config.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                try {
                    result.add(Long.parseLong(trimmed));
                } catch (NumberFormatException e) {
                    logger.warn("[DiscordAdapter] 無法解析 guild ID: {}", trimmed);
                }
            }
        }
        return result;
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    /** 簡單回 ACK（可擴展） */
    private void maybeAcknowledge(MessageReceivedEvent event, String content) {
        // 目前只做日誌記錄，暫不自動回應
    }
}
