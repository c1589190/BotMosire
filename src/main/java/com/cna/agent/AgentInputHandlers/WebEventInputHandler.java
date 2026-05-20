package com.cna.agent.AgentInputHandlers;

import com.cna.agent.AgentInput.WebEventInput;
import com.cna.agent.AgentTask.WebEventTask;
import com.cna.agent.LivingLoop;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class WebEventInputHandler extends AbstractInputHandler<WebEventInput> {

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Long> ipRateLimitPool = new ConcurrentHashMap<>();
    private static final long RATE_LIMIT_MS = 1000;

    public WebEventInputHandler(LivingLoop engine) {
        super(WebEventInput.class, engine);
    }

    @Override
    protected void processInputs(List<WebEventInput> inputs) {
        long now = System.currentTimeMillis();

        for (WebEventInput webInput : inputs) {
            String ip = webInput.getIpAddress();
            String rawJson = webInput.getRawJson();

            Long lastRequestTime = ipRateLimitPool.get(ip);
            if (lastRequestTime != null && now - lastRequestTime < RATE_LIMIT_MS) {
                log.warn("[WebGatekeeper] 拦截到来自 IP [{}] 的高频操作，已丢弃。", ip);
                continue;
            }

            try {
                JsonNode jsonNode = mapper.readTree(rawJson);
                if (!jsonNode.has("action")) {
                    log.warn("[WebGatekeeper] 来自 IP [{}] 的请求缺失核心动作字段，判定为无效数据。", ip);
                    continue;
                }
            } catch (Exception e) {
                log.warn("[WebGatekeeper] 解析前端 JSON 失败，可能存在恶意注入: {}", rawJson);
                continue;
            }

            ipRateLimitPool.put(ip, now);
            WebEventTask task = new WebEventTask(ip, rawJson);
            this.engine.pushTask(task); // 这也会触发热度 ++

            log.info("[WebEventInputHandler] 成功清洗并放行前端事件，已推入执行总线");
        }
    }

    @Override
    public void tick() {
        long now = System.currentTimeMillis();
        ipRateLimitPool.entrySet().removeIf(entry -> now - entry.getValue() > RATE_LIMIT_MS * 10);
    }
}