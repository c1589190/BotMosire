package com.cna.agent.AgentTool.io;

import com.cna.Main;
import com.cna.agent.AgentTool.DefaultAgentToolUnit;
import com.cna.config.ToolPromptsManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class CdWorkspace implements DefaultAgentToolUnit {

    private final ObjectMapper mapper = new ObjectMapper();
    private String lastRecord = "调用目录切换工具";

    @Override
    public String getName() { return "change_directory"; }

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

        fn.with("parameters").putArray("required").add("path");
        return tool;
    }

    @Override
    public String execute(JsonNode arguments) {
        String path = arguments.path("path").asText("").trim();
        if (path.isBlank()) return "ERROR: 路径不能为空，若要回根目录请传入 '/'。";

        // 此处假设 WorkSpaceManager 已经改为单例或通过上下文获取，若是跟随 Agent Session 请按需调整
        String result = Main.workspaceManager.cd(path);
        lastRecord = "切换工作区目录至: " + path;
        return result;
    }

    @Override
    public String getTextRecord() { return lastRecord; }
}