package com.cna.agent.code;

import com.cna.agent.AgentTool.DefaultAgentToolUnit;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * CodeAgent 专属的 finish_task。
 * <p>
 * 刻意与主脑的 {@link com.cna.agent.AgentTool.FinishTask} 完全隔离：主脑那个会触发记忆保存、
 * 感觉概念抽取与 GLOBAL_CACHE 操作；本工具只承载「结束信号 + 总结字符串」，不碰任何主脑状态。
 * 实际的终止由 {@link CodeAgent} 的循环侦测工具名 "finish_task" 完成，本类的 execute 仅作兜底。
 */
public class CodeAgentFinishTask implements DefaultAgentToolUnit {

    private static final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String getName() {
        return "finish_task";
    }

    @Override
    public ObjectNode getToolDefinition() {
        ObjectNode toolDef = mapper.createObjectNode();
        toolDef.put("type", "function");

        ObjectNode function = toolDef.putObject("function");
        function.put("name", getName());
        function.put("description",
                "结束当前子任务并把结果交回主脑。任务成功或失败都必须调用：成功时 success=true，"
                        + "失败/无法继续时 success=false；summary 必须写清楚实际结果或失败原因。");

        ObjectNode params = function.putObject("parameters");
        params.put("type", "object");
        ObjectNode properties = params.putObject("properties");

        ObjectNode successProp = properties.putObject("success");
        successProp.put("type", "boolean");
        successProp.put("description", "任务是否成功完成。无法继续或出错时填 false。");

        ObjectNode summaryProp = properties.putObject("summary");
        summaryProp.put("type", "string");
        summaryProp.put("description", "一段话总结实际做了什么、关键结果或产出文件路径；失败时写失败原因。");

        ArrayNode required = params.putArray("required");
        required.add("success");
        required.add("summary");

        return toolDef;
    }

    @Override
    public String execute(JsonNode arguments) {
        // 正常情况下 CodeAgent 循环会在调用本方法前就拦截 finish_task；此处仅作兜底。
        return arguments.path("summary").asText("(无总结)");
    }

    @Override
    public String getTextRecord() {
        return "结束了子任务并提交总结";
    }
}
