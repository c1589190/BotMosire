package com.cna.agent.AgentTool.io;

import com.cna.Main;
import com.cna.agent.AgentTool.DefaultAgentToolUnit;
import com.cna.config.ToolPromptsManager;
import com.cna.workspace.WorkSpaceManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class ListFiles implements DefaultAgentToolUnit {

    private final ObjectMapper mapper = new ObjectMapper();
    private String lastRecord = "调用目录浏览工具";

    @Override
    public String getName() { return "list_workspace"; }

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

        // path 为可选参数，不加 required
        props.put("additionalProperties", false);
        return tool;
    }

    @Override
    public String execute(JsonNode arguments) {
        String path = arguments.path("path").asText("").trim();
        lastRecord = path.isBlank() ? "浏览当前工作目录" : "浏览目录: " + path;

        // 如果传入了路径，先尝试 cd 过去，然后 ls，最后可选地 cd 回来。
        // 由于我们在上一步把 list 逻辑重构成无参数的 ls()，这里可以通过临时切换目录来实现
        if (!path.isBlank()) {
            String cdRes = Main.workspaceManager.cd(path);
            if (cdRes.startsWith("ERROR")) return cdRes;
        }
        return Main.workspaceManager.ls();
    }

    @Override
    public String getTextRecord() { return lastRecord; }
}