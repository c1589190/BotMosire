package com.cna.agent.AgentTool;

import com.cna.workspace.WorkSpaceManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class ReadFile implements DefaultAgentToolUnit {

    private static final ObjectMapper mapper = new ObjectMapper();
    private String lastRecord = "调用文件读取工具";

    @Override
    public String getName() { return "read_workspace_file"; }

    @Override
    public ObjectNode getToolDefinition() {
        ObjectNode tool = mapper.createObjectNode();
        tool.put("type", "function");
        ObjectNode fn = tool.putObject("function");
        fn.put("name", getName());
        fn.put("description",
                "分段读取工作区中指定文件的内容。" +
                "每次最多返回 500 行，带行号显示。" +
                "对于大文件，先读前几百行理解结构，再按需继续读后续部分。" +
                "不传 offset/limit 默认从第 1 行读 500 行。" +
                "返回结果会告知文件总行数和剩余未读行数。");

        ObjectNode params = fn.putObject("parameters");
        params.put("type", "object");
        ObjectNode props = params.putObject("properties");

        ObjectNode path = props.putObject("path");
        path.put("type", "string");
        path.put("description", "文件的相对路径，例如 'notes/todo.md'");

        ObjectNode offset = props.putObject("offset");
        offset.put("type", "integer");
        offset.put("description", "从第几行开始读（从 1 起算），默认 1（从头开始）");

        ObjectNode limit = props.putObject("limit");
        limit.put("type", "integer");
        limit.put("description", "最多读取多少行，默认 500，最大 500");

        params.putArray("required").add("path");
        return tool;
    }

    @Override
    public String execute(JsonNode arguments) {
        String path = arguments.path("path").asText("").trim();
        if (path.isBlank()) return "ERROR: 文件路径不能为空。";

        int offset = arguments.path("offset").asInt(1);
        int limit  = arguments.path("limit").asInt(500);

        lastRecord = "读取工作区文件: [" + path + "] offset=" + offset + " limit=" + limit;
        return WorkSpaceManager.getInstance().read(path, offset, limit);
    }

    @Override
    public String getTextRecord() { return lastRecord; }
}
