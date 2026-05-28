package com.cna.llm;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class LLMConfig {
    private String apiBase;
    private String apiKey;
    private String chatModel;
    private String embeddingModel;

    // 物理生成参数控制
    @Builder.Default private double temperature = 0.7;
    @Builder.Default private double frequencyPenalty = 0.0;
    @Builder.Default private double presencePenalty = 0.0;
    @Builder.Default private int max_tokens = 65535;

    @Builder.Default private String systemPrompt = "";

    // 思维链开关
    @Builder.Default private boolean enableCoT = false;

    // 思维链推理力度 (deepseek: "high" / "medium" / "low")
    @Builder.Default private String reasoningEffort = "high";

    // 是否启用流式输出（SSE），默认开启
    @Builder.Default private boolean stream = true;

    // HTTP 超時設定（秒）：不同模型需要不同的 timeout，避免一个慢模型卡死整个线程池
    @Builder.Default private int connectTimeoutSec = 30;
    @Builder.Default private int readTimeoutSec    = 300;
    @Builder.Default private int writeTimeoutSec   = 30;
}