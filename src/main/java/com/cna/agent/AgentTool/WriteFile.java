package com.cna.agent.AgentTool;

import com.cna.workspace.WorkSpaceManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class WriteFile implements DefaultAgentToolUnit {

    private static final ObjectMapper mapper = new ObjectMapper();
    private String lastRecord = "调用文件写入工具";

    @Override
    public String getName() { return "write_workspace_file"; }

    @Override
    public ObjectNode getToolDefinition() {
        ObjectNode tool = mapper.createObjectNode();
        tool.put("type", "function");
        ObjectNode fn = tool.putObject("function");
        fn.put("name", getName());
        fn.put("description",
                "在你的私人工作区（workspace）中创建或覆盖写入一个文本文件。" +
                "支持 .txt/.md/.json/.csv/.py/.java 等纯文本格式。" +
                "路径是相对于工作区根目录的相对路径，例如 'notes/todo.md'。" +
                "写入后可通过 send_workspace_file 工具将文件发送给用户。");

        ObjectNode params = fn.putObject("parameters");
        params.put("type", "object");
        ObjectNode props = params.putObject("properties");

        ObjectNode path = props.putObject("path");
        path.put("type", "string");
        path.put("description", "文件的相对路径，例如 'report.md' 或 'data/result.csv'");

        ObjectNode content = props.putObject("content");
        content.put("type", "string");
        content.put("description", "要写入文件的文本内容");

        params.putArray("required").add("path").add("content");
        return tool;
    }

    @Override
    public String execute(JsonNode arguments) {
        String path = arguments.path("path").asText("").trim();
        String content = arguments.path("content").asText("");
        if (path.isBlank()) return "ERROR: 文件路径不能为空。";

        lastRecord = "写入工作区文件: [" + path + "]，共 " + content.length() + " 字符";
        return WorkSpaceManager.getInstance().write(path, content);
    }

    @Override
    public String getTextRecord() { return lastRecord; }
}
