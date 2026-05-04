# 🤖 BotMosire

> 让 AI 像人类一样处理信息：先过滤，再思考，最后行动。记忆要折叠，心境要传承。

---

## 📖 项目简介

BotMosire 是一个基于 Java 21 的 AI Agent 框架，专注于**拟人化认知架构**。

不同于传统的单轮请求-响应模式，BotMosire 引入了**持续认知循环**（LivingLoop），让 AI 能够：
- 像人一样选择性关注消息（而不是每条都回）
- 在后台整理记忆（潜意识折叠）
- 先思考再行动（第0轮规划）
- 主动结束对话（finish_task）

**核心理念：** 这不是「优化」，这是「拟人」。

---

## ✨ 功能特色

### 🚪 Gatekeeper（门神）
小模型快速判断「这批消息值得注意吗？」，省 token，符合人类注意力选择逻辑。
- 专有线程池 + AtomicBoolean 锁，防止连发时判断冲突
- 一次处理积压消息，只发 1 次小模型请求

### 🧠 潜意识折叠
处理 N 个任务后，后台异步调用 LLM 总结旧记忆，折叠后向量化存入深层记忆库。
- 模拟「睡眠时记忆整合」
- 折叠时注入当前心境，让过去的记忆被现在的她重新诠释

### 🎯 第0轮规划
传入空工具数组，剥夺模型调用能力，强制纯思考，输出战略路线图。
- 规划烙印在后续轮次的 turnsAddition
- 模拟「三思而后行」

### ✅ finish_task 工具
虚拟工具（在 `LivingLoop.java` 中动态注入），让模型主动结束思考循环，避免无限循环。

### ⏳ 催熟机制
静止计数超过阈值才触发回复，等待连发消息聚齐，避免碎片化回复。
- 模拟「等人把话说完」

### 🎲 10% 捞回
当所有消息都被 Gatekeeper 拦截时，10% 概率从垃圾桶捞一条。
- 避免「完全不理的冷漠感」
- 回复时心境是「勉为其难」

---

## 🛠️ 环境需求

| 依赖 | 版本 | 说明 |
|------|------|------|
| **Java** | 21+ (LTS) | 推荐 Microsoft Build of OpenJDK |
| **Maven** | 3.9.x | 用于编译打包 |
| **Napcat（仅 QQ）** | - | QQ 消息适配器，目前仅支持 QQ 平台 |



## 🚀 快速开始

### 1. 克隆项目

```bash
git clone https://github.com/c1589190/BotMosire.git
cd BotMosire
```

> 分支说明：
> - `master` — 基础框架（仅有 QQ/Napcat 适配器）
> - `test_CNA` — 社区测试分支，包含额外的功能实验

### 2. 编译打包

```bash
mvn clean package
```

首次运行会下载依赖，约需 1-2 分钟。

### 3. 配置

首次运行会自动生成 `application.properties` 模板：

```bash
java -jar target/BotMosire-Alpha26.1.jar
```

编辑 `application.properties`，至少填入：
- Napcat WebSocket 地址（如需 QQ 平台）
- LLM API Key（SiliconFlow / 其他 OpenAI 格式 API）

### 4. 运行

**Windows：**

```powershell
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
java "-Dfile.encoding=UTF-8" -jar target\BotMosire-Alpha26.1.jar
```

**Linux/macOS：**

```bash
java -jar target/BotMosire-Alpha26.1.jar
```

---

## ⚙️ 配置说明

BotMosire 采用**矩阵式 LLM 配置**，7 个角色各司其职：

| 角色 | 用途 | 默认模型（SiliconFlow） |
|------|------|---------|
| **gatekeeper** | 消息预过滤 | `Pro/deepseek-ai/DeepSeek-V3.2` |
| **planner** | 任务规划 | `Pro/deepseek-ai/DeepSeek-V3.2` |
| **brain** | 主力对话 | `Pro/deepseek-ai/DeepSeek-V3.2` |
| **advanced_brain** | 重型任务 | `Pro/deepseek-ai/DeepSeek-R1` |
| **embedding** | 向量化记忆 | `Qwen/Qwen3-Embedding-4B`（本地推荐用 `nomic-embed-text`） |
| **scheduler** | 定时任务 | `Pro/deepseek-ai/DeepSeek-V3.2` |
| **vision** | 图像理解 | `Qwen/Qwen3.6-35B-A3B` |

**配置示例：**

```properties
# Gatekeeper
llm.gatekeeper.chatModel=Pro/deepseek-ai/DeepSeek-V3.2
llm.gatekeeper.apiBase=https://api.siliconflow.cn/v1
llm.gatekeeper.apiKey=your-siliconflow-api-key

# Brain
llm.brain.chatModel=Pro/deepseek-ai/DeepSeek-V3.2
llm.brain.apiBase=https://api.siliconflow.cn/v1
llm.brain.apiKey=your-siliconflow-api-key

# Embedding（本地 Ollama 或 SiliconFlow 不支持 embedding 时）
llm.embedding.embeddingModel=nomic-embed-text
llm.embedding.apiBase=http://localhost:11434/v1
llm.embedding.apiKey=ollama
```

**架构哲学：** 轻量任务用小模型（省 token），重量任务用大模型（高质量）。

---

## 🏗️ 架构说明

### LivingLoop（生命循环）

BotMosire 采用**双线程生产者-消费者**架构：

```
[Adapter] → AgentInputTasksQueue (BlockingQueue) → [LivingLoop]
                                                     ↓
                                              [Consumer Thread]
                                                     ↓
                                              Gatekeeper → Brain → Tools
                                                     ↓
                                              finish_task?
                                                     ↓
                                              [Output] → Adapter
```

**关键组件：**

- **AgentInputTasksQueue**：阻塞队列，生产者（Adapter）推入消息，消费者（LivingLoop）取出处理
- **Consumer Thread**：持续运行的认知循环，负责思考、工具调用、回复生成
- **PluginManager**：插件加载器，支持 ChatTaskHandler、ScheduledTaskHandler 等

### 认知参数

```properties
cognitive.cycleTicks=8000        # 心跳间隔（ms），达到此时间触发 Gatekeeper 感知循环
cognitive.messageWaitingTime=5   # 静默秒数，超过则催熟触发（等人把话说完）
cognitive.consumerCyclingTime=10 # 单次消费循环的最大思考轮次
cognitive.taskCountForReflection=10 # 处理多少任务后触发潜意识记忆折叠
```

---

## 📝 已知问题

- **适配器扩展**：当前适配器为 Napcat（QQ），如需其他平台可参考源码自行扩展
- **Embedding 404**：确保 `llm.embedding.apiBase` 指向 Ollama（`http://localhost:11434/v1`），DeepSeek API 不支持 embedding 端点
- **中文日志乱码**：Windows PowerShell 需设置 UTF-8 编码（见「快速开始 - 运行」）

---

## 📜 许可证

本项目遵循 Apache 2.0 许可证。

---

## 🙏 致谢

感谢所有为 BotMosire 贡献代码和建议的朋友。

如果这个项目对你有帮助，欢迎 Star ⭐
