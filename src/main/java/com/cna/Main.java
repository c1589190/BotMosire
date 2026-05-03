package com.cna;

import com.cna.agent.AgentInput.DefaultAgentInputUnit;
import com.cna.agent.LivingLoop;
import com.cna.config.ConfigsManager;
import com.cna.plugin.PluginsManager;
import lombok.extern.slf4j.Slf4j;

import java.net.URISyntaxException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

@Slf4j
public class Main {

    public static BlockingQueue<DefaultAgentInputUnit> AgentInputTasksQueue = new LinkedBlockingQueue<>(4096);

    public static NapcatAdapter GlobalNapcatAdapter;
    public static LivingLoop loop = new LivingLoop();
    // 声明插件管理器
    public static PluginsManager pluginsManager;

    public static void main(String[] args){

        ConfigsManager.init();

        try {
            GlobalNapcatAdapter = new NapcatAdapter();
            GlobalNapcatAdapter.connect();
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }

        // 1. 启动核心循环
        loop.start();

        // 2. 启动插件管理器，把 loop (实现了 MosireAPI) 传给它！
        pluginsManager = new PluginsManager(loop);
        pluginsManager.loadPlugins();

        // 3. 注册 JVM 关闭钩子 (安全停机)
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("[System] 正在关闭系统，准备安全卸载插件...");
            if (pluginsManager != null) {
                pluginsManager.disableAll();
            }
            loop.stop();
        }));
    }
}