#!/usr/bin/env python3
"""
最简 MCP stdio echo 服务器 — 用于测试 McpBridge 的发现和调用功能。
遵循 MCP 2024-11-05 协议的 JSON-RPC 2.0 over stdio。
"""
import sys
import json

def send(obj):
    """写一行 JSON 到 stdout，刷新后在 BotMosire 控制台可见。"""
    line = json.dumps(obj, ensure_ascii=False)
    sys.stdout.write(line + "\n")
    sys.stdout.flush()
    # 也写 stderr 以便调试（BotMosire 的 McpConnection 会消费 stderr）
    print(f"[echo-server] >> {line}", file=sys.stderr, flush=True)

def main():
    print("[echo-server] MCP Echo Server 启动", file=sys.stderr, flush=True)

    initialized = False

    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue

        print(f"[echo-server] << {line}", file=sys.stderr, flush=True)

        try:
            req = json.loads(line)
        except json.JSONDecodeError:
            continue

        req_id = req.get("id")
        method = req.get("method")
        params = req.get("params", {})

        # ── initialize ──
        if method == "initialize":
            send({
                "jsonrpc": "2.0",
                "id": req_id,
                "result": {
                    "protocolVersion": "2024-11-05",
                    "capabilities": {
                        "tools": {"listChanged": False}
                    },
                    "serverInfo": {
                        "name": "echo-server",
                        "version": "1.0.0"
                    }
                }
            })

        # ── notifications/initialized ──
        elif method == "notifications/initialized":
            initialized = True
            print("[echo-server] 握手完成", file=sys.stderr, flush=True)

        # ── tools/list ──
        elif method == "tools/list":
            send({
                "jsonrpc": "2.0",
                "id": req_id,
                "result": {
                    "tools": [
                        {
                            "name": "echo",
                            "description": "回显输入的消息。用于测试 MCP 工具调用是否正常工作。",
                            "inputSchema": {
                                "type": "object",
                                "properties": {
                                    "message": {
                                        "type": "string",
                                        "description": "要回显的消息内容"
                                    },
                                    "repeat": {
                                        "type": "integer",
                                        "description": "重复次数（默认1，最大5）"
                                    }
                                },
                                "required": ["message"]
                            }
                        },
                        {
                            "name": "get_time",
                            "description": "返回服务器当前时间。无参数。",
                            "inputSchema": {
                                "type": "object",
                                "properties": {},
                                "required": []
                            }
                        }
                    ]
                }
            })

        # ── tools/call ──
        elif method == "tools/call":
            tool_name = params.get("name", "")
            tool_args = params.get("arguments", {})

            if tool_name == "echo":
                message = tool_args.get("message", "")
                repeat = min(int(tool_args.get("repeat", 1)), 5)
                result_text = f"Echo: {message}\n" * repeat
                send({
                    "jsonrpc": "2.0",
                    "id": req_id,
                    "result": {
                        "content": [{"type": "text", "text": result_text.strip()}],
                        "isError": False
                    }
                })

            elif tool_name == "get_time":
                from datetime import datetime
                now = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
                send({
                    "jsonrpc": "2.0",
                    "id": req_id,
                    "result": {
                        "content": [{"type": "text", "text": f"服务器当前时间: {now}"}],
                        "isError": False
                    }
                })

            else:
                send({
                    "jsonrpc": "2.0",
                    "id": req_id,
                    "result": {
                        "content": [{"type": "text", "text": f"错误: 未知工具 [{tool_name}]"}],
                        "isError": True
                    }
                })

        else:
            # 未知方法 — 但不要对通知报错
            if req_id is not None:
                send({
                    "jsonrpc": "2.0",
                    "id": req_id,
                    "error": {
                        "code": -32601,
                        "message": f"Method not found: {method}"
                    }
                })

if __name__ == "__main__":
    main()
