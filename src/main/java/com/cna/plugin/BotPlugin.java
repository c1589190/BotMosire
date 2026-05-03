package com.cna.plugin;

public abstract class BotPlugin {
    // 插件的名字
    public abstract String getName();

    // 插件启动时被主程序调用，主程序会把 api 传给它
    public abstract void onEnable(MosireAPI api);

    // 插件关闭时调用
    public abstract void onDisable();
}