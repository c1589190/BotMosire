package com.cna.agent.AgentTool;

import com.cna.workspace.WorkSpaceManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class ListFiles implements DefaultAgentToolUnit {

    private static final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String getName() { return "list_workspace"; }

    @Override
    public ObjectNode getToolDefinition() {
        ObjectNode tool = mapper.createObjectNode();
        tool.put("type", "function");
        ObjectNode fn = tool.putObject("function");
        fn.put("name", getName());
        fn.put("description",
                "列出工作区目录中的所有文件和子目录。" +
                "不传 path 参数则列出根目录，传入子目录路径则列出该目录。");

        ObjectNode params = fn.putObject("parameters");
        params.put("type", "object");
        ObjectNode props = params.putObject("properties");

        ObjectNode path = props.putObject("path");
        path.put("type", "string");
        path.put("description", "要列出的目录路径（相对路径），留空则列出根目录");

        return tool;
    }

    @Override
    public String execute(JsonNode arguments) {
        String path = arguments.path("path").asText("").trim();
        return WorkSpaceManager.getInstance().list(path.isBlank() ? null : path);
    }

    @Override
    public String getTextRecord() { return "列出工作区目录内容"; }
}
