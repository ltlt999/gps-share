package com.gpsbt.transfer.nmea;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

/** 由定位快照生成标准 NMEA 0183 语句。 */
public final class NmeaGenerator {

    /** 米/秒 → 节。 */
    public static final double MS_TO_KNOTS = 1.9438444924406045;

    /** $GPSSHARE 的协议版本。 */
    public static final int PROTOCOL_VERSION = 1;

    private static final String TALKER = "GP";
    private final float defaultHdop;

    public NmeaGenerator(float defaultHdop) {
        this.defaultHdop = defaultHdop;
    }

    public String gga(NmeaInput in) {
        List<String> f = new ArrayList<>();
        f.add(utcTime(in.utcMillis));
        f.add(latitude(in.latitude));
        f.add(longitude(in.longitude));
        f.add(in.valid ? "1" : "0");
        f.add(String.format(Locale.US, "%02d", in.valid ? Math.max(1, in.satellitesUsed) : 0));
        f.add(String.format(Locale.US, "%.1f", hdopFor(in.valid ? in : null, defaultHdop)));
        f.add(String.format(Locale.US, "%.1f", in.valid ? in.altitudeM : 0d));
        f.add("M");
        f.add("0.0");
        f.add("M");
        f.add("");
        f.add("");
        return frame("GP", "GGA", f);
    }

    public String rmc(NmeaInput in) {
        List<String> f = new ArrayList<>();
        f.add(utcTime(in.utcMillis));
        f.add(in.valid ? "A" : "V");
        f.add(latitude(in.latitude));
        f.add(longitude(in.longitude));
        f.add(String.format(Locale.US, "%.1f", in.valid ? Math.max(0d, in.speedMps * MS_TO_KNOTS) : 0d));
        f.add(String.format(Locale.US, "%.1f", in.valid ? clamp(0d, 360d, in.bearingDeg) : 0d));
        f.add(utcDate(in.utcMillis));
        f.add("");
        f.add("");
        f.add(in.valid ? "A" : "N");
        return frame("GP", "RMC", f);
    }

    public String gsa(NmeaInput in) {
        List<Integer> used = new ArrayList<>();
        for (NmeaInput.Sat sat : in.satellites()) {
            if (sat.used && used.size() < 12) {
                used.add(sat.prn);
            }
        }
        if (used.isEmpty() && in.satellitesUsed > 0 && in.satellitesInView() > 0) {
            for (NmeaInput.Sat sat : in.satellites()) {
                if (used.size() < Math.min(12, in.satellitesUsed)) {
                    used.add(sat.prn);
                }
            }
        }
        double hdop = hdopFor(in.valid ? in : null, defaultHdop);
        List<String> f = new ArrayList<>();
        f.add("A");
        f.add(in.valid ? "3" : "1");
        for (int i = 0; i < 12; i++) {
            f.add(i < used.size() ? String.format(Locale.US, "%02d", used.get(i)) : "");
        }
        f.add(String.format(Locale.US, "%.1f", hdop * 1.2));
        f.add(String.format(Locale.US, "%.1f", hdop));
        f.add(String.format(Locale.US, "%.1f", hdop * 0.8));
        return frame("GP", "GSA", f);
    }

