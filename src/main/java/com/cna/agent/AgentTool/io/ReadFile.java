package com.cna.agent.AgentTool.io;

import com.cna.Main;
import com.cna.agent.AgentTool.DefaultAgentToolUnit;
import com.cna.config.ToolPromptsManager;
import com.cna.workspace.WorkSpaceManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class ReadFile implements DefaultAgentToolUnit {

    private final ObjectMapper mapper = new ObjectMapper();
    private String lastRecord = "调用文件读取工具";

    @Override
    public String getName() { return "read_workspace_file"; }

    @Override
    public ObjectNode getToolDefinition() {
        ObjectNode tool = mapper.createObjectNode();
        tool.put("type", "function");

        ToolPromptsManager p = new ToolPromptsManager(this.getClass().getSimpleName());

        ObjectNode fn = tool.putObject("function");
        fn.put("name", getName());
        fn.put("description", p.getToolDescription());

        ObjectNode props = fn.putObject("parameters").put("type", "object").putObject("properties");

        props.putObject("path")
                .put("type", "string")
                .put("description", p.getCustomDescription("path"));

        props.putObject("chunk_id")
                .put("type", "integer")
                .put("description", p.getCustomDescription("chunk_id"));

        fn.with("parameters").putArray("required").add("path");
        props.put("additionalProperties", false);
        return tool;
    }

    @Override
    public String execute(JsonNode arguments) {
        String path = arguments.path("path").asText("").trim();
        if (path.isBlank()) return "ERROR: 文件路径不能为空。";

        long chunkId = arguments.path("chunk_id").asLong(0);

        lastRecord = "读取文件 [" + path + "] (Chunk ID: " + chunkId + ")";
        return Main.workspaceManager.read(path, chunkId);
    }

    @Override
    public String getTextRecord() { return lastRecord; }
}