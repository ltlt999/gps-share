package com.gpsbt.transfer.util;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** 中文界面格式化，所有输出均为面向用户的可读文本。 */
public final class Formatters {

    private Formatters() {
    }

    public static String latitude(double value) {
        return String.format(Locale.US, "%.6f° %s", Math.abs(value), value >= 0 ? "N" : "S");
    }

    public static String longitude(double value) {
        return String.format(Locale.US, "%.6f° %s", Math.abs(value), value >= 0 ? "E" : "W");
    }

    public static String speedKmh(double metersPerSecond) {
        if (Double.isNaN(metersPerSecond)) {
            return "NaN";
        }
        return String.format(Locale.US, "%.1f km/h", Math.max(0d, metersPerSecond) * 3.6);
    }

    public static String bearing(double degrees) {
        if (Double.isNaN(degrees)) {
            return "--";
        }
        return String.format(Locale.US, "%.1f°", degrees);
    }

    public static String altitude(double meters) {
        if (Double.isNaN(meters)) {
            return "--";
        }
        return String.format(Locale.US, "%.1f m", meters);
    }

    public static String accuracy(float meters) {
        if (Float.isNaN(meters) || meters <= 0f) {
            return "--";
        }
        return String.format(Locale.US, "±%.1f m", meters);
    }

    /** 时长格式化为 HH:mm:ss，负数返回 --。 */
    /** 字节量 → 人类可读（B / KB / MB），超过 1 MB 保留一位小数。 */
    public static String bytes(long value) {
        if (value < 1024L) {
            return value + " B";
        }
        double kb = value / 1024.0d;
        if (kb < 1024.0d) {
            return String.format(Locale.US, "%.1f KB", kb);
        }
        return String.format(Locale.US, "%.1f MB", kb / 1024.0d);
    }

    public static String duration(long millis) {
        if (millis < 0) {
            return "--";
        }
        long totalSeconds = millis / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds);
    }

    /** 时间戳当天时刻 HH:mm:ss（本地时区）。 */
    public static String clockOfDay(long millis) {
        SimpleDateFormat format = new SimpleDateFormat("HH:mm:ss", Locale.US);
        format.setTimeZone(TimeZone.getDefault());
        return format.format(new Date(millis));
    }
}
