package com.gpsbt.transfer.rx;

import com.gpsbt.transfer.nmea.NmeaChecksum;
import com.gpsbt.transfer.nmea.NmeaGenerator;
import com.gpsbt.transfer.nmea.NmeaInput;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * NMEA 语句解析器：把一行行语句还原成定位快照，与 {@link NmeaGenerator} 互为逆运算。
 *
 * 纯逻辑、无 Android 依赖，可单测。
 *
 * 多句合并：GGA 给位置 / 海拔 / 使用卫星数，RMC 给速度 / 航向，GSV 给可见卫星，
 * GSA 给「哪几颗用于定位」。这些语句在一个定位周期里先后到达，因此解析器**累积**
 * 状态，只在收到 GGA 或 RMC（两句都带位置与有效性）时对外发布快照。
 * 同一周期发布两次无害——注入方只是把同一个位置再写一遍。
 */
public final class NmeaParser {

    private static final double KNOTS_TO_MS = 1.0d / NmeaGenerator.MS_TO_KNOTS;
    private static final int MIN_SENTENCE_FIELD_LENGTH = 5;

    /** 卫星累积项。{@link NmeaInput.Sat} 的 used 是 final，所以先在可变结构里攒，发布时再构造。 */
    private static final class SatEntry {
        final int prn;
        final int elevation;
        final int azimuth;
        final int snr;
        final String talker;
        boolean used;

        SatEntry(int prn, int elevation, int azimuth, int snr, String talker) {
            this.prn = prn;
            this.elevation = elevation;
            this.azimuth = azimuth;
            this.snr = snr;
            this.talker = talker;
        }
    }

    private final NmeaInput state = new NmeaInput();
    private final List<SatEntry> entries = new ArrayList<>();
    private final Set<Integer> usedPrns = new HashSet<>();
    /**
     * 本周期是否已有 GGA（或 RMC）设过位置。GSV 到达时用 state 里的位置
     * 配合 entries 里的卫星打包成快照。
     *
     * 不用"发布过"标志——GSV 在一个周期里会到多次（多星座 / 多页），
     * 每次到达都要发布一次，最后一次携带完整卫星列表。中间值会被后续
     * GSV 覆盖，所以 UI 最终显示的是正确数字。
     */
    private boolean hasFix;

    /** 清空累积状态（断线重连时调用）。 */
    public void reset() {
        state.valid = false;
        state.utcMillis = 0L;
        state.latitude = 0d;
        state.longitude = 0d;
        state.altitudeM = 0d;
        state.speedMps = 0d;
        state.bearingDeg = 0d;
        state.accuracyM = Float.NaN;
        state.satellitesUsed = 0;
        state.clearSats();
        entries.clear();
        usedPrns.clear();
        hasFix = false;
    }

    /**
     * 喂入一行。
     *
     * 发布策略：本周期 GGA（也接受 RMC）到达后标记已就位；之后每条 GSV 都
     * 用当前 {@code state}（位置 + 速度 + satellitesUsed）加上累积的
     * {@code entries} 打包成快照发布。多次 GSV 覆盖式发布，最后一条携带
     * 本周期全部卫星。{@code satellitesUsed} 与 {@code satellitesInView}
     * 始终来自同一周期。
     *
     * 边界：若 GSV 被禁用，GSV 不到，hasFix 一直为 true；下一周期 GGA 来了
     * 会把上一周期的"占位"发出（sats 为空），保证不会卡死。
     *
     * @return 快照（GSV 每条都返，其他句子返 null），或 null
     */
    public NmeaInput accept(String line) {
        if (line == null) {
            return null;
        }
        String text = line.trim();
        if (text.isEmpty() || text.charAt(0) != '$') {
            return null;
        }
        String body = verifiedBody(text);
        if (body == null) {
            return null;
        }
        String[] f = body.split(",", -1);
        if (f.length == 0 || f[0].length() < MIN_SENTENCE_FIELD_LENGTH) {
            return null;
        }
        // 形如 GPGGA / BDGSV：末三位是语句类型
        String type = f[0].substring(f[0].length() - 3).toUpperCase(Locale.US);
        if ("GGA".equals(type)) {
            // 新一周期的确定起点：先把上一周期遗留的"占位"发出去（覆盖式），
            // 然后清空累积。
            NmeaInput leftover = publishIfHasFix();
            entries.clear();
            usedPrns.clear();
            if (applyGga(f)) {
                hasFix = true;
            }
            return leftover;
        }
        if ("RMC".equals(type)) {
            if (applyRmc(f)) {
                hasFix = true;
            }
            return null;
        }
        if ("GSA".equals(type)) {
            applyGsa(f);
            return null;
        }
        if ("GSV".equals(type)) {
            // 不在这里清 entries：多星座的 GSV 是按 talker 分组的，每组
            // 都有自己的 page 1，若按 page<=1 清会把前一个星座的累积数据全清掉。
            applyGsv(f);
            // GSV 到了：连同当前累积的卫星列表一起发布。多星座多页会发布多次，
            // 最后一次携带完整卫星列表——把中间值覆盖掉。
            return publishIfHasFix();
        }
        return null;
    }

