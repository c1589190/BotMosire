# BotMosire

基于 Java 21 的 AI Agent 框架（本分支含 QQ / Napcat 与 Discord 等适配器）。

## 环境

- **Java** 21+
- **Maven** 3.9+（仅在你需要从源码打 JAR 时需要）

## 快速开始

核心就一件事：**在「你希望生成配置文件的目录」里执行 `java -jar …`**。  
`application.properties` 会写在**当前工作目录**（你终端所在的文件夹），**不会**自动跟 JAR 文件放在同一路径——除非你先把终端 `cd` 到 JAR 旁边。

### 方式一：直接使用 JAR（推荐给使用者）

1. 从 Release 下载 `BotMosire-Alpha26.1.1.jar`（或他人提供的同名制品），放进一个空文件夹，例如 `D:\BotMosire\`。
2. 在该文件夹打开终端并进入该目录：

   ```bash
   cd /d D:\BotMosire
   ```

3. 运行（首次若不存在配置，会从模板生成 `application.properties` 并启动程序）：

   ```bash
   java -jar BotMosire-Alpha26.1.1.jar
   ```

4. 若需先改配置：用 `Ctrl+C` 结束进程，用编辑器打开**同目录下**的 `application.properties`，填入 Napcat / Discord / LLM 等项后，再执行第 3 步。

**Windows 控制台中文乱码时**（可选）：

```powershell
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
java "-Dfile.encoding=UTF-8" -jar BotMosire-Alpha26.1.1.jar
```

### 方式二：从源码构建 JAR（推荐给开发者）

```bash
cd BotMosire-test_CNA-new   # 或你的本地克隆目录
mvn clean package -q
```

可执行包路径：

```text
target/BotMosire-Alpha26.1.1.jar
```

之后与**方式一**相同：先 `cd` 到你希望存放配置的目录，再 `java -jar …\target\BotMosire-Alpha26.1.1.jar`（或把 JAR 复制过去再运行）。

---

说明：首次运行不会「只生成配置就退出」，而是正常启动；若未配置 Napcat / Discord，日志里可能出现连接失败提示，程序仍会继续按配置尝试运行。
