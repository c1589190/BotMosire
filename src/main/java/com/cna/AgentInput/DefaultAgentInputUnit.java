package com.cna.AgentInput;

import java.util.UUID;

public interface DefaultAgentInputUnit {
    AgentInputType getType();
    String getInputText();
    UUID getUUID();
}
