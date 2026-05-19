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
}