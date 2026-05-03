package com.cna.plugin;

import lombok.extern.slf4j.Slf4j;
import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

@Slf4j
public class PluginsManager {

    private final List<BotPlugin> activePlugins = new ArrayList<>();
    private final File pluginFolder = new File("plugins");
    private final MosireAPI api; // 主程序传进来的底层能力实现

    public PluginsManager(MosireAPI api) {
        this.api = api;
    }

    public void loadPlugins() {
        if (!pluginFolder.exists()) {
            pluginFolder.mkdirs();
            log.info("[PluginManager] 已创建 plugins 文件夹，等待插件载入...");
            return;
        }

        File[] jarFiles = pluginFolder.listFiles((dir, name) -> name.endsWith(".jar"));
        if (jarFiles == null || jarFiles.length == 0) {
            log.info("[PluginManager] 没有找到任何外部插件。");
            return;
        }

        try {
            // 1. 把所有 jar 文件的路径转换成 URL
            URL[] urls = new URL[jarFiles.length];
            for (int i = 0; i < jarFiles.length; i++) {
                urls[i] = jarFiles[i].toURI().toURL();
            }

            // 2. 创建一个专属的类加载器，让 Java 认识这些外部的 class
            URLClassLoader pluginClassLoader = new URLClassLoader(urls, this.getClass().getClassLoader());

            // 3. 核心机制：利用 SPI 自动发现所有实现了 BotPlugin 的类
            ServiceLoader<BotPlugin> loader = ServiceLoader.load(BotPlugin.class, pluginClassLoader);

            for (BotPlugin plugin : loader) {
                try {
                    log.info("[PluginManager] 正在装载插件: {} ...", plugin.getName());
                    // 4. 调用插件的启动方法，并把 API 塞给它
                    plugin.onEnable(this.api);
                    activePlugins.add(plugin);
                    log.info("[PluginManager] 插件 {} 已启动完毕。", plugin.getName());
                } catch (Exception e) {
                    log.error("[PluginManager] 插件 {} 启动失败！", plugin.getName(), e);
                }
            }
        } catch (Exception e) {
            log.error("[PluginManager] 读取 plugins 目录发生严重错误", e);
        }
    }

    public void disableAll() {
        for (BotPlugin plugin : activePlugins) {
            try {
                plugin.onDisable();
                log.info("[PluginManager] 插件 {} 已安全卸载。", plugin.getName());
            } catch (Exception e) {
                log.error("[PluginManager] 卸载插件 {} 时出错", plugin.getName(), e);
            }
        }
        activePlugins.clear();
    }
}