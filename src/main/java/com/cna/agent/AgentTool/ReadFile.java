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
                "读取工作区中指定文件的内容。超过 50KB 的文件会被截断。" +
                "如果不知道有哪些文件，先用 list_workspace 查看目录结构。");

        ObjectNode params = fn.putObject("parameters");
        params.put("type", "object");
        ObjectNode props = params.putObject("properties");

        ObjectNode path = props.putObject("path");
        path.put("type", "string");
        path.put("description", "文件的相对路径，例如 'notes/todo.md'");

        params.putArray("required").add("path");
        return tool;
    }

    @Override
    public String execute(JsonNode arguments) {
        String path = arguments.path("path").asText("").trim();
        if (path.isBlank()) return "ERROR: 文件路径不能为空。";
        lastRecord = "读取工作区文件: [" + path + "]";
        return WorkSpaceManager.getInstance().read(path);
    }

    @Override
    public String getTextRecord() { return lastRecord; }
}
