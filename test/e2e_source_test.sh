#!/bin/bash
# ============================================================
# 深度记忆来源追溯 — 端到端实机测试脚本
#
# 使用方法：
#   cd BotMosire
#   ./test/e2e_source_test.sh
#
# 流程：
#   1. 用测试 DB 启动 BotMosire
#   2. 通过 stdin 注入若干轮 ChatMessageInput（模拟群聊/私聊）
#   3. 监控 LLM 的 prompt/response 中是否出现 [DM-N] 和来源格式
#   4. 等待 consolidation 触发
#   5. 查询 DB 验证 sources 列数据
# ============================================================

set -e

BOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
TEST_DB="$BOT_DIR/test_memory.db"
LOG_FILE="$BOT_DIR/test/e2e_test_$(date +%Y%m%d_%H%M%S).log"

echo "╔══════════════════════════════════════════════════╗"
echo "║  深度记忆来源追溯 — 端到端实机测试                   ║"
echo "╚══════════════════════════════════════════════════╝"
echo ""

# 清理旧的测试 DB
rm -f "$TEST_DB" "$TEST_DB-shm" "$TEST_DB-wal"
echo "[E2E] 已清理测试 DB"

# 1. 运行 Java 预填充（通过 Maven exec）
echo ""
echo "─── 第 1 步: 运行集成测试（含数据预填充 + 验证）───"
cd "$BOT_DIR"
mvn test-compile exec:java -Dexec.mainClass="com.cna.test.SourceTrackingIntegrationTest" \
    -Dexec.classpathScope=test -q 2>&1 | grep -E "✅|❌|║|阶段|通过|失败" || true

echo ""
echo "══════════════════════════════════════════════════"
echo "  以上是集成测试结果（不含真实 LLM 交互）"
echo ""
echo "  要测试完整链路（含 LLM 交互），请："
echo "    1. 启动 BotMosire:  mvn exec:java -Dexec.mainClass=com.cna.Main -q"
echo "    2. 在控制台输入 send 命令发测试消息"
echo "    3. 观察日志中的 [DM-N] 和来源标注"
echo "    4. 任务结束后查询 DB"
echo ""
echo "  DB 查询命令："
echo "    sqlite3 agent_memory.db 'SELECT id, substr(content,1,60), sources FROM Deep_Memorys;'"
echo "    sqlite3 agent_memory.db 'SELECT id, substr(content,1,60), sources FROM Current_Memorys;'"
echo "══════════════════════════════════════════════════"
