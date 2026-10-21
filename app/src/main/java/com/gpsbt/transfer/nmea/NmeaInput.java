package com.gpsbt.transfer.nmea;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** 与 Android 框架无关的定位快照，NMEA 生成器的唯一输入。 */
public final class NmeaInput {

    public boolean valid;
    public long utcMillis;
    public double latitude;
    public double longitude;
    public double altitudeM;
    public double speedMps;
    public double bearingDeg;
    public float accuracyM = Float.NaN;

    /** 数据来源：卫星定位 / 网络定位 / 无。 */
    public enum Source {
        NONE, GPS, NET;

        public String wire() {
            switch (this) {
                case GPS:
                    return "GPS";
                case NET:
                    return "NET";
                default:
                    return "NONE";
            }
        }

        public static Source fromWire(String value) {
            if (value == null) {
                return NONE;
            }
            String normalized = value.trim().toUpperCase(Locale.US);
            if ("GPS".equals(normalized)) {
                return GPS;
            }
            if ("NET".equals(normalized)) {
                return NET;
            }
            return NONE;
        }
    }

    /** 数据来源。发送端按 provider 判定，接收端从 $GPSSHARE 解析。 */
    public Source source = Source.NONE;
    /** 解算序号：发送端每次真正的新解算 +1。接收端用它判定「是不是同一个解算」。 */
    public long seq;
    /** 本次解算的年龄（毫秒）。发送端在发帧时刻算出，接收端从 $GPSSHARE 解析得到。 */
    public long ageMillis;
    /** 解算时刻对应的 uptime（纳秒）。发送端填；接收端不用。 */
    public long fixElapsedRealtimeNanos;
    /** 垂直精度（米）。NaN = 未知。 */
    public float verticalAccuracyM = Float.NaN;
    /** 速度精度（米/秒）。NaN = 未知。 */
    public float speedAccuracyMps = Float.NaN;
    /** 航向精度（度）。NaN = 未知。 */
    public float bearingAccuracyDeg = Float.NaN;
    /** 这份快照是否携带卫星明细。GSV 发布点为 true，位置发布点为 false。 */
    public transient boolean withSatellites;

    public int satellitesUsed;
    /**
     * 这份快照对应的线上字节数（本周期实际收到的 NMEA 字节）。
     * 接收端用它统计数据量；0 表示未知（比如快照是程序内部构造的）。
     */
    public transient long wireBytes;

    private final List<Sat> satellites = new ArrayList<>();

    /** 一颗可见卫星。 */
    public static final class Sat {
        public final int prn;
        public final int elevationDeg;
        public final int azimuthDeg;
        public final int snr;
        public final boolean used;
        /** 卫星所属星座 talker（GP/GL/BD/GA/GQ/GI），来自系统 GnssStatus；null 时由编号推断。 */
        public final String talker;

        public Sat(int prn, int elevationDeg, int azimuthDeg, int snr, boolean used) {
            this(prn, elevationDeg, azimuthDeg, snr, used, null);
        }

        public Sat(int prn, int elevationDeg, int azimuthDeg, int snr, boolean used, String talker) {
            this.prn = prn;
            this.elevationDeg = elevationDeg;
            this.azimuthDeg = azimuthDeg;
            this.snr = snr;
            this.used = used;
            this.talker = talker;
        }
    }

    public void addSat(Sat sat) {
        satellites.add(sat);
    }

    public void clearSats() {
        satellites.clear();
    }

    public List<Sat> satellites() {
        return Collections.unmodifiableList(satellites);
    }

    public int satellitesInView() {
        return satellites.size();
    }

    /** 返回副本（含卫星列表），`utcMillis` 原样复制。 */
    public NmeaInput copy() {
        NmeaInput copy = new NmeaInput();
        copy.valid = valid;
        copy.utcMillis = utcMillis;
        copy.latitude = latitude;
        copy.longitude = longitude;
        copy.altitudeM = altitudeM;
        copy.speedMps = speedMps;
        copy.bearingDeg = bearingDeg;
        copy.accuracyM = accuracyM;
        copy.satellitesUsed = satellitesUsed;
        copy.wireBytes = wireBytes;
        copy.source = source;
        copy.seq = seq;
        copy.ageMillis = ageMillis;
        copy.fixElapsedRealtimeNanos = fixElapsedRealtimeNanos;
        copy.verticalAccuracyM = verticalAccuracyM;
        copy.speedAccuracyMps = speedAccuracyMps;
        copy.bearingAccuracyDeg = bearingAccuracyDeg;
        copy.withSatellites = withSatellites;
        for (Sat sat : satellites) {
            copy.addSat(sat);
        }
        return copy;
    }

    /**
     * 算出本次解算的年龄（毫秒）。
     *
     * 优先用 uptime：elapsedRealtimeNanos 不受用户改时间、时区切换、NTP 校时
     * 影响，而且与 Android 平台判断定位新鲜度（{@code Location.isFresh()}）
     * 用的是同一把尺子。拿不到时退回墙上时钟。
     */
    public long computeAgeMillis(long nowElapsedRealtimeNanos, long nowWallMillis) {
        if (fixElapsedRealtimeNanos > 0 && nowElapsedRealtimeNanos > fixElapsedRealtimeNanos) {
            return (nowElapsedRealtimeNanos - fixElapsedRealtimeNanos) / 1_000_000L;
        }
        if (utcMillis > 0) {
            return Math.max(0L, nowWallMillis - utcMillis);
        }
        return 0L;
    }
}
