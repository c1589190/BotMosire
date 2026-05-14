package com.cna.agent.AgentTool.io;

import com.cna.ChatAdaptersManager;
import com.cna.Main;
import com.cna.agent.AgentTool.DefaultAgentToolUnit;
import com.cna.config.ToolPromptsManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;

@Slf4j
public class SendFileToChat implements DefaultAgentToolUnit {

    private final ObjectMapper mapper = new ObjectMapper();
    private String lastRecord = "调用文件发送工具";

    // 设置平台发送文件的硬性上限，例如 25MB
    private static final long MAX_FILE_SIZE_BYTES = 25 * 1024 * 1024;

    @Override
    public String getName() { return "send_workspace_file"; }

    @Override
    public ObjectNode getToolDefinition() {
        ObjectNode tool = mapper.createObjectNode();
        tool.put("type", "function");

        ToolPromptsManager p = new ToolPromptsManager(this.getClass().getSimpleName());

        ObjectNode fn = tool.putObject("function");
        fn.put("name", getName());
        fn.put("description", p.getToolDescription());

        ObjectNode props = fn.putObject("parameters").put("type", "object").putObject("properties");

        props.putObject("file_path")
                .put("type", "string")
                .put("description", p.getCustomDescription("file_path"));

        props.putObject("namespace")
                .put("type", "string")
                .put("description", p.getCustomDescription("namespace"));

        fn.with("parameters").putArray("required").add("file_path").add("namespace");
        return tool;
    }

    @Override
    public String execute(JsonNode arguments) {
        String filePath = arguments.path("file_path").asText("").trim();
        String namespace = arguments.path("namespace").asText("").trim();

        if (filePath.isBlank() || namespace.isBlank()) {
            return "ERROR: 文件路径与目标 namespace 均不能为空。";
        }

        // 🌟 1. 使用 WorkspaceManager 统一解析路径（自动处理相对路径、当前目录和防穿越）
        Path resolved = Main.workspaceManager.resolve(filePath);

        if (resolved == null) return "ERROR: 越权访问拦截，禁止操作工作区外的文件。";
        if (!Files.exists(resolved)) return "ERROR: 文件 [" + filePath + "] 不存在，请先使用 list_workspace 确认。";
        if (Files.isDirectory(resolved)) return "ERROR: 只能发送具体文件，不能发送文件夹。";

        try {
            // 🌟 2. 文件体积防爆盾
            long fileSize = Files.size(resolved);
            if (fileSize > MAX_FILE_SIZE_BYTES) {
                return String.format("ERROR: 文件过大 (%.2f MB)，超出了聊天平台允许的最大发送限制 (25 MB)。",
                        fileSize / (1024.0 * 1024.0));
            }

            lastRecord = "将本地文件 [" + filePath + "] 发送至节点 [" + namespace + "]";
            log.info("[Tool][SendFile] {}", lastRecord);

            String result = ChatAdaptersManager.sendFile(namespace, resolved);
            if (result != null && result.startsWith("SUCCESS")) {
                return "SUCCESS: 文件 [" + filePath + "] 已发送至 " + namespace;
            }
            return result;

        } catch (Exception e) {
            log.error("文件发送/读取异常", e);
            return "ERROR: 底层发送接口异常 - " + e.getMessage();
        }
    }

    @Override
    public String getTextRecord() { return lastRecord; }
}