    public List<String> gsv(NmeaInput in) {
        int count = in.satellitesInView();
        if (count == 0) {
            return Collections.emptyList();
        }
        // 按星座分组（保持首次出现顺序），每组用各自的 talker 发一组 GSV，
        // 接收端才能准确区分 GPS / 北斗 / GLONASS / Galileo 等系统
        List<List<NmeaInput.Sat>> groups = new ArrayList<>();
        List<String> talkers = new ArrayList<>();
        for (NmeaInput.Sat sat : in.satellites()) {
            // 系统提供的星座类型最可靠；没有时按编号段推断
            String talker = sat.talker != null ? sat.talker : talkerOf(sat.prn);
            int idx = talkers.indexOf(talker);
            if (idx < 0) {
                talkers.add(talker);
                List<NmeaInput.Sat> group = new ArrayList<>();
                group.add(sat);
                groups.add(group);
            } else {
                groups.get(idx).add(sat);
            }
        }
        List<String> out = new ArrayList<>();
        for (int g = 0; g < groups.size(); g++) {
            String talker = talkers.get(g);
            List<NmeaInput.Sat> group = groups.get(g);
            int total = (group.size() + 3) / 4;
            for (int page = 0; page < total; page++) {
                List<String> f = new ArrayList<>();
                f.add(String.valueOf(total));
                f.add(String.valueOf(page + 1));
                f.add(String.valueOf(group.size()));
                for (int k = 0; k < 4; k++) {
                    int index = page * 4 + k;
                    if (index < group.size()) {
                        NmeaInput.Sat sat = group.get(index);
                        f.add(String.format(Locale.US, "%02d", sat.prn));
                        f.add(String.format(Locale.US, "%02d", (int) clamp(0d, 90d, sat.elevationDeg)));
                        f.add(String.format(Locale.US, "%03d", (int) normalize360(sat.azimuthDeg)));
                        f.add(sat.snr > 0 ? String.format(Locale.US, "%02d", Math.min(99, sat.snr)) : "");
                    } else {
                        f.add("");
                        f.add("");
                        f.add("");
                        f.add("");
                    }
                }
                out.add(frame(talker, "GSV", f));
            }
        }
        return out;
    }

    /** 按卫星编号推断所属星座 talker（与 Android GnssStatus 的 svid 区间一致）。 */
    static String talkerOf(int prn) {
        if (prn >= 65 && prn <= 96) {
            return "GL";
        }
        if (prn >= 193 && prn <= 200) {
            return "GQ";
        }
        if (prn >= 201 && prn <= 237) {
            return "BD";
        }
        if (prn >= 301 && prn <= 336) {
            return "GA";
        }
        if (prn >= 401 && prn <= 414) {
            return "GI";
        }
        // GPS 1-32 与 SBAS 120-158 均使用 GP talker
        return "GP";
    }

    public String vtg(NmeaInput in) {
        double knots = in.valid ? Math.max(0d, in.speedMps * MS_TO_KNOTS) : 0d;
        double kmh = in.valid ? Math.max(0d, in.speedMps * 3.6) : 0d;
        double track = in.valid ? clamp(0d, 360d, in.bearingDeg) : 0d;
        List<String> f = new ArrayList<>();
        f.add(String.format(Locale.US, "%.1f", track));
        f.add("T");
        f.add("");
        f.add("M");
        f.add(String.format(Locale.US, "%.1f", knots));
        f.add("N");
        f.add(String.format(Locale.US, "%.1f", kmh));
        f.add("K");
        f.add("A");
        return frame("GP", "VTG", f);
    }

    /** ZDA：UTC 日期时间帧。无定位时作为链路保活帧发送。 */
    public String zda(long utcMillis) {
        String date = utcDate(utcMillis);
        List<String> f = new ArrayList<>();
        f.add(utcTime(utcMillis));
        f.add(date.substring(0, 2));
        f.add(date.substring(2, 4));
        f.add("20" + date.substring(4, 6));
        f.add("00");
        f.add("00");
        return frame("GP", "ZDA", f);
    }

