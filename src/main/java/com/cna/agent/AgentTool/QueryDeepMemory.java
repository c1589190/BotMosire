package com.cna.agent.AgentTool;

import com.cna.config.ConfigsManager;
import com.cna.config.ToolPromptsManager;
import com.cna.db.MemoryManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
public class QueryDeepMemory implements DefaultAgentToolUnit {

    private final ObjectMapper mapper = new ObjectMapper();
    private String lastQuery = null;
    private int fetchedCount = 0;

    @Override
    public String getName() {
        return "query_deep_memory";
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

        ObjectNode queryNode = properties.putObject("query");
        queryNode.put("type", "string");
        queryNode.put("description", p.getCustomDescription("query"));

        ArrayNode required = parameters.putArray("required");
        required.add("query");

        return tool;
    }

    @Override
    public String execute(JsonNode arguments) {
        try {
            String query = arguments.path("query").asText().trim();
            this.lastQuery = query;

            // 默认召回 5 条最相关的高维度记忆
            int limit = ConfigsManager.MEMORY_DEPTH;
            log.info("[Tool][QueryDeepMemory] 大模型尝试潜入深层记忆网络，搜索关键词: [{}]", query);

            List<String> deepMemories = MemoryManager.getInstance().searchDeepMemoryByText(query, limit);

            if (deepMemories == null || deepMemories.isEmpty()) {
                this.fetchedCount = 0;
                return "SYSTEM_FEEDBACK: 记忆深处一片空白，没有找到与 [" + query + "] 相关的深层记忆。";
            }

            this.fetchedCount = deepMemories.size();

            return String.format("【潜意识深潜结果】：找到了 %d 条关于 [%s] 的深层记忆片段：\n%s\n(注意：深层记忆是过去的总结，可能存在时间上的错乱，请结合当前语境理解)",
                    fetchedCount, query, String.join("\n---\n", deepMemories));

        } catch (Exception e) {
            log.error("执行 query_deep_memory 发生异常", e);
            return "ERROR: 深层记忆检索失败，底层异常: " + e.getMessage();
        }
    }

    @Override
    public String getTextRecord() {
        if (this.lastQuery == null) {
            return "尝试搜索深层记忆失败;";
        } else if (this.fetchedCount == 0) {
            return "尝试搜索关于 [" + this.lastQuery + "] 的深层记忆，但什么也没想起来;";
        } else {
            return "调用深潜工具，想起了 " + this.fetchedCount + " 条关于 [" + this.lastQuery + "] 的深层记忆;";
        }
    }
}