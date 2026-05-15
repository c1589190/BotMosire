package com.cna.agent.AgentInputHandlers;

import com.cna.agent.AgentInput.DefaultAgentInputUnit;
import com.cna.agent.AgentInput.WebEventInput;
//import com.cna.agent.AgentTask.WebEventTask;
import com.cna.agent.AgentTask.WebEventTask;
import com.cna.agent.LivingLoop;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class WebEventInputHandler implements DefaultAgentInputHandlerUnit {

    private final ObjectMapper mapper = new ObjectMapper();

    // 内存防抖池：记录每个 IP 最近一次有效请求的时间戳，防止被恶意连点/DDoS
    private final Map<String, Long> ipRateLimitPool = new ConcurrentHashMap<>();

    // 防抖冷却时间：1000毫秒（1秒内同一个IP的连续请求直接丢弃）
    private static final long RATE_LIMIT_MS = 10;

    @Override
    public Class<? extends DefaultAgentInputUnit> getSupportedInputClass() {
        return WebEventInput.class;
    }

    @Override
    public void handleInputs(List<DefaultAgentInputUnit> inputs, LivingLoop engine) {
        if (inputs.isEmpty()) return;

        long now = System.currentTimeMillis();

        for (DefaultAgentInputUnit input : inputs) {
            if (input instanceof WebEventInput) {
                WebEventInput webInput = (WebEventInput) input;
                String ip = webInput.getIpAddress();
                String rawJson = webInput.getRawJson();

                // ==========================================
                // 阶段 1：本地速率拦截 (防抖与防刷)
                // ==========================================
                Long lastRequestTime = ipRateLimitPool.get(ip);
                if (lastRequestTime != null && now - lastRequestTime < RATE_LIMIT_MS) {
                    log.warn("[WebGatekeeper] 拦截到来自 IP [{}] 的高频操作，已丢弃。", ip);
                    continue; // 直接抛弃
                }

                // ==========================================
                // 阶段 2：JSON 结构清洗 (防止脏数据污染大模型)
                // ==========================================
                try {
                    JsonNode jsonNode = mapper.readTree(rawJson);
                    // 假设你和前端约定了必须传 "action" 字段
                    if (!jsonNode.has("action")) {
                        log.warn("[WebGatekeeper] 来自 IP [{}] 的请求缺失核心动作字段，判定为无效数据。", ip);
                        continue; // 格式不对，直接抛弃
                    }

                    // 这里甚至可以调用 Gatekeeper 小模型：如果 action 是一段用户输入的复杂长文本，
                    // 让小模型先做个鉴黄或恶意意图检测，再决定是否放行。
                    // (如果需要的话，把 ChatMessageInputHandler 里那套调用小模型的逻辑搬过来即可)

                } catch (Exception e) {
                    log.warn("[WebGatekeeper] 解析前端 JSON 失败，可能存在恶意注入: {}", rawJson);
                    continue; // JSON格式稀烂，直接抛弃
                }

                // ==========================================
                // 阶段 3：校验通过，刷新时间戳，生产任务
                // ==========================================
                ipRateLimitPool.put(ip, now);

                WebEventTask task = new WebEventTask(ip, rawJson);
                engine.pushTask(task);

                log.info("[WebEventInputHandler] 成功清洗并放行前端事件，已推入执行总线");
            }
        }
    }

    @Override
    public void tick(LivingLoop engine) {
        // 定期清理防抖池中过期的 IP 记录，防止内存泄漏
        long now = System.currentTimeMillis();
        ipRateLimitPool.entrySet().removeIf(entry -> now - entry.getValue() > RATE_LIMIT_MS * 10);
    }
}