    /** 用 state（位置 + satellitesUsed）与当前 entries 打包快照；hasFix=false 时返 null。 */
    private NmeaInput publishIfHasFix() {
        if (!hasFix) {
            return null;
        }
        NmeaInput snap = state.copy();
        snap.clearSats();
        for (SatEntry entry : entries) {
            snap.addSat(new NmeaInput.Sat(entry.prn, entry.elevation, entry.azimuth,
                    entry.snr, entry.used, entry.talker));
        }
        return snap;
    }

    /** 去掉 $ 前缀并校验 *CS；校验失败或格式不符返回 null。 */
    private static String verifiedBody(String sentence) {
        int star = sentence.indexOf('*');
        if (star <= 1) {
            return null;
        }
        String body = sentence.substring(1, star);
        String given = sentence.substring(star + 1).trim();
        if (given.length() < 2) {
            return null;
        }
        String expected = NmeaChecksum.toHex(NmeaChecksum.xor(body));
        return expected.equalsIgnoreCase(given.substring(0, 2)) ? body : null;
    }

    private boolean applyGga(String[] f) {
        if (f.length < 10) {
            return false;
        }
        state.valid = parseInt(f[6], 0) > 0;
        long time = parseUtcTime(f[1]);
        if (time > 0) {
            state.utcMillis = time;
        }
        double lat = parseCoordinate(f[2], f[3], false);
        double lon = parseCoordinate(f[4], f[5], true);
        if (!Double.isNaN(lat)) {
            state.latitude = lat;
        }
        if (!Double.isNaN(lon)) {
            state.longitude = lon;
        }
        state.satellitesUsed = Math.max(0, parseInt(f[7], 0));
        state.altitudeM = parseDouble(f[9], 0d);
        double hdop = parseDouble(f[8], Double.NaN);
        // 与生成器对称：hdop = accuracy / 5
        state.accuracyM = Double.isNaN(hdop) ? Float.NaN : (float) (hdop * 5.0d);
        return true;
    }

    private boolean applyRmc(String[] f) {
        if (f.length < 10) {
            return false;
        }
        boolean active = "A".equalsIgnoreCase(f[2]);
        state.valid = active;
        long time = parseUtcTime(f[1]);
        long date = parseUtcDate(f[9]);
        if (time > 0 && date > 0) {
            state.utcMillis = date + time;
        } else if (time > 0) {
            state.utcMillis = time;
        }
        double lat = parseCoordinate(f[3], f[4], false);
        double lon = parseCoordinate(f[5], f[6], true);
        if (!Double.isNaN(lat)) {
            state.latitude = lat;
        }
        if (!Double.isNaN(lon)) {
            state.longitude = lon;
        }
        double knots = parseDouble(f[7], 0d);
        state.speedMps = active ? Math.max(0d, knots * KNOTS_TO_MS) : 0d;
        double course = parseDouble(f[8], 0d);
        state.bearingDeg = active ? normalize360(course) : 0d;
        return true;
    }

