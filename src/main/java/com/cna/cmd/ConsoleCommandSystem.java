package com.cna.cmd;

import com.cna.agent.AgentTask.ConsoleChatTask;
import com.cna.agent.AgentTask.DefaultAgentTaskUnit;
import com.cna.agent.LivingLoop; // 确保引入了 LivingLoop
import lombok.extern.slf4j.Slf4j;
import java.util.Scanner;

import static com.cna.Main.loop;

@Slf4j
public class ConsoleCommandSystem {

    private Thread commandThread;

    public void start() {
        commandThread = new Thread(() -> {
            Scanner scanner = new Scanner(System.in);
            log.info("[Console] 命令行调试系统已启动。");
            log.info("[Console] 支持的命令: ");
            log.info("  - send <消息内容> : 直接向 Agent 发送控制台消息");
            log.info("  - exit / stop   : 安全结束进程");

            // 持续监听控制台输入
            while (!Thread.currentThread().isInterrupted() && scanner.hasNextLine()) {
                String input = scanner.nextLine().trim();

                // 忽略空回车
                if (input.isEmpty()) {
                    continue;
                }

                // 1. 内置的停机指令
                if ("exit".equalsIgnoreCase(input) || "stop".equalsIgnoreCase(input)) {
                    log.info("[Console] 收到退出指令，准备安全结束进程...");
                    System.exit(0);
                    break;
                }

                // 2. 解析 send 命令
                // 将输入转换为小写比对前缀，确保 Send/SEND 都能识别，并加个空格防止匹配到 sendxxx
                if (input.toLowerCase().startsWith("send ")) {
                    // 截取 "send " 之后的所有内容作为 payload
                    String payload = input.substring(5).trim();

                    if (payload.isEmpty()) {
                        log.warn("[Console] send 命令缺少内容。用法示例: send 你好，测试一下");
                        continue;
                    }

                    log.info("[Console] 收到 send 命令，正在构建并注入 ConsoleChatTask...");

                    // 实例化任务
                    ConsoleChatTask consoleTask = new ConsoleChatTask(payload);

                    try {
                        loop.pushTask(consoleTask);
                        log.info("[Console] 任务注入成功！UUID: {}", consoleTask.getUUID());
                    } catch (Exception e) {
                        log.error("[Console] 任务注入失败，请检查 TaskQueue 状态", e);
                    }

                    continue;
                } else if(input.toLowerCase().startsWith("test_task ")) {
                    // 截取 "testtask " 之后的所有内容
                    String payload = input.substring(10).trim();

                    if (payload.isEmpty()) {
                        log.warn("[Console] testTask 命令缺少内容。用法示例: testTask ScheduledTask [可选参数]");
                        continue;
                    }

                    log.info("[Console] 收到 testTask 命令，准备动态实例化任务: {}", payload);

                    // 1. 解析类名和可选参数（以空格分隔，第一个词是类名，后面全算作参数）
                    String[] parts = payload.split("\\s+", 2);
                    String taskClassName = parts[0];
                    String taskParam = parts.length > 1 ? parts[1] : null;

                    // 2. 自动补全默认包名（如果用户没写全限定类名）
                    String fullClassName = taskClassName;
                    if (!taskClassName.contains(".")) {
                        fullClassName = "com.cna.agent.AgentTask." + taskClassName;
                    }

                    try {
                        // 3. 动态加载类
                        Class<?> taskClass = Class.forName(fullClassName);

                        // 4. 类型安全检查：确保它是 DefaultAgentTaskUnit 的实现类
                        if (!DefaultAgentTaskUnit.class.isAssignableFrom(taskClass)) {
                            log.error("[Console] 错误：{} 不是合法的 Agent 任务类 (未实现 DefaultAgentTaskUnit)", fullClassName);
                            continue;
                        }

                        DefaultAgentTaskUnit task = null;

                        // 5. 根据是否传入了参数，动态选择构造函数
                        try {
                            if (taskParam != null) {
                                // 尝试寻找并调用 public XxxTask(String text) 构造函数
                                task = (DefaultAgentTaskUnit) taskClass.getDeclaredConstructor(String.class).newInstance(taskParam);
                            } else {
                                // 尝试寻找并调用无参构造 public XxxTask()
                                task = (DefaultAgentTaskUnit) taskClass.getDeclaredConstructor().newInstance();
                            }
                        } catch (NoSuchMethodException e) {
                            // 构造函数不匹配的降级容错
                            if (taskParam != null) {
                                log.warn("[Console] 找不到 {} 的带参(String)构造函数，将尝试使用无参构造...", taskClassName);
                                task = (DefaultAgentTaskUnit) taskClass.getDeclaredConstructor().newInstance();
                            } else {
                                log.error("[Console] 找不到 {} 的无参构造函数，可能需要传入参数？", taskClassName);
                                continue;
                            }
                        }

                        // 6. 注入任务队列
                        loop.pushTask(task);
                        log.info("[Console] 🛠️ 测试任务注入成功！类型: {}, UUID: {}", taskClass.getSimpleName(), task.getUUID());

                    } catch (ClassNotFoundException e) {
                        log.error("[Console] 找不到任务类: {}。请检查拼写，或者使用完整的包名。", fullClassName);
                    } catch (Exception e) {
                        log.error("[Console] 任务实例化或注入失败，详细原因:", e);
                    }
                    continue;
                }

                // 3. 未知命令兜底
                log.warn("[Console] 未知命令: {}", input);
            }
        }, "Console-Command-Thread");

        commandThread.setDaemon(true);
        commandThread.start();
    }
}