    /**
     * $GPSSHARE：承载标准 NMEA 装不下的元信息 —— 米制精度、解算年龄、来源、序号。
     *
     * <p>帧首发送，接收端解析到位置时元数据已在手。
     *
     * <p><b>精度值</b>非有限（NaN / ±Infinity）或不大于 0 时，<b>省略整个键</b>，
     * 不写 0 也不写默认值 —— 「未知」和「0 米」是两回事，见 {@link #appendAccuracy}。
     *
     * <p><b>有效性不在本语句里表达。</b>{@code valid == false} 的快照照样会发出
     * {@code SRC} 与精度。定位到底成不成立要看 GGA 的质量位与 RMC 的状态位 ——
     * 线上协议没给 $GPSSHARE 留表达「提供者报了但没有定位」的位置，
     * 只看本语句会给无效定位显示出一个自信的精度。
     *
     * @param in 定位快照。序号直接取 {@link NmeaInput#seq}，本方法不另收 seq 参数 ——
     *     两个真相源会静默分叉，而 SEQ 是接收端的去重键。不允许为 null
     * @param ageMillis 解算年龄（毫秒），调用方必须传非负值
     *     （{@link NmeaInput#computeAgeMillis(long, long)} 已钳好）。这里<b>不再二次钳位</b>：
     *     钳到 0 恰好是最坏的值（等于宣称「0 毫秒前刚测的」），且与真正新鲜的解算无法区分；
     *     负值原样上线，交给接收端的范围校验 fail-closed 拒掉
     * @return 完整的 $GPSSHARE 语句，自带 *CS 与 \r\n
     */
    public String gpsShare(NmeaInput in, long ageMillis) {
        StringBuilder body = new StringBuilder();
        body.append(PROTOCOL_VERSION).append(",SEQ=").append(Math.max(0L, in.seq));
        appendAccuracy(body, "ACC", in.accuracyM);
        appendAccuracy(body, "VACC", in.verticalAccuracyM);
        appendAccuracy(body, "SACC", in.speedAccuracyMps);
        appendAccuracy(body, "BACC", in.bearingAccuracyDeg);
        body.append(",AGE=").append(ageMillis);
        body.append(",SRC=").append(in.source.wire());
        return frame("GPS", "SHARE", Collections.singletonList(body.toString()));
    }

    /** 无新解算时的保活帧：$GPSSHARE（带上一次解算的元数据）+ $GPZDA。 */
    public String keepaliveFrame(NmeaInput last, long ageMillis, long nowMillis) {
        if (last == null) {
            // 从来没有过解算：只发一个纯保活帧。
            // 不要造 $GPSSHARE 空壳 —— 接收端会把 AGE=0 读成「刚刚定位」，
            // SEQ=0 还会把去重计数器倒回 0，等于凭空造出一份并不存在的解算。
            return zda(nowMillis);
        }
        StringBuilder sb = new StringBuilder();
        sb.append(gpsShare(last, ageMillis));
        sb.append(zda(nowMillis));
        return sb.toString();
    }

    /**
     * 追加一个精度键值对；值不可用时<b>整个键都不写</b>。
     *
     * <p>「未知」和「0」是两回事，而线上没有别的写法能表达「未知」—— 缺键才是。
     * 两种情况必须挡住：
     *
     * <ul>
     *   <li><b>非有限值。</b>{@code Float.isNaN(Infinity)} 为 false、{@code Infinity <= 0f}
     *       也为 false，于是走到格式化那步印出字面量 {@code "Infinity"} —— 每个字符都是
     *       ASCII，校验和照样成立，语句被当成格式良好的接收；接收端
     *       {@code Double.parseDouble("Infinity")} 还会成功，拿到一个「极好的精度」。
     *   <li><b>取整后归零的小正数。</b>{@code 0.04f} / {@code 0.049f} / {@code 0.01f} 都
     *       能通过 {@code value > 0} 的守卫，但 {@code %.1f} 一律印成 {@code "0.0"} ——
     *       接收端 {@code parseDouble("0.0")} 存成 0.0 而不是 NaN，等于谎报「完美精度」。
     *       所以<b>先取整再判断</b>，而不是判断完再格式化。
     * </ul>
     */
    private static void appendAccuracy(StringBuilder body, String key, float value) {
        if (!Float.isFinite(value) || value <= 0f) {
            // 非有限值（NaN / ±Infinity）或非正数：无线上可表达的含义，整个键省略
            return;
        }
        // 取整用 double 算，别用 float：float 的 value*10 会先溢出成 Infinity，
        // Math.round 再把它饱和成 Integer.MAX_VALUE，除以 10 后印出「214748364.7」——
        // 比真值小了四个数量级。double 下这条路不存在。
        double rounded = Math.round((double) value * 10.0d) / 10.0d;
        if (!(rounded > 0d) || Double.isInfinite(rounded)) {
            // 太小，%.1f 会印成 0.0 —— 那等于谎报「完美精度」，宁可省略
            return;
        }
        body.append(',').append(key).append('=')
                .append(String.format(Locale.US, "%.1f", rounded));
    }

    /** 按固定顺序拼装一帧（各语句均带 *CS 与 \r\n）。$GPSSHARE 恒在帧首。 */
    public String buildFrame(NmeaInput in, Set<NmeaSentenceType> enabled, long ageMillis) {
        StringBuilder sb = new StringBuilder();
        sb.append(gpsShare(in, ageMillis));
        if (enabled.contains(NmeaSentenceType.GGA)) {
            sb.append(gga(in));
        }
        if (enabled.contains(NmeaSentenceType.RMC)) {
            sb.append(rmc(in));
        }
        if (enabled.contains(NmeaSentenceType.GSA)) {
            sb.append(gsa(in));
        }
        if (enabled.contains(NmeaSentenceType.GSV)) {
            for (String sentence : gsv(in)) {
                sb.append(sentence);
            }
        }
        if (enabled.contains(NmeaSentenceType.VTG)) {
            sb.append(vtg(in));
        }
        return sb.toString();
    }

    /** 纬度转 ddmm.mmmm,N/S。 */
    public static String latitude(double lat) {
        double abs = Math.abs(lat);
        int degrees = (int) abs;
        double minutes = (abs - degrees) * 60.0;
        if (minutes >= 59.99995) {
            minutes -= 60.0;
            degrees += 1;
        }
        return String.format(Locale.US, "%02d%07.4f,%s", degrees, Math.max(0d, minutes),
                lat >= 0 ? "N" : "S");
    }

    /** 经度转 dddmm.mmmm,E/W。 */
    public static String longitude(double lon) {
        double abs = Math.abs(lon);
        int degrees = (int) abs;
        double minutes = (abs - degrees) * 60.0;
        if (minutes >= 59.99995) {
            minutes -= 60.0;
            degrees += 1;
        }
        return String.format(Locale.US, "%03d%07.4f,%s", degrees, Math.max(0d, minutes),
                lon >= 0 ? "E" : "W");
    }

    /** UTC 毫秒 → HHmmss.SS。 */
    public static String utcTime(long millis) {
        SimpleDateFormat format = new SimpleDateFormat("HHmmss.SS", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date(millis));
    }

    /** UTC 毫秒 → ddMMyy。 */
    public static String utcDate(long millis) {
        SimpleDateFormat format = new SimpleDateFormat("ddMMyy", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date(millis));
    }

    /** 由定位精度估算 HDOP；无精度信息时返回 fallback。 */
    public static double hdopFor(NmeaInput in, float fallback) {
        if (in == null || Float.isNaN(in.accuracyM) || in.accuracyM <= 0f) {
            return fallback;
        }
        return clamp(0.5d, 9.9d, in.accuracyM / 5.0);
    }

    private static String frame(String talker, String type, List<String> values) {
        StringBuilder sb = new StringBuilder("$").append(talker).append(type);
        for (String value : values) {
            sb.append(',').append(value);
        }
        return NmeaChecksum.withChecksum(sb.toString());
    }

    private static double clamp(double min, double max, double value) {
        return Math.min(max, Math.max(min, value));
    }

    private static double normalize360(double value) {
        double v = value % 360.0;
        return v < 0 ? v + 360.0 : v;
    }
}
