#!/usr/bin/env python3
"""
GitHub MCP stdio 服务器 — 将 GitHub REST API 封装为 MCP 工具。
供 BotMosire 的 McpBridge 通过 stdio 连接调用。

遵循 MCP 2024-11-05 协议的 JSON-RPC 2.0 over stdio。

环境变量:
  GITHUB_TOKEN — GitHub Personal Access Token (必填)
  GITHUB_OWNER   — 默认仓库 owner (可选)
  GITHUB_REPO    — 默认仓库名 (可选)
"""
import sys
import json
import os
import urllib.request
import urllib.error
import urllib.parse

API_BASE = "https://api.github.com"
TOKEN = os.environ.get("GITHUB_TOKEN", "")
DEFAULT_OWNER = os.environ.get("GITHUB_OWNER", "")
DEFAULT_REPO = os.environ.get("GITHUB_REPO", "")

def send(obj):
    line = json.dumps(obj, ensure_ascii=False, separators=(',', ':'))
    sys.stdout.write(line + "\n")
    sys.stdout.flush()
    print(f"[github-mcp] >> {line[:200]}", file=sys.stderr, flush=True)

def gh_api(method, path, body=None):
    """调用 GitHub REST API，返回 (status, response_json_or_text)。"""
    url = f"{API_BASE}{path}"
    data = json.dumps(body).encode("utf-8") if body else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Authorization", f"Bearer {TOKEN}")
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("User-Agent", "BotMosire-MCP/1.0")
    if data:
        req.add_header("Content-Type", "application/json")

    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        err_body = e.read().decode("utf-8") if e.fp else str(e)
        try:
            err_json = json.loads(err_body)
            return e.code, err_json
        except json.JSONDecodeError:
            return e.code, {"message": err_body}
    except Exception as e:
        return 0, {"message": str(e)}

def format_json(obj, indent=2):
    return json.dumps(obj, ensure_ascii=False, indent=indent)

# ── 工具定义 ──────────────────────────────────────────────────────────────

TOOLS = [
    {
        "name": "search_repositories",
        "description": "搜索 GitHub 仓库",
        "inputSchema": {
            "type": "object",
            "properties": {
                "query": {
                    "type": "string",
                    "description": "搜索关键词 (支持 GitHub 搜索语法)"
                },
                "page": {
                    "type": "integer",
                    "description": "页码 (默认 1)"
                },
                "per_page": {
                    "type": "integer",
                    "description": "每页条数 (默认 10, 最大 30)"
                }
            },
            "required": ["query"]
        }
    },
    {
        "name": "get_file_contents",
        "description": "获取仓库中文件的内容",
        "inputSchema": {
            "type": "object",
            "properties": {
                "owner": {"type": "string", "description": "仓库 owner"},
                "repo": {"type": "string", "description": "仓库名"},
                "path": {"type": "string", "description": "文件路径"},
                "branch": {"type": "string", "description": "分支名 (可选)"}
            },
            "required": ["owner", "repo", "path"]
        }
    },
    {
        "name": "list_issues",
        "description": "列出仓库的 issues",
        "inputSchema": {
            "type": "object",
            "properties": {
                "owner": {"type": "string", "description": "仓库 owner"},
                "repo": {"type": "string", "description": "仓库名"},
                "state": {
                    "type": "string",
                    "enum": ["open", "closed", "all"],
                    "description": "状态筛选 (默认 open)"
                },
                "per_page": {
                    "type": "integer",
                    "description": "每页条数 (默认 10)"
                }
            },
            "required": ["owner", "repo"]
        }
    },
    {
        "name": "create_issue",
        "description": "在仓库中创建 issue",
        "inputSchema": {
            "type": "object",
            "properties": {
                "owner": {"type": "string", "description": "仓库 owner"},
                "repo": {"type": "string", "description": "仓库名"},
                "title": {"type": "string", "description": "Issue 标题"},
                "body": {"type": "string", "description": "Issue 正文 (Markdown)"},
                "labels": {
                    "type": "array",
                    "items": {"type": "string"},
                    "description": "标签列表"
                }
            },
            "required": ["owner", "repo", "title"]
        }
    },
    {
        "name": "search_code",
        "description": "在 GitHub 上搜索代码",
        "inputSchema": {
            "type": "object",
            "properties": {
                "query": {
                    "type": "string",
                    "description": "搜索关键词 (支持 GitHub 代码搜索语法)"
                },
                "per_page": {
                    "type": "integer",
                    "description": "每页条数 (默认 10, 最大 30)"
                }
            },
            "required": ["query"]
        }
    },
    {
        "name": "get_user_info",
        "description": "获取 GitHub 用户信息",
        "inputSchema": {
            "type": "object",
            "properties": {
                "username": {
                    "type": "string",
                    "description": "GitHub 用户名 (留空则获取当前认证用户)"
                }
            },
            "required": []
        }
    },
]

