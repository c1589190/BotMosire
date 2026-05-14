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
                "向工作区文件写入文本内容。支持两种模式：\n" +
                "- overwrite（默认）：创建新文件或完全覆盖已有文件。适合写短文件或第一次建立文件。\n" +
                "- append：在文件末尾追加内容，不影响已有内容。适合分段生成大文件时逐段追加。\n" +
                "单次写入上限 512KB。生成大文件时，建议先用 overwrite 写第一段，再多次 append 后续段落。\n" +
                "写入后可通过 send_workspace_file 发送给用户。");

        ObjectNode params = fn.putObject("parameters");
        params.put("type", "object");
        ObjectNode props = params.putObject("properties");

        ObjectNode path = props.putObject("path");
        path.put("type", "string");
        path.put("description", "文件的相对路径，例如 'report.md' 或 'data/result.csv'");

        ObjectNode content = props.putObject("content");
        content.put("type", "string");
        content.put("description", "要写入的文本内容");

        ObjectNode mode = props.putObject("mode");
        mode.put("type", "string");
        mode.put("description", "'overwrite'（默认，覆盖）或 'append'（追加）");

        params.putArray("required").add("path").add("content");
        return tool;
    }

    @Override
    public String execute(JsonNode arguments) {
        String path    = arguments.path("path").asText("").trim();
        String content = arguments.path("content").asText("");
        String mode    = arguments.path("mode").asText("overwrite").trim().toLowerCase();

        if (path.isBlank()) return "ERROR: 文件路径不能为空。";

        boolean append = "append".equals(mode);
        lastRecord = (append ? "追加" : "覆盖") + "写入工作区文件: [" + path + "]，共 " + content.length() + " 字符";
        return WorkSpaceManager.getInstance().write(path, content, append);
    }

    @Override
    public String getTextRecord() { return lastRecord; }
}
