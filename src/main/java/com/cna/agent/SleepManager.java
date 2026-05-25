package com.cna.agent;

import lombok.extern.slf4j.Slf4j;

/**
 * 全局休眠管理器（单例），按每天的时间段控制休眠。
 * 休眠期间 LivingLoop 跳过所有 Input 处理，相当于 Agent 进入睡眠状态。
 * 支持跨天时间段（如 22:00 ~ 06:00）。
 */
@Slf4j
public class SleepManager {

    private static final SleepManager INSTANCE = new SleepManager();

    /** 休眠开始时间（分钟从 0 点起），-1 表示未设置 */
    private volatile int sleepStartMin = -1;
    /** 休眠结束时间（分钟从 0 点起），-1 表示未设置 */
    private volatile int sleepEndMin = -1;

    private SleepManager() {}

    public static SleepManager getInstance() {
        return INSTANCE;
    }

    /**
     * 当前是否处于休眠时间段内。
     */
    public boolean isSleeping() {
        int start = sleepStartMin;
        int end = sleepEndMin;
        if (start < 0 || end < 0) {
            return false;
        }

        int now = minutesSinceMidnight();

        if (start <= end) {
            // 不跨天：如 02:00 ~ 08:00
            return now >= start && now < end;
        } else {
            // 跨天：如 22:00 ~ 06:00
            return now >= start || now < end;
        }
    }

    /**
     * 设置每日休眠时间段。
     * @param start HH:mm 格式的开始时间
     * @param end   HH:mm 格式的结束时间
     */
    public void setSleepWindow(String start, String end) {
        int startMin = parseTime(start);
        int endMin = parseTime(end);
        if (startMin < 0 || endMin < 0) {
            log.warn("[SleepManager] 无效的时间格式: start={}, end={}", start, end);
            return;
        }
        if (startMin == endMin) {
            log.warn("[SleepManager] 开始和结束时间相同，将清空休眠设置");
            clearSleepWindow();
            return;
        }
        this.sleepStartMin = startMin;
        this.sleepEndMin = endMin;
        log.info("[SleepManager] 😴 休眠时间段已设置: {} ~ {} ({} min ~ {} min)",
                start, end, startMin, endMin);
    }

    /**
     * 清空休眠时间段，Agent 始终处于唤醒状态。
     */
    public void clearSleepWindow() {
        this.sleepStartMin = -1;
        this.sleepEndMin = -1;
        log.info("[SleepManager] 🔔 休眠时间段已清空，Agent 将始终保持唤醒");
    }

    /**
     * 获取当前设置的休眠时间段描述。
     */
    public String getSleepWindow() {
        int start = sleepStartMin;
        int end = sleepEndMin;
        if (start < 0 || end < 0) {
            return "未设置（始终唤醒）";
        }
        return String.format("%02d:%02d ~ %02d:%02d",
                start / 60, start % 60, end / 60, end % 60);
    }

    // ========== 工具方法 ==========

    private static int minutesSinceMidnight() {
        java.time.LocalTime now = java.time.LocalTime.now();
        return now.getHour() * 60 + now.getMinute();
    }

    public static int parseTime(String time) {
        if (time == null || time.isBlank()) {
            return -1;
        }
        try {
            String[] parts = time.trim().split(":");
            int h = Integer.parseInt(parts[0]);
            int m = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            if (h < 0 || h > 23 || m < 0 || m > 59) {
                return -1;
            }
            return h * 60 + m;
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