    private void applyGsv(String[] f) {
        if (f.length < 4) {
            return;
        }
        // 关键：不在这里清 entries。多星座的 GSV 是按 talker 分组的，每组
        // 都有自己的 page 1，若按 page<=1 清会把前一个星座的累积数据全清掉。
        // 清的工作交给 GGA（一个周期的确定起点）去做。
        String talker = f[0].length() >= 2 ? f[0].substring(0, 2) : null;
        for (int i = 4; i + 3 < f.length; i += 4) {
            int prn = parseInt(f[i], -1);
            if (prn <= 0) {
                continue;
            }
            SatEntry entry = new SatEntry(prn, parseInt(f[i + 1], 0),
                    parseInt(f[i + 2], 0), parseInt(f[i + 3], 0), talker);
            // GSA 可能先于 GSV 到达，用记住的「已用于定位」集合补上标记
            entry.used = usedPrns.contains(prn);
            entries.add(entry);
        }
    }

    private void applyGsa(String[] f) {
        if (f.length < 3) {
            return;
        }
        usedPrns.clear();
        // GSA 的 12 个 PRN 槽位在 f[3]..f[14]
        for (int i = 3; i < f.length && i <= 14; i++) {
            int prn = parseInt(f[i], -1);
            if (prn > 0) {
                usedPrns.add(prn);
            }
        }
        for (SatEntry entry : entries) {
            entry.used = usedPrns.contains(entry.prn);
        }
    }

    /** ddmm.mmmm + 半球字母 → 十进制度；半球缺失时按正数处理。 */
    private static double parseCoordinate(String value, String hemisphere, boolean longitude) {
        if (value == null || value.trim().isEmpty()) {
            return Double.NaN;
        }
        double raw;
        try {
            raw = Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
        // 整数部分就是度：纬度 2 位、经度 3 位
        double degrees = Math.floor(raw / 100.0d);
        double minutes = raw - degrees * 100.0d;
        double result = degrees + minutes / 60.0d;
        if (hemisphere != null) {
            String h = hemisphere.trim().toUpperCase(Locale.US);
            if ("S".equals(h) || "W".equals(h)) {
                result = -result;
            }
        }
        return result;
    }

    /** HHmmss.SS → UTC 当日毫秒数；无法解析返回 0。 */
    private static long parseUtcTime(String value) {
        if (value == null || value.length() < 6) {
            return 0L;
        }
        try {
            int hour = Integer.parseInt(value.substring(0, 2));
            int minute = Integer.parseInt(value.substring(2, 4));
            int second = Integer.parseInt(value.substring(4, 6));
            long millis = (long) hour * 3600_000L + (long) minute * 60_000L + second * 1000L;
            if (value.length() > 7) {
                double fraction = Double.parseDouble("0" + value.substring(6));
                millis += Math.round(fraction * 1000.0d);
            }
            return millis;
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** ddMMyy → UTC 当日零点毫秒时间戳；无法解析返回 0。 */
    private static long parseUtcDate(String value) {
        if (value == null || value.length() < 6) {
            return 0L;
        }
        try {
            int day = Integer.parseInt(value.substring(0, 2));
            int month = Integer.parseInt(value.substring(2, 4));
            int year = 2000 + Integer.parseInt(value.substring(4, 6));
            java.util.Calendar calendar = java.util.Calendar.getInstance(
                    java.util.TimeZone.getTimeZone("UTC"), Locale.US);
            calendar.clear();
            calendar.set(year, month - 1, day, 0, 0, 0);
            return calendar.getTimeInMillis();
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static int parseInt(String value, int fallback) {
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double parseDouble(String value, double fallback) {
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double normalize360(double value) {
        double v = value % 360.0d;
        return v < 0 ? v + 360.0d : v;
    }
}
