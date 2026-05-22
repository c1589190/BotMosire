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

    // 天级格式：2026年4月22日，用于 LLM prompt 中以提升 DeepSeek KV-cache 命中率
    private static final DateTimeFormatter CHINESE_DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy年M月d日");

    // 精确到秒的格式：2026年4月22日22:30:00
    private static final DateTimeFormatter CHINESE_PRECISE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy年M月d日HH:mm:ss");

    /**
     * 获取当前系统时间并转化为中文天级格式（如: 2026年4月22日）
     * 用于 AgentInput 的时间戳标记、日志输出以及 LLM prompt 中的 now_time 变量。
     * 
     * 【缓存优化】采用天级粒度替代分钟级，大幅提升 DeepSeek prefix-cache 命中率。
     */
    public static String getNowFormatted() {
        return LocalDateTime.now().format(CHINESE_DATE_FORMATTER);
    }

    /**
     * 获取当前系统时间并转化为中文精确到秒的格式（如: 2026年4月22日22:30:00）
     * 用于需要精确时间戳的场景，如日志记录、工具返回等。
     */
    public static String getNowPrecise() {
        return LocalDateTime.now().format(CHINESE_PRECISE_FORMATTER);
    }

    /**
     * 重载方法：将指定的毫秒数转化为中文天级格式
     * 适合处理从 Napcat 传来的原始 'time' 字段
     */
    public static String formatTimestamp(long timestampInSeconds) {
        // OneBot 协议通常传的是秒级时间戳，这里做个转换
        return java.time.Instant.ofEpochSecond(timestampInSeconds)
                .atZone(java.time.ZoneId.systemDefault())
                .format(CHINESE_DATE_FORMATTER);
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