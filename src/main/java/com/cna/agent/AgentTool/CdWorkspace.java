package com.cna.agent.AgentTool;

import com.cna.workspace.WorkSpaceManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class CdWorkspace implements DefaultAgentToolUnit {

    private static final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String getName() { return "change_directory"; }

    @Override
    public ObjectNode getToolDefinition() {
        ObjectNode tool = mapper.createObjectNode();
        tool.put("type", "function");
        ObjectNode fn = tool.putObject("function");
        fn.put("name", getName());
        fn.put("description",
                "切换工作区当前目录（cwd）。切换后，每轮任务开始时会自动显示新目录的内容，" +
                "read/write/send 等工具的相对路径也以新 cwd 为基准。\n" +
                "特殊用法：'..' 返回上级目录，'/' 回到根目录。\n" +
                "如果路径以 '/' 开头，则从工作区根目录解析（绝对路径）。");

        ObjectNode params = fn.putObject("parameters");
        params.put("type", "object");
        ObjectNode props = params.putObject("properties");

        ObjectNode path = props.putObject("path");
        path.put("type", "string");
        path.put("description", "目标目录路径，例如 'src'、'..'、'/'、'/reports'");

        params.putArray("required").add("path");
        return tool;
    }

    @Override
    public String execute(JsonNode arguments) {
        String path = arguments.path("path").asText("").trim();
        if (path.isBlank()) return "ERROR: 路径不能为空，用 '/' 回到根目录。";
        return WorkSpaceManager.getInstance().cd(path);
    }

    @Override
    public String getTextRecord() {
        return "切换工作区目录到: " + WorkSpaceManager.getInstance().getCurrentDir();
    }
}
