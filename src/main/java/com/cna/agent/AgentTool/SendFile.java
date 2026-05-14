package com.cna.agent.AgentTool;

import com.cna.ChatAdaptersManager;
import com.cna.workspace.WorkSpaceManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;

@Slf4j
public class SendFile implements DefaultAgentToolUnit {

    private static final ObjectMapper mapper = new ObjectMapper();
    private String lastRecord = "调用文件发送工具";

    @Override
    public String getName() { return "send_workspace_file"; }

    @Override
    public ObjectNode getToolDefinition() {
        ObjectNode tool = mapper.createObjectNode();
        tool.put("type", "function");
        ObjectNode fn = tool.putObject("function");
        fn.put("name", getName());
        fn.put("description",
                "将工作区中的文件发送给指定的聊天目标（QQ群、QQ私聊、Discord）。" +
                "只能发送工作区内已存在的文件，使用 list_workspace 确认文件路径后再调用此工具。" +
                "namespace 格式与 send_chat_message 相同。");

        ObjectNode params = fn.putObject("parameters");
        params.put("type", "object");
        ObjectNode props = params.putObject("properties");

        ObjectNode filePath = props.putObject("file_path");
        filePath.put("type", "string");
        filePath.put("description", "工作区内的文件相对路径，例如 'report.md'");

        ObjectNode namespace = props.putObject("namespace");
        namespace.put("type", "string");
        namespace.put("description", "发送目标，格式如 'qq_group:123456'、'qq_private:789'、'discord_guild:...'");

        params.putArray("required").add("file_path").add("namespace");
        return tool;
    }

    @Override
    public String execute(JsonNode arguments) {
        String filePath = arguments.path("file_path").asText("").trim();
        String namespace = arguments.path("namespace").asText("").trim();

        if (filePath.isBlank()) return "ERROR: 文件路径不能为空。";
        if (namespace.isBlank()) return "ERROR: 发送目标不能为空。";

        Path resolved = WorkSpaceManager.getInstance().resolve(filePath);
        if (resolved == null) return "ERROR: 非法文件路径，禁止访问工作区外的文件。";
        if (!resolved.toFile().exists()) return "ERROR: 文件不存在: [" + filePath + "]，请先用 write_workspace_file 创建它。";
        if (resolved.toFile().isDirectory()) return "ERROR: 目标是目录，只能发送文件。";

        lastRecord = "发送工作区文件 [" + filePath + "] 至 [" + namespace + "]";
        log.info("[SendFile] {}", lastRecord);

        String result = ChatAdaptersManager.sendFile(namespace, resolved);
        if (result.startsWith("SUCCESS")) {
            return "SUCCESS: 文件 [" + filePath + "] 已成功发送至 " + namespace;
        }
        return result;
    }

    @Override
    public String getTextRecord() { return lastRecord; }
}
