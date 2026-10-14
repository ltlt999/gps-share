package com.gpsbt.transfer.state;

import java.util.Locale;

/**
 * 把一行 NMEA 语句按类别记进日志：卫星语句（GSV / GSA）单独一类，便于筛选。
 *
 * 发送端记的是**发出去的**语句，接收端记的是**收到的**语句——两边用同一套
 * 分类规则，日志页的「卫星 / 数据」筛选在两个角色下才都有内容可看。
 */
public final class NmeaLog {

    private NmeaLog() {
    }

    /** 记一行；null 或空白直接忽略。 */
    public static void sentence(String sentence) {
        String text = sentence == null ? "" : sentence.trim();
        if (text.isEmpty()) {
            return;
        }
        LogBus.get().log(isSatellite(text.toUpperCase(Locale.US))
                ? LogBus.Level.SAT : LogBus.Level.DATA, text);
    }

    /**
     * 是否为卫星相关语句：GSV（可见卫星明细）与 GSA（精度因子 / 已用于定位的卫星）。
     *
     * @param upperCaseSentence 已转大写的语句
     */
    public static boolean isSatellite(String upperCaseSentence) {
        return upperCaseSentence.contains("GSV") || upperCaseSentence.contains("GSA");
    }
}
