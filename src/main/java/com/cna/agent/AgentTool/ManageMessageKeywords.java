package com.cna.agent.AgentTool;

import com.cna.agent.MessageKeywordManager;
import com.cna.config.ConfigsManager;
import com.cna.config.ToolPromptsManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

/**
 * 消息关键词管理工具 — LLM 通过此工具设置/删除/查看消息筛选关键词。
 * 命中关键词的 ChatMessageInput 会在 Gatekeeper 阶段获得额外权重加分，
 * 从而提高该消息被优先处理的概率。
 */
@Slf4j
public class ManageMessageKeywords implements DefaultAgentToolUnit {

    private static final ObjectMapper mapper = new ObjectMapper();
    private String lastRecord = "";

    public ManageMessageKeywords() {
    }

    @Override
    public String getName() {
        return "manage_message_keywords";
    }

    @Override
    public ObjectNode getToolDefinition() {
        ObjectNode tool = mapper.createObjectNode();
        tool.put("type", "function");

        ToolPromptsManager p = new ToolPromptsManager(this.getClass().getName());

        ObjectNode function = tool.putObject("function");
        function.put("name", getName());
        function.put("description", p.getToolDescription());

        ObjectNode parameters = function.putObject("parameters");
        parameters.put("type", "object");

        ObjectNode properties = parameters.putObject("properties");

        // action
        ObjectNode actionProp = properties.putObject("action");
        actionProp.put("type", "string");
        actionProp.put("description", p.getCustomDescription("action"));
        ArrayNode actionEnum = actionProp.putArray("enum");
        actionEnum.add("add").add("remove").add("list").add("clear");

        // keyword
        ObjectNode kwProp = properties.putObject("keyword");
        kwProp.put("type", "string");
        kwProp.put("description", p.getCustomDescription("keyword"));

        // weight
        ObjectNode weightProp = properties.putObject("weight");
        weightProp.put("type", "number");
        weightProp.put("description", String.format(
                p.getCustomDescription("weight"),
                ConfigsManager.MESSAGE_KEYWORD_WEIGHT_MIN,
                ConfigsManager.MESSAGE_KEYWORD_WEIGHT_MAX,
                ConfigsManager.MESSAGE_KEYWORD_DEFAULT_WEIGHT));

        // ttl_minutes
        ObjectNode ttlProp = properties.putObject("ttl_minutes");
        ttlProp.put("type", "integer");
        ttlProp.put("description", String.format(
                p.getCustomDescription("ttl_minutes"),
                ConfigsManager.MESSAGE_KEYWORD_DEFAULT_TTL_MINUTES));

        ArrayNode required = parameters.putArray("required");
        required.add("action");

        parameters.put("additionalProperties", false);
        return tool;
    }

    @Override
    public String execute(JsonNode arguments) {
        try {
            String action = arguments.path("action").asText("").trim();
            MessageKeywordManager km = MessageKeywordManager.getInstance();

            switch (action) {
                case "add": {
                    String keyword = arguments.path("keyword").asText("").trim();
                    if (keyword.isEmpty()) {
                        return "错误：add 操作必须提供 keyword 参数。";
                    }
                    double weight = arguments.has("weight") && !arguments.get("weight").isNull()
                            ? arguments.path("weight").asDouble(ConfigsManager.MESSAGE_KEYWORD_DEFAULT_WEIGHT)
                            : ConfigsManager.MESSAGE_KEYWORD_DEFAULT_WEIGHT;
                    int ttlMinutes = arguments.has("ttl_minutes") && !arguments.get("ttl_minutes").isNull()
                            ? arguments.path("ttl_minutes").asInt(ConfigsManager.MESSAGE_KEYWORD_DEFAULT_TTL_MINUTES)
                            : ConfigsManager.MESSAGE_KEYWORD_DEFAULT_TTL_MINUTES;

                    String result = km.addKeyword(keyword, weight, ttlMinutes);
                    this.lastRecord = "manage_message_keywords add: \"" + keyword + "\" weight=" + weight + " ttl=" + ttlMinutes + "min";
                    return result;
                }

                case "remove": {
                    String keyword = arguments.path("keyword").asText("").trim();
                    if (keyword.isEmpty()) {
                        return "错误：remove 操作必须提供 keyword 参数。";
                    }
                    String result = km.removeKeyword(keyword);
                    this.lastRecord = "manage_message_keywords remove: \"" + keyword + "\"";
                    return result;
                }

                case "list": {
                    String result = km.listKeywords();
                    this.lastRecord = "manage_message_keywords list";
                    return result;
                }

                case "clear": {
                    String result = km.clearKeywords();
                    this.lastRecord = "manage_message_keywords clear";
                    return result;
                }

                default:
                    return "错误：未知操作 [" + action + "]，仅支持 add / remove / list / clear。";
            }

        } catch (Exception e) {
            log.error("执行 manage_message_keywords 发生异常", e);
            return "ERROR: 关键词管理失败，异常: " + e.getMessage();
        }
    }

    @Override
    public String getTextRecord() {
        return this.lastRecord;
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
