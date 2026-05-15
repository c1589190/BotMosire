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

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentLinkedQueue;

@Slf4j
public class WebServer {

    private final Javalin app;
    private final ConcurrentLinkedQueue<LlmCommand> commandQueue = new ConcurrentLinkedQueue<>();
    private final String indexHtmlAbsolutePath; // 记录 index.html 的绝对路径

    public WebServer(String workspaceAbsolutePath) {
        String websitePath = workspaceAbsolutePath + "/website";
        this.indexHtmlAbsolutePath = websitePath + "/index.html";

        app = Javalin.create(config -> {
            config.staticFiles.add(staticFiles -> {
                staticFiles.hostedPath = "/";
                staticFiles.directory = websitePath;
                staticFiles.location = Location.EXTERNAL;
            });
            config.bundledPlugins.enableCors(cors -> {
                cors.addRule(it -> it.anyHost());
            });
        });

        setupRoutes();
    }

    public void start(int port) {
        app.start(port);
        log.info("[WebServer] 动态网页服务器已启动，访问地址: http://localhost:{}", port);
    }

    public void stop() {
        app.stop();
    }

    private void setupRoutes() {

        // =========================================================
        // 【标准感知通道】前端事件 -> 全局感知总线
        // =========================================================
        app.post("/api/agent/webhook", ctx -> {
            try {
                String rawBody = ctx.body();
                String ip = ctx.ip();

                if (rawBody == null || rawBody.isBlank()) {
                    ctx.status(400).json(new ResponsePayload("ERROR", "请求体不能为空"));
                    return;
                }

                WebEventInput webEvent = new WebEventInput(rawBody, ip);
                Main.AgentInputTasksQueue.offer(webEvent);
                log.info("[WebServer] 成功捕获前端事件并已投入全局感知总线: {}", rawBody);

                ctx.json(new ResponsePayload("SUCCESS", "事件已送达主脑"));
            } catch (Exception e) {
                log.error("[WebServer] 处理前端 webhook 失败", e);
                ctx.status(500).json(new ResponsePayload("ERROR", "内部处理异常"));
            }
        });

        // =========================================================
        // 【下发通道】前端轮询获取指令
        // =========================================================
        app.get("/api/llm/command", ctx -> {
            LlmCommand cmd = commandQueue.poll();
            if (cmd != null) {
                ctx.json(cmd);
            } else {
                ctx.status(204);
            }
        });

        // =========================================================
        // 【接收 Agent 指令】持久化修改文件并推送到队列
        // =========================================================
        app.post("/api/llm/command", ctx -> {
            try {
                LlmCommand cmd = ctx.bodyAsClass(LlmCommand.class);

                // 对 HTML 修改指令先持久化到磁盘，再推入前端实时渲染队列
                if ("update_html".equals(cmd.getAction()) || "append_html".equals(cmd.getAction())) {
                    boolean patched = patchHtmlFile(cmd);
                    if (!patched) {
                        ctx.status(400).json(new ResponsePayload("ERROR",
                            "未找到目标节点 [" + cmd.getTarget() + "]，请先调用 read_file 查看 website/index.html 的当前 DOM 结构，确认 CSS 选择器正确后重试。"));
                        return;
                    }
                }

                commandQueue.offer(cmd);
                log.info("[WebServer] 收到 LLM 指令并已推送到前端: action={}, target={}", cmd.getAction(), cmd.getTarget());
                ctx.json(new ResponsePayload("SUCCESS", "指令已执行，HTML 改动已持久化到磁盘并推送至前端实时渲染。"));
            } catch (Exception e) {
                log.error("[WebServer] 解析 LLM 指令失败", e);
                ctx.status(400).json(new ResponsePayload("ERROR", "处理异常: " + e.getMessage()));
            }
        });
    }

    /**
     * 使用 Jsoup 安全地局部修改 index.html
     * @return 是否成功找到目标并修改
     */
    private boolean patchHtmlFile(LlmCommand cmd) {
        try {
            File input = new File(indexHtmlAbsolutePath);
            if (!input.exists()) {
                log.error("[WebServer] 找不到文件: {}", indexHtmlAbsolutePath);
                return false;
            }

            // 解析 HTML
            Document doc = Jsoup.parse(input, "UTF-8");
            // 关闭美化，防止 Jsoup 破坏已有的代码缩进格式
            doc.outputSettings().prettyPrint(false);

            Element targetElement = doc.selectFirst(cmd.getTarget());
            if (targetElement != null) {
                if ("update_html".equals(cmd.getAction())) {
                    targetElement.html(cmd.getHtml()); // 替换节点内部所有内容
                } else if ("append_html".equals(cmd.getAction())) {
                    targetElement.append(cmd.getHtml()); // 在节点末尾追加内容
                }

                // 保存回文件
                Files.writeString(Path.of(indexHtmlAbsolutePath), doc.outerHtml(), StandardCharsets.UTF_8);
                log.info("[WebServer] 已成功将改动持久化到硬盘的 index.html, 目标节点: {}", cmd.getTarget());
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