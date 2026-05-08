package com.cna.agent.AgentTool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

public interface DefaultAgentToolUnit {
    // 1. 工具的唯一名称 (大模型返回的 name)
    String getName();

    // 2. 生成这个工具的 JSON Schema (给 LLM 看的说明书)
    ObjectNode getToolDefinition();

    // 3. 真正的物理执行逻辑 (参数是大模型填好的 JSON，返回值是执行结果)
    // 返回 String 是为了把执行结果（比如"发送成功"或"报错了"）再喂给大模型
    String execute(JsonNode arguments);

    String getTextRecord();

    default boolean isAutoLoad() {
        return true;
    }
    default boolean isAutoMemory(){
        return true;
    }


}
