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
 * <p><b>发布时机</b>（v2.0.0 起）：
 * <ul>
 *   <li>位置在 <b>RMC</b> 处发布 —— RMC 带着本周期的速度与航向，而 GGA（经纬度、高度）
 *       就在它前一句。此刻发布 = 位置、速度、航向、高度全是本周期的值。
 *   <li>卫星在 <b>GSV</b> 处发布（与 v1.6.2 一致）。
 *   <li>某一帧没有 RMC（用户关了 RMC 语句）时，位置拖到<b>下一帧开头</b>补发 ——
 *       宁可晚 1 秒，也要给完整的一份数据。「速度航向是上一周期、其余是本周期」
 *       的混合数据比晚 1 秒的完整数据更糟。
 * </ul>
 *
 * <p><b>元信息</b>（米制精度、解算年龄、来源、序号）来自帧首的 {@code $GPSSHARE}。
 * 缺该语句（对 v1.6.2 发送端）时完全退回旧行为：精度 = HDOP×5、年龄 = 0，不崩不卡。
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
    /** 本周期累计收到的线上字节（含 
 与校验和）。 */
    private long wireBytes;

    /**
     * 本帧的位置已经就位、但还没发布 —— 在等 RMC 补齐本周期的速度与航向。
     *
     * <p>为 true 时，说明 GGA 已到而 RMC 还没到。若本帧一直没有 RMC，
     * 位置会在下一帧开头（{@code $GPSSHARE} 或 GGA 到达时）补发。
     */
    private boolean positionPending;

    /**
     * {@code $GPSSHARE} 带来的真实水平精度。NaN = 本帧没有（或发送端是旧版，没有该语句）。
     *
     * <p>单独存一份、<b>不</b>直接写进 {@code state.accuracyM}：GGA 的 HDOP×5 兜底会写
     * {@code state.accuracyM}，两者互相覆盖就会把真实精度冲掉。发布时再择优。
     */
    private float metaAccuracyM = Float.NaN;

    /**
     * 自上一个 GGA 以来是否见过 {@code $GPSSHARE}。
     *
     * <p>用来判断「GGA 到达时该不该重置元信息」：v2 的帧首是 {@code $GPSSHARE}，
     * 它就在 GGA 之前，真值已经到手，GGA 再重置一次就把真值冲掉了；
     * 而 v1.6.2 的帧首是 GGA，没有 {@code $GPSSHARE}，此时必须重置，
     * 否则会把上一帧的元信息留到这一帧。
     */
    private boolean seenShareInFrame;

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
        wireBytes = 0L;
        positionPending = false;
        metaAccuracyM = Float.NaN;
        seenShareInFrame = false;
        state.seq = 0L;
        state.ageMillis = 0L;
        state.source = NmeaInput.Source.NONE;
        state.verticalAccuracyM = Float.NaN;
        state.speedAccuracyMps = Float.NaN;
        state.bearingAccuracyDeg = Float.NaN;
    }

    /**
     * 喂入一行。
     *
     * <p>发布策略（v2.0.0）：
     * <ul>
     *   <li>{@code $GPSSHARE} —— 帧首。读入本帧元信息；若上一帧因缺 RMC 而没发出去，
     *       此刻补发。
     *   <li>GGA —— 读入位置与高度，<b>不发布</b>（RMC 紧跟其后，带着本周期的速度与航向）。
     *   <li>RMC —— <b>位置发布点</b>。
     *   <li>GSV —— 卫星发布点。多星座多页会发布多次，最后一次携带完整卫星列表。
     * </ul>
     *
     * @return 快照，或 null
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
        String head = f[0].toUpperCase(Locale.US);
        if ("GPSSHARE".equals(head)) {
            // 帧首。上一帧若因缺 RMC 而没发出去，此刻补发。
            // 补发必须走在 applyShare 之前 —— 它要用的是上一帧的元信息，
            // 等 applyShare 把本帧的元信息填进来就晚了。
            NmeaInput deferred = flushPendingPosition();
            seenShareInFrame = true;
            wireBytes = 0L;
            wireBytes += text.length() + 2;
            applyShare(f);
            return deferred;
        }
        // 形如 GPGGA / BDGSV：末三位是语句类型
        String type = head.substring(head.length() - 3);
        if ("GGA".equals(type)) {
            // 帧边界（对没有 $GPSSHARE 的旧版发送端，GGA 就是帧边界）：
            // 上一帧若因缺 RMC 而没发出去，此刻补发。
            NmeaInput deferred = flushPendingPosition();
            if (!seenShareInFrame) {
                // 本帧没有 $GPSSHARE：元信息必须清成「未知」，
                // 否则会把上一帧的精度、年龄、来源留到这一帧。
                metaAccuracyM = Float.NaN;
                wireBytes = 0L;
            }
            seenShareInFrame = false;
            entries.clear();
            usedPrns.clear();
            wireBytes += text.length() + 2;
            if (applyGga(f)) {
                hasFix = true;
            }
            // 不在这里发布：RMC 紧跟其后，带着本周期的速度与航向。
            // 此刻发布的话，速度与航向会是上一周期的旧值。
            positionPending = true;
            return deferred;
        }
        if ("RMC".equals(type)) {
            wireBytes += text.length() + 2;
            if (applyRmc(f)) {
                hasFix = true;
            }
            positionPending = false;
            return publishPosition();
        }
        if ("GSA".equals(type)) {
            wireBytes += text.length() + 2;
            applyGsa(f);
            return null;
        }
        if ("GSV".equals(type)) {
            // 不在这里清 entries：多星座的 GSV 是按 talker 分组的，每组
            // 都有自己的 page 1，若按 page<=1 清会把前一个星座的累积数据全清掉。
            wireBytes += text.length() + 2;
            applyGsv(f);
            // GSV 到了：连同当前累积的卫星列表一起发布。多星座多页会发布多次，
            // 最后一次携带完整卫星列表——把中间值覆盖掉。
            return publishSatellites();
        }
        return null;
    }

    /** 位置发布点：只带位置字段，卫星明细留给 GSV 发布点。 */
    private NmeaInput publishPosition() {
        if (!hasFix) {
            return null;
        }
        NmeaInput snap = state.copy();
        snap.wireBytes = wireBytes;
        snap.clearSats();
        snap.withSatellites = false;
        applyMetaAccuracy(snap);
        return snap;
    }

    /** 卫星发布点：带完整卫星列表。 */
    private NmeaInput publishSatellites() {
        if (!hasFix) {
            return null;
        }
        NmeaInput snap = state.copy();
        snap.wireBytes = wireBytes;
        snap.clearSats();
        for (SatEntry entry : entries) {
            snap.addSat(new NmeaInput.Sat(entry.prn, entry.elevation, entry.azimuth,
                    entry.snr, entry.used, entry.talker));
        }
        snap.withSatellites = true;
        applyMetaAccuracy(snap);
        return snap;
    }

    /** $GPSSHARE 的真实精度优先于 GGA 的 HDOP×5 兜底。 */
    private void applyMetaAccuracy(NmeaInput snap) {
        if (!Float.isNaN(metaAccuracyM)) {
            snap.accuracyM = metaAccuracyM;
        }
    }

    /**
     * 上一帧没有 RMC 时，位置拖到这一帧开头才发。
     *
     * <p>宁可晚 1 秒，也要给完整的一份数据 —— 「速度航向是上一周期、其余是本周期」
     * 的混合数据比晚 1 秒的完整数据更糟。
     */
    private NmeaInput flushPendingPosition() {
        if (!positionPending) {
            return null;
        }
        positionPending = false;
        return publishPosition();
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

    /**
     * 解析 {@code $GPSSHARE}：{@code $GPSSHARE,1,SEQ=..,ACC=..,VACC=..,SACC=..,BACC=..,AGE=..,SRC=..}
     *
     * <p>键值对形式，缺的键就是「未知」——不填默认值。
     *
     * <p><b>本帧的元信息是整体替换，不是逐项更新。</b>先把上一帧残留的值全部清成
     * 「未知」，再按本帧出现的键填上。逐项更新的话，某个键在这一帧里消失
     * （发送端这一帧取不到垂直精度）时，会把上一帧的旧值继续报出去 ——
     * 那等于谎报一个已经不成立的精度。
     */
    private void applyShare(String[] f) {
        state.seq = 0L;
        state.ageMillis = 0L;
        state.source = NmeaInput.Source.NONE;
        state.verticalAccuracyM = Float.NaN;
        state.speedAccuracyMps = Float.NaN;
        state.bearingAccuracyDeg = Float.NaN;
        metaAccuracyM = Float.NaN;
        for (int i = 2; i < f.length; i++) {
            String field = f[i];
            if (field == null || field.isEmpty()) {
                continue;
            }
            int eq = field.indexOf('=');
            if (eq <= 0 || eq == field.length() - 1) {
                continue;
            }
            String key = field.substring(0, eq).trim().toUpperCase(Locale.US);
            String value = field.substring(eq + 1).trim();
            if ("SEQ".equals(key)) {
                state.seq = (long) parseDouble(value, 0d);
            } else if ("AGE".equals(key)) {
                state.ageMillis = (long) parseDouble(value, 0d);
            } else if ("SRC".equals(key)) {
                state.source = NmeaInput.Source.fromWire(value);
            } else if ("ACC".equals(key)) {
                metaAccuracyM = parseFiniteFloat(value);
            } else if ("VACC".equals(key)) {
                state.verticalAccuracyM = parseFiniteFloat(value);
            } else if ("SACC".equals(key)) {
                state.speedAccuracyMps = parseFiniteFloat(value);
            } else if ("BACC".equals(key)) {
                state.bearingAccuracyDeg = parseFiniteFloat(value);
            }
        }
    }

    /**
     * 解析一个浮点精度值；无法解析或非有限（NaN / ±Infinity）时返回 NaN（未知）。
     *
     * <p>{@code Double.parseDouble("Infinity")} 是<b>成功</b>的 —— 线上真出现
     * {@code ACC=Infinity} 时，它会被当成一个「极好的精度」原样收下。这里必须挡一道，
     * 与发送端「非有限值就不上线」的规则对齐。
     */
    private static float parseFiniteFloat(String value) {
        double parsed = parseDouble(value, Double.NaN);
        if (!Double.isFinite(parsed)) {
            return Float.NaN;
        }
        float result = (float) parsed;
        return Float.isFinite(result) ? result : Float.NaN;
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