def main():
    if not TOKEN:
        print("[github-mcp] ⚠️ 未设置 GITHUB_TOKEN 环境变量", file=sys.stderr, flush=True)
        # 不退出 — 让调用方处理错误

    print("[github-mcp] GitHub MCP Server 启动", file=sys.stderr, flush=True)

    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue

        print(f"[github-mcp] << {line[:200]}", file=sys.stderr, flush=True)

        try:
            req = json.loads(line)
        except json.JSONDecodeError:
            continue

        req_id = req.get("id")
        method = req.get("method", "")
        params = req.get("params", {})

        # ── initialize ──
        if method == "initialize":
            send({
                "jsonrpc": "2.0",
                "id": req_id,
                "result": {
                    "protocolVersion": "2024-11-05",
                    "capabilities": {"tools": {"listChanged": False}},
                    "serverInfo": {
                        "name": "github-mcp-server",
                        "version": "1.0.0"
                    }
                }
            })

        # ── notifications/initialized ──
        elif method == "notifications/initialized":
            print("[github-mcp] 握手完成", file=sys.stderr, flush=True)

        # ── tools/list ──
        elif method == "tools/list":
            send({
                "jsonrpc": "2.0",
                "id": req_id,
                "result": {"tools": TOOLS}
            })

        # ── tools/call ──
        elif method == "tools/call":
            tool_name = params.get("name", "")
            tool_args = params.get("arguments", {})
            result_text = ""
            is_error = False

            if not TOKEN:
                result_text = "错误: GitHub MCP 服务器未配置 GITHUB_TOKEN 环境变量。请在 BotMosire 的 application.properties 中为 mcp.server.github.env 设置 GITHUB_TOKEN=your_token。"
                is_error = True

            elif tool_name == "search_repositories":
                query = tool_args.get("query", "")
                page = tool_args.get("page", 1)
                per_page = min(tool_args.get("per_page", 10), 30)
                qs = urllib.parse.urlencode({"q": query, "page": page, "per_page": per_page})
                status, data = gh_api("GET", f"/search/repositories?{qs}")
                if status == 200:
                    repos = data.get("items", [])
                    lines = [f"🔍 搜索 \"{query}\" — 共 {data.get('total_count', 0)} 个结果 (第{page}页):"]
                    for r in repos:
                        lines.append(f"  • {r['full_name']} ⭐{r['stargazers_count']} — {r.get('description', '无描述')[:100]}")
                        lines.append(f"    {r['html_url']}")
                    result_text = "\n".join(lines)
                else:
                    result_text = f"搜索失败 (HTTP {status}): {data.get('message', str(data))}"
                    is_error = True

            elif tool_name == "get_file_contents":
                owner = tool_args.get("owner", DEFAULT_OWNER)
                repo = tool_args.get("repo", DEFAULT_REPO)
                path = tool_args.get("path", "")
                branch = tool_args.get("branch", "")
                qs = f"?ref={branch}" if branch else ""
                status, data = gh_api("GET", f"/repos/{owner}/{repo}/contents/{path}{qs}")
                if status == 200:
                    import base64
                    content = data.get("content", "")
                    if content:
                        decoded = base64.b64decode(content).decode("utf-8")
                        result_text = f"📄 {owner}/{repo}/{path} ({data.get('size', 0)} bytes):\n{decoded[:4000]}"
                        if len(decoded) > 4000:
                            result_text += f"\n... (截断，共 {len(decoded)} 字符)"
                    else:
                        result_text = f"📁 {owner}/{repo}/{path} (目录)"
                else:
                    result_text = f"获取文件失败 (HTTP {status}): {data.get('message', str(data))}"
                    is_error = True

            elif tool_name == "list_issues":
                owner = tool_args.get("owner", DEFAULT_OWNER)
                repo = tool_args.get("repo", DEFAULT_REPO)
                state = tool_args.get("state", "open")
                per_page = min(tool_args.get("per_page", 10), 30)
                qs = urllib.parse.urlencode({"state": state, "per_page": per_page})
                status, data = gh_api("GET", f"/repos/{owner}/{repo}/issues?{qs}")
                if status == 200:
                    lines = [f"📋 {owner}/{repo} issues (state={state}):"]
                    for iss in data:
                        if "pull_request" in iss:
                            continue  # 跳过 PR
                        lines.append(f"  #{iss['number']} [{iss['state']}] {iss['title']}")
                        lines.append(f"    作者: {iss.get('user', {}).get('login', '?')} | {iss.get('html_url', '')}")
                    result_text = "\n".join(lines) if len(lines) > 1 else f"{owner}/{repo} 暂无 {state} 状态的 issues。"
                else:
                    result_text = f"获取 issues 失败 (HTTP {status}): {data.get('message', str(data))}"
                    is_error = True

            elif tool_name == "create_issue":
                owner = tool_args.get("owner", DEFAULT_OWNER)
                repo = tool_args.get("repo", DEFAULT_REPO)
                title = tool_args.get("title", "")
                body = tool_args.get("body", "")
                labels = tool_args.get("labels", [])
                payload = {"title": title, "body": body}
                if labels:
                    payload["labels"] = labels
                status, data = gh_api("POST", f"/repos/{owner}/{repo}/issues", payload)
                if status == 201:
                    result_text = f"✅ Issue 创建成功!\n  #{data['number']}: {data['title']}\n  {data['html_url']}"
                else:
                    result_text = f"创建 issue 失败 (HTTP {status}): {data.get('message', str(data))}"
                    is_error = True

            elif tool_name == "search_code":
                query = tool_args.get("query", "")
                per_page = min(tool_args.get("per_page", 10), 30)
                qs = urllib.parse.urlencode({"q": query, "per_page": per_page})
                status, data = gh_api("GET", f"/search/code?{qs}")
                if status == 200:
                    items = data.get("items", [])
                    lines = [f"🔍 代码搜索 \"{query}\" — 共 {data.get('total_count', 0)} 个结果:"]
                    for item in items:
                        lines.append(f"  • {item['repository']['full_name']}: {item['path']}")
                        lines.append(f"    {item['html_url']}")
                    result_text = "\n".join(lines) if len(lines) > 1 else "未找到匹配的代码。"
                else:
                    result_text = f"代码搜索失败 (HTTP {status}): {data.get('message', str(data))}"
                    is_error = True

            elif tool_name == "get_user_info":
                username = tool_args.get("username", "")
                if username:
                    status, data = gh_api("GET", f"/users/{username}")
                else:
                    status, data = gh_api("GET", "/user")
                if status == 200:
                    result_text = (
                        f"👤 GitHub 用户: {data.get('login', '?')}\n"
                        f"  名称: {data.get('name', 'N/A')}\n"
                        f"  Bio: {data.get('bio', 'N/A')}\n"
                        f"  公开仓库: {data.get('public_repos', 0)}\n"
                        f"  Followers: {data.get('followers', 0)}\n"
                        f"  URL: {data.get('html_url', '')}"
                    )
                else:
                    result_text = f"获取用户信息失败 (HTTP {status}): {data.get('message', str(data))}"
                    is_error = True

            else:
                result_text = f"错误: 未知工具 [{tool_name}]。可用工具: {', '.join(t['name'] for t in TOOLS)}"
                is_error = True

            send({
                "jsonrpc": "2.0",
                "id": req_id,
                "result": {
                    "content": [{"type": "text", "text": result_text}],
                    "isError": is_error
                }
            })

        # ── ping ──
        elif method == "ping":
            send({"jsonrpc": "2.0", "id": req_id, "result": {}})

        # ── 未知方法 ──
        else:
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
