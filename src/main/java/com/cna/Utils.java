package com.cna;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 时间处理工具类
 * 负责将冰冷的系统毫秒数转化为具备人类感知的中文描述
 */
public class Utils {

    // 预设好你的目标格式：2026年4月22日22：30分
    private static final DateTimeFormatter CHINESE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy年M月d日HH：mm分");

    /**
     * 获取当前系统时间并转化为中文格式
     * 用于 AgentInput 的时间戳标记或日志输出
     */
    public static String getNowFormatted() {
        return LocalDateTime.now().format(CHINESE_FORMATTER);
    }

    /**
     * 重载方法：将指定的毫秒数转化为中文格式
     * 适合处理从 Napcat 传来的原始 'time' 字段
     */
    public static String formatTimestamp(long timestampInSeconds) {
        // OneBot 协议通常传的是秒级时间戳，这里做个转换
        return java.time.Instant.ofEpochSecond(timestampInSeconds)
                .atZone(java.time.ZoneId.systemDefault())
                .format(CHINESE_FORMATTER);
    }

    private static final int DISCORD_MAX_LEN = 1990;

    public static List<String> splitForDiscord(String text) {
        List<String> result = new ArrayList<>();
        while (text.length() > DISCORD_MAX_LEN) {
            int splitAt = text.lastIndexOf('\n', DISCORD_MAX_LEN);
            if (splitAt <= 0) splitAt = text.lastIndexOf(' ', DISCORD_MAX_LEN);
            if (splitAt <= 0) splitAt = DISCORD_MAX_LEN;
            result.add(text.substring(0, splitAt));
            text = text.substring(splitAt).stripLeading();
        }
        if (!text.isEmpty()) result.add(text);
        return result;
    }
}