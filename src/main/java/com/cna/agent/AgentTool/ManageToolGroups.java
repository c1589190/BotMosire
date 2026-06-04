package com.cna.agent.AgentTool;

import com.cna.config.ToolPromptsManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具组管理器。LLM 通过此工具按需激活/注销工具组，减少每轮注入的工具定义数量。
 * <p>
 * 线程安全设计：此工具不存储任何实例状态。LivingLoop 直接从已解析的
 * LLM 返回 JSON 中读取 action/group 来更新任务激活池，无需通过本类传递状态。
 */
@Slf4j
public class ManageToolGroups implements DefaultAgentToolUnit {

    private static final ObjectMapper mapper = new ObjectMapper();

    private final String catalogDescription;
    private final Map<String, List<String>> groupCatalog;

    public ManageToolGroups(Map<String, DefaultAgentToolUnit> toolbox) {
        this.groupCatalog = new LinkedHashMap<>();
        for (Map.Entry<String, DefaultAgentToolUnit> entry : toolbox.entrySet()) {
            DefaultAgentToolUnit tool = entry.getValue();
            String className = tool.getClass().getSimpleName();
            String group = ToolPromptsManager.getToolGroup(className);
            if (group == null) {
                group = "misc";
            }
            groupCatalog.computeIfAbsent(group, k -> new ArrayList<>()).add(tool.getName());
        }

        StringBuilder sb = new StringBuilder(512);
        sb.append("管理当前任务可用的工具组。支持 activate/deactivate/list 操作。");
        sb.append("激活后下一轮思考中该组工具的完整定义即注入；完成子任务后应主动注销以节省上下文。");
        sb.append("可用分组：\n");
        for (Map.Entry<String, List<String>> g : groupCatalog.entrySet()) {
            sb.append("[").append(g.getKey()).append("] ");
            sb.append(String.join(", ", g.getValue())).append("\n");
        }
        this.catalogDescription = sb.toString();
    }

    @Override
    public String getName() {
        return "manage_tool_groups";
    }

    @Override
    public ObjectNode getToolDefinition() {
        ObjectNode toolDef = mapper.createObjectNode();
        toolDef.put("type", "function");

        ObjectNode function = toolDef.putObject("function");
        function.put("name", getName());
        function.put("description", this.catalogDescription);

        ObjectNode params = function.putObject("parameters");
        params.put("type", "object");

        ObjectNode properties = params.putObject("properties");

        ObjectNode actionProp = properties.putObject("action");
        actionProp.put("type", "string");
        actionProp.put("description", "操作类型：activate（激活工具组，下轮可用）、deactivate（注销工具组）、list（列出所有分组）");
        ArrayNode actionEnum = actionProp.putArray("enum");
        actionEnum.add("activate").add("deactivate").add("list");

        ObjectNode groupProp = properties.putObject("group");
        groupProp.put("type", "string");
        groupProp.put("description", "目标分组名。activate/deactivate 时必填（见上方分组目录），list 时忽略。");

        ArrayNode required = params.putArray("required");
        required.add("action");

        params.put("additionalProperties", false);
        return toolDef;
    }

    @Override
    public String execute(JsonNode arguments) {
        String action = arguments.path("action").asText("").trim();

        if ("list".equals(action)) {
            StringBuilder sb = new StringBuilder("可用工具组：\n");
            for (Map.Entry<String, List<String>> g : groupCatalog.entrySet()) {
                sb.append("[").append(g.getKey()).append("] ");
                sb.append(String.join(", ", g.getValue())).append("\n");
            }
            return sb.toString();
        }

        String group = arguments.path("group").asText("").trim();
        if (group.isEmpty()) {
            return "错误：activate/deactivate 操作必须提供 group 参数。调用 list 可查看所有可用分组。";
        }

        if (!groupCatalog.containsKey(group)) {
            return "错误：未知分组 [" + group + "]。调用 list 可查看所有可用分组。";
        }

        if ("activate".equals(action)) {
            return "工具组 [" + group + "] 激活成功，下一轮思考中将可用。成员：" + String.join(", ", groupCatalog.get(group));
        }
        if ("deactivate".equals(action)) {
            return "工具组 [" + group + "] 已注销。";
        }

        return "错误：未知操作 [" + action + "]，仅支持 activate/deactivate/list。";
    }

    @Override
    public String getTextRecord() {
        return "管理了工具组激活状态";
    }

    @Override
    public boolean isAutoLoad() {
        return true;
    }

    @Override
    public boolean isAutoMemory() {
        return false;
    }
}
