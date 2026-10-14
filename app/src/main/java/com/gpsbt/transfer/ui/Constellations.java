package com.gpsbt.transfer.ui;

import android.content.Context;

import com.gpsbt.transfer.R;

import java.util.Locale;

/**
 * 卫星所属星座 → 国旗图标与中文名。
 *
 * NMEA 的 talker 前两位就是星座代号，与 Android GnssStatus 的 svid 区间一致：
 * <pre>
 *   GP  GPS      美国
 *   BD  北斗      中国
 *   GL  GLONASS  俄罗斯
 *   GA  Galileo  欧盟
 *   GQ  QZSS     日本
 *   GI  NavIC    印度
 * </pre>
 *
 * 图标是**简化版**国旗：21×14 dp 下细节不可辨，只保留可辨识特征
 * （俄罗斯三横条、日本红圆、中国红底黄星……）。这样不依赖系统的 emoji
 * 字体——部分国产 ROM 不渲染国旗 emoji，会退化成两个字母。
 */
public final class Constellations {

    private Constellations() {
    }

    /** talker（如 "GP"）→ 国旗图标资源；未知或为空时返回中性占位旗。 */
    public static int flagResOf(String talker) {
        if (talker == null) {
            return R.drawable.ic_flag_unknown;
        }
        switch (talker.trim().toUpperCase(Locale.US)) {
            case "GP":
                return R.drawable.ic_flag_gp;
            case "BD":
                return R.drawable.ic_flag_bd;
            case "GL":
                return R.drawable.ic_flag_gl;
            case "GA":
                return R.drawable.ic_flag_ga;
            case "GQ":
                return R.drawable.ic_flag_gq;
            case "GI":
                return R.drawable.ic_flag_gi;
            default:
                return R.drawable.ic_flag_unknown;
        }
    }

    /** talker → 星座中文名；未知或为空时返回「其他」。 */
    public static String nameOf(Context context, String talker) {
        if (talker == null) {
            return context.getString(R.string.constellation_other);
        }
        switch (talker.trim().toUpperCase(Locale.US)) {
            case "GP":
                return context.getString(R.string.constellation_gps);
            case "BD":
                return context.getString(R.string.constellation_beidou);
            case "GL":
                return context.getString(R.string.constellation_glonass);
            case "GA":
                return context.getString(R.string.constellation_galileo);
            case "GQ":
                return context.getString(R.string.constellation_qzss);
            case "GI":
                return context.getString(R.string.constellation_navic);
            default:
                return context.getString(R.string.constellation_other);
        }
    }
}
