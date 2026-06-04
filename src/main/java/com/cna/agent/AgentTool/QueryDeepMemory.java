package com.cna.agent.AgentTool;

import com.cna.config.ConfigsManager;
import com.cna.config.ToolPromptsManager;
import com.cna.agent.MemoryManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
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

        // 新增：可选的来源过滤参数
        ObjectNode sourcesNode = properties.putObject("sources");
        sourcesNode.put("type", "array");
        sourcesNode.put("description", "可选：按来源标识符过滤/优先召回（如 qqid:xxx, qq_group:xxx, webaddress_xxx, system:internal）。不传则纯语义搜索");
        ObjectNode sourcesItems = sourcesNode.putObject("items");
        sourcesItems.put("type", "string");

        ArrayNode required = parameters.putArray("required");
        required.add("query");

        parameters.put("additionalProperties", false);
        return tool;
    }

    @Override
    public String execute(JsonNode arguments) {
        try {
            String query = arguments.path("query").asText().trim();
            this.lastQuery = query;

            // 解析可选的 sources 参数
            List<String> sourceFilter = null;
            JsonNode sourcesNode = arguments.path("sources");
            if (sourcesNode.isArray() && !sourcesNode.isEmpty()) {
                sourceFilter = new ArrayList<>();
                for (JsonNode s : sourcesNode) {
                    String src = s.asText();
                    if (src != null && !src.isBlank()) {
                        sourceFilter.add(src);
                    }
                }
            }

            int limit = ConfigsManager.MEMORY_DEPTH;
            String filterDesc = (sourceFilter != null && !sourceFilter.isEmpty())
                    ? "，来源优先: " + sourceFilter
                    : "";
            log.info("[Tool][QueryDeepMemory] 大模型尝试潜入深层记忆网络，搜索关键词: [{}]{}, 召回数: {}",
                    query, filterDesc, limit);

            // 使用带 ID 和来源的结构化结果
            List<MemoryManager.DeepMemoryResult> results =
                    MemoryManager.getInstance().searchDeepMemoryResultsByText(query, limit, sourceFilter);

            if (results == null || results.isEmpty()) {
                this.fetchedCount = 0;
                return "SYSTEM_FEEDBACK: 记忆深处一片空白，没有找到与 [" + query + "] 相关的深层记忆。";
            }

            this.fetchedCount = results.size();

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("【潜意识深潜结果】：找到了 %d 条关于 [%s] 的深层记忆片段：\n",
                    fetchedCount, query));
            for (MemoryManager.DeepMemoryResult r : results) {
                sb.append(r.toString()).append("\n---\n");
            }
            sb.append("(提示：[DM-N] 为记忆编号，可配合 finish_task 的 useful_memory_ids 参数标记有用记忆。深层记忆是过去的总结，可能存在时间错乱，请结合当前语境理解)");

            return sb.toString();

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
