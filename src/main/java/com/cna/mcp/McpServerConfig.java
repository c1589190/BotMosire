package com.cna.mcp;

import lombok.Builder;
import lombok.Data;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * MCP 服务器配置 — 支持三种启动类型：command（自定义命令）、npx（npm 包）、uvx（Python 包）。
 */
@Data
@Builder
public class McpServerConfig {

    public enum ServerType {
        COMMAND,  // 自定义命令（默认）
        NPX,      // npx 包（如 @playwright/mcp@latest）
        UVX       // uvx 包（如 windows-mcp）
    }

    /** 服务器逻辑名称 */
    private String name;

    /** 启动类型 */
    @Builder.Default
    private ServerType type = ServerType.COMMAND;

    /** 启动命令（COMMAND 类型时必填） */
    private String command;

    /** 命令参数列表 */
    @Builder.Default
    private List<String> args = Collections.emptyList();

    /** npx/uvx 包名（NPX/UVX 类型时必填） */
    private String mcpPackage;

    /** npx/uvx 额外参数 */
    @Builder.Default
    private List<String> extraArgs = Collections.emptyList();

    /** 环境变量 */
    @Builder.Default
    private Map<String, String> env = Collections.emptyMap();

    /** 是否启用 */
    @Builder.Default
    private boolean enabled = true;

    /** 请求超时秒数（0 则使用全局默认） */
    @Builder.Default
    private int requestTimeoutSeconds = 0;
}
