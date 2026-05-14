package com.cna.agent.AgentTool.io;

import com.cna.Main;
import com.cna.agent.AgentTool.DefaultAgentToolUnit;
import com.cna.config.ToolPromptsManager;
import com.cna.workspace.WorkSpaceManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class WriteFile implements DefaultAgentToolUnit {

    private final ObjectMapper mapper = new ObjectMapper();
    private String lastRecord = "调用文件写入工具";

    @Override
    public String getName() { return "write_workspace_file"; }

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

        props.putObject("content")
                .put("type", "string")
                .put("description", p.getCustomDescription("content"));


        ObjectNode modeNode = props.putObject("mode");
        modeNode.put("type", "string");
        modeNode.putArray("enum").add("overwrite").add("append"); // 这里必须用 putArray
        modeNode.put("description", p.getCustomDescription("mode"));

        props.with("mode").put("description", p.getCustomDescription("mode"));

        fn.with("parameters").putArray("required").add("path").add("content");
        return tool;
    }

    @Override
    public String execute(JsonNode arguments) {
        String path = arguments.path("path").asText("").trim();
        String content = arguments.path("content").asText("");
        String mode = arguments.path("mode").asText("overwrite").trim().toLowerCase();

        if (path.isBlank()) return "ERROR: 文件路径不能为空。";

        boolean append = "append".equals(mode);
        lastRecord = (append ? "追加" : "覆盖") + "写入文件: [" + path + "] (" + content.length() + " 字符)";
        return Main.workspaceManager.write(path, content, append);
    }

    @Override
    public String getTextRecord() { return lastRecord; }
}