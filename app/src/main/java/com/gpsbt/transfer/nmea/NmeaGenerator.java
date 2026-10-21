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
     * $GPSSHARE：承载标准 NMEA 装不下的元信息 —— 米制精度、解算年龄、来源。
     *
     * 帧首发送，接收端解析到位置时元数据已在手。精度值不可用（NaN）时
     * **省略整个键**，不写 0 也不写默认值 —— 「未知」和「0 米」是两回事。
     */
    public String gpsShare(NmeaInput in, long seq, long ageMillis) {
        NmeaInput source = in == null ? new NmeaInput() : in;
        StringBuilder body = new StringBuilder();
        body.append(PROTOCOL_VERSION).append(",SEQ=").append(Math.max(0L, seq));
        appendAccuracy(body, "ACC", source.accuracyM);
        appendAccuracy(body, "VACC", source.verticalAccuracyM);
        appendAccuracy(body, "SACC", source.speedAccuracyMps);
        appendAccuracy(body, "BACC", source.bearingAccuracyDeg);
        body.append(",AGE=").append(Math.max(0L, ageMillis));
        body.append(",SRC=").append(source.source.wire());
        return frame("GPS", "SHARE", Collections.singletonList(body.toString()));
    }

    /** 无新解算时的保活帧：$GPSSHARE（带上一次解算的元数据）+ $GPZDA。 */
    public String keepaliveFrame(NmeaInput last, long ageMillis, long nowMillis) {
        NmeaInput source = last == null ? new NmeaInput() : last;
        StringBuilder sb = new StringBuilder();
        sb.append(gpsShare(source, source.seq, ageMillis));
        sb.append(zda(nowMillis));
        return sb.toString();
    }

    private static void appendAccuracy(StringBuilder body, String key, float value) {
        if (Float.isNaN(value) || value <= 0f) {
            return;
        }
        body.append(',').append(key).append('=')
                .append(String.format(Locale.US, "%.1f", value));
    }

    /** 按固定顺序拼装一帧（各语句均带 *CS 与 \r\n）。$GPSSHARE 恒在帧首。 */
    public String buildFrame(NmeaInput in, Set<NmeaSentenceType> enabled, long ageMillis) {
        StringBuilder sb = new StringBuilder();
        sb.append(gpsShare(in, in.seq, ageMillis));
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
