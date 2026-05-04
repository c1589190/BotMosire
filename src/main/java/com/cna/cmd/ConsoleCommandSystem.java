package com.cna.cmd;

import com.cna.agent.AgentTask.ConsoleChatTask;
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

                    // 【关键入队逻辑】：根据你的双端队列实现，通常使用 addLast 或 offerLast
                    // 注意：这里假设你在 LivingLoop 中把 TaskQueue 设为了 public static
                    // 如果是封装的方法，请改为类似 LivingLoop.submitTask(consoleTask)
                    try {
                        loop.pushTask(consoleTask);
                        log.info("[Console] 任务注入成功！UUID: {}", consoleTask.getUUID());
                    } catch (Exception e) {
                        log.error("[Console] 任务注入失败，请检查 TaskQueue 状态", e);
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