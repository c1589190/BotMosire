package com.cna.agent.AgentTool;

import com.cna.config.ConfigsManager;
import com.cna.config.ToolPromptsManager;
import com.cna.db.MemoryManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
public class GetMoreCurrentMemorys implements DefaultAgentToolUnit {

    private final ObjectMapper mapper = new ObjectMapper();
    private int fetchedCount = 0;

    @Override
    public String getName() {
        // 【修复】：大模型的 tool name 通常不支持带 "." 的全限定类名，建议改成下划线格式
        return "get_more_current_memorys";
    }

    @Override
    public ObjectNode getToolDefinition() {
        ObjectNode tool = mapper.createObjectNode();
        tool.put("type", "function");

        // 实例化提示词管理器
        ToolPromptsManager p = new ToolPromptsManager(this.getClass().getName());

        ObjectNode function = tool.putObject("function");
        function.put("name", getName());
        // 获取工具描述
        function.put("description", p.getToolDescription());

        // 无需必须参数，直接给一个空的 properties
        ObjectNode parameters = function.putObject("parameters");
        parameters.put("type", "object");
        parameters.putObject("properties");

        return tool;
    }

    @Override
    public String execute(JsonNode arguments) {
        try {
            int count = ConfigsManager.CURRENT_MEMORIES_MAXSIZE + ConfigsManager.EMB_MEMORY_SIZE;
            log.info("[Tool][GetMoreCurrentMemorys] 潜意识申请回想最近的 {} 条短期记忆", count);

            // 调用 MemoryManager 的现有方法
            List<String> memories = MemoryManager.getInstance().getCurrentMemorys(count);

            if (memories == null || memories.isEmpty()) {
                this.fetchedCount = 0;
                return "SYSTEM_FEEDBACK: 当前短期记忆库为空。";
            }

            this.fetchedCount = memories.size();

            // 为了防止顺序倒置，如果数据库返回的是 [最新 -> 最老]，你可能需要在这里 Collections.reverse(memories)
            // 保证大模型阅读时的顺序是正常的 [旧 -> 新]。具体取决于你 MemoryDB 的 SQL 排序逻辑。

            return String.format("【以下是你最近的 %d 条内部记忆记录】:\n%s",
                    fetchedCount, String.join("\n", memories));

        } catch (Exception e) {
            log.error("执行 get_more_current_memorys 发生异常", e);
            return "ERROR: 获取短期记忆失败，底层异常: " + e.getMessage();
        }
    }

    @Override
    public String getTextRecord() {
        if (this.fetchedCount == 0) {
            return "尝试回想最近的短期记忆，但发现记忆库为空;";
        } else {
            return "调用工具，回想了最近的 " + this.fetchedCount + " 条短期记忆;";
        }
    }

    @Override
    public boolean isAutoMemory() {
        return false;
    }
}