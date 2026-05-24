package com.cna.workspace;

import com.cna.Main;
import com.cna.agent.AgentInput.WebEventInput;
import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import com.cna.config.ConfigsManager;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

@Slf4j
public class WebServer {

    private static final int RATE_PER_MIN = 30;
    private static final int MAX_BODY_BYTES = 64 * 1024;

    private final Javalin app;
    private final ConcurrentLinkedQueue<LlmCommand> commandQueue = new ConcurrentLinkedQueue<>();
    private final String indexHtmlAbsolutePath;
    private final Object indexHtmlLock = new Object(); // 防止並發 update_html 互蓋
    private final Map<String, Deque<Long>> ipBuckets = new ConcurrentHashMap<>();
    private final String webhookToken = ConfigsManager.getConfig("web.webhookToken", "");

    public WebServer(String workspaceAbsolutePath) {
        String websitePath = workspaceAbsolutePath + "/website";
        this.indexHtmlAbsolutePath = websitePath + "/index.html";

        app = Javalin.create(config -> {
            config.staticFiles.add(staticFiles -> {
                staticFiles.hostedPath = "/";
                staticFiles.directory = websitePath;
                staticFiles.location = Location.EXTERNAL;
            });
            config.bundledPlugins.enableCors(cors -> cors.addRule(it -> it.anyHost()));
            config.http.maxRequestSize = MAX_BODY_BYTES; // server 層直接拒絕過大 payload，不先讀進記憶體
        });

        setupRoutes();
        startIpBucketCleanup();
    }

    private void startIpBucketCleanup() {
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ip-bucket-cleanup");
            t.setDaemon(true);
            return t;
        }).scheduleAtFixedRate(() -> {
            long now = System.currentTimeMillis();
            ipBuckets.entrySet().removeIf(e -> {
                synchronized (e.getValue()) {
                    return e.getValue().isEmpty() || now - e.getValue().peekLast() > 30 * 60 * 1000;
                }
            });
        }, 30, 30, java.util.concurrent.TimeUnit.MINUTES);
    }

    public void start(int port) {
        app.start(port);
        log.info("[WebServer] 动态网页服务器已启动，访问地址: http://localhost:{}", port);
    }

    public void stop() { app.stop(); }

    private boolean rateLimited(String ip) {
        long now = System.currentTimeMillis();
        Deque<Long> bucket = ipBuckets.computeIfAbsent(ip, k -> new ArrayDeque<>());
        synchronized (bucket) {
            while (!bucket.isEmpty() && now - bucket.peekFirst() > 60_000) bucket.pollFirst();
            if (bucket.size() >= RATE_PER_MIN) return true;
            bucket.addLast(now);
            return false;
        }
    }

    private void setupRoutes() {
        app.post("/api/agent/webhook", ctx -> {
            try {
                // Bearer token 驗證（token 為空時跳過，方便本機開發）
                if (!webhookToken.isBlank()) {
                    String auth = ctx.header("Authorization");
                    if (!("Bearer " + webhookToken).equals(auth)) {
                        ctx.status(401).json(new ResponsePayload("ERROR", "Unauthorized"));
                        return;
                    }
                }
                // per-IP rate limit
                String ip = ctx.ip();
                if (rateLimited(ip)) {
                    ctx.status(429).json(new ResponsePayload("ERROR", "Too Many Requests"));
                    return;
                }
                // payload 大小上限已在 Javalin config.http.maxRequestSize 層處理，不需再讀 body 檢查
                String rawBody = ctx.body();
                if (rawBody == null || rawBody.isBlank()) {
                    ctx.status(400).json(new ResponsePayload("ERROR", "请求体不能为空"));
                    return;
                }
                Main.offerInput(new WebEventInput(rawBody, ip), "WebHook:" + ip);
                log.info("[WebServer] 前端事件已投入感知总线: {}", rawBody);
                ctx.json(new ResponsePayload("SUCCESS", "事件已送达主脑"));
            } catch (Exception e) {
                log.error("[WebServer] 处理前端 webhook 失败", e);
                ctx.status(500).json(new ResponsePayload("ERROR", "内部处理异常"));
            }
        });

        app.get("/api/llm/command", ctx -> {
            LlmCommand cmd = commandQueue.poll();
            if (cmd != null) ctx.json(cmd);
            else ctx.status(204);
        });

        app.post("/api/llm/command", ctx -> {
            try {
                LlmCommand cmd = ctx.bodyAsClass(LlmCommand.class);

                if ("update_html".equals(cmd.getAction()) || "append_html".equals(cmd.getAction())) {
                    boolean patched = patchHtmlFile(cmd);
                    if (!patched) {
                        ctx.status(400).json(new ResponsePayload("ERROR",
                            "未找到目标节点 [" + cmd.getTarget() + "]，请先调用 read_file 查看 website/index.html 的当前 DOM 结构，确认 CSS 选择器正确后重试。"));
                        return;
                    }
                }

                commandQueue.offer(cmd);
                log.info("[WebServer] LLM 指令已推送: action={}, target={}", cmd.getAction(), cmd.getTarget());
                ctx.json(new ResponsePayload("SUCCESS", "指令已执行，HTML 改动已持久化到磁盘并推送至前端实时渲染。"));
            } catch (Exception e) {
                log.error("[WebServer] 解析 LLM 指令失败", e);
                ctx.status(400).json(new ResponsePayload("ERROR", "处理异常: " + e.getMessage()));
            }
        });
    }

    private boolean patchHtmlFile(LlmCommand cmd) {
        synchronized (indexHtmlLock) { // 防止並發 update_html 互蓋
            try {
                File input = new File(indexHtmlAbsolutePath);
                if (!input.exists()) { log.error("[WebServer] 找不到文件: {}", indexHtmlAbsolutePath); return false; }
                Document doc = Jsoup.parse(input, "UTF-8");
                doc.outputSettings().prettyPrint(false);
                Element targetElement = doc.selectFirst(cmd.getTarget());
                if (targetElement != null) {
                    if ("update_html".equals(cmd.getAction())) targetElement.html(cmd.getHtml());
                    else if ("append_html".equals(cmd.getAction())) targetElement.append(cmd.getHtml());
                    Files.writeString(Path.of(indexHtmlAbsolutePath), doc.outerHtml(), StandardCharsets.UTF_8);
                    log.info("[WebServer] 已持久化改动到 index.html, 目标节点: {}", cmd.getTarget());
                    return true;
                } else {
                    log.warn("[WebServer] 未能在 index.html 找到目标节点: {}", cmd.getTarget());
                    return false;
                }
            } catch (Exception e) {
                log.error("[WebServer] Jsoup 持久化 index.html 失败", e);
                return false;
            }
        }
    }

    @Data
    public static class LlmCommand {
        private String action;
        private String target;
        private String html;
        private String code;
    }

    @Data
    public static class ResponsePayload {
        private String status;
        private String message;
        public ResponsePayload(String status, String message) {
            this.status = status;
            this.message = message;
        }
    }
}
