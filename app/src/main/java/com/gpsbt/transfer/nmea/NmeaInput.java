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

        /**
         * 线上协议的字面拼写。
         *
         * <p>这里刻意写字符串字面量而不是 {@code name()}：返回值会进 $GPSSHARE 语句，
         * 是两端之间的协议约定。将来枚举常量若改名（例如 {@code NET} 改成 {@code NETWORK}），
         * 线上协议必须仍然发 {@code NET}；写成 {@code name()} 的话，一次改名就会静默
         * 改掉协议、把两端解析对不上。
         */
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
    /**
     * 本次解算的年龄（毫秒）。
     *
     * <p><b>发送端不算这个字段。</b>发送端的唯一年龄入口是
     * {@link #computeAgeMillis(long, long)}，其返回值直接作为参数一路传给发帧方法，
     * <b>不会回写本字段</b>。所以改年龄计算只需改 {@code computeAgeMillis}，无需动这里。
     *
     * <p><b>接收端填这个字段。</b>它是从线上 $GPSSHARE 语句里解析出来的载荷。
     *
     * <p>两者同名不冲突：一个在发送端<b>算</b>（方法），一个在接收端<b>填</b>（字段），
     * 两条路径互不相交。后续代码不要以为需要「同步维护」它们。
     */
    public long ageMillis;
    /** 解算时刻对应的 uptime（纳秒）。发送端填；接收端不用。 */
    public long fixElapsedRealtimeNanos;
    /** 垂直精度（米）。NaN = 未知。 */
    public float verticalAccuracyM = Float.NaN;
    /** 速度精度（米/秒）。NaN = 未知。 */
    public float speedAccuracyMps = Float.NaN;
    /** 航向精度（度）。NaN = 未知。 */
    public float bearingAccuracyDeg = Float.NaN;
    /**
     * 这份快照是否携带卫星明细。GSV 发布点为 true，位置发布点为 false。
     *
     * <p>这里的 {@code transient} 其实是空操作——本类从不序列化。它被当作
     * 「不属于线上载荷」的标记来用，与同样空操作的 {@link #wireBytes} 保持一致：
     * 这两个字段描述的是本地这份快照的形态，不是要发到线上的东西。
     */
    public transient boolean withSatellites;

    public int satellitesUsed;
    /**
     * 这份快照对应的线上字节数（本连接累计收到的 NMEA 字节）。
     * 接收端用它统计数据量；0 表示未知（比如快照是程序内部构造的）。
     *
     * <p><b>累计值，不是本帧的字节数。</b>一帧会发布两次（位置 + 卫星明细），
     * 两次发布都带着同一个累计值。统计时要按「本次 − 上次」的增量结算，
     * 直接累加会把同一帧算两遍。
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
     * 算出本次解算的年龄（毫秒）。<b>发送端</b>唯一的年龄计算入口，返回值直接
     * 传给发帧方法；它<b>不回写</b> {@link #ageMillis} 字段（那是接收端的载荷）。
     *
     * <p>优先用 uptime：elapsedRealtimeNanos 不受用户改时间、时区切换、NTP 校时
     * 影响，而且与 Android 平台判断定位新鲜度（{@code Location.isFresh()}）
     * 用的是同一把尺子。拿不到时退回墙上时钟。
     *
     * <p>守卫 {@code nowElapsedRealtimeNanos > fixElapsedRealtimeNanos} 同时在做两件事：
     * 一是<b>拒绝 uptime 倒流</b>——now 反而比 fix 更早（uptime 被重置、或调用方传错了
     * 参数）时算出的差值是负的、没有意义，必须走墙上时钟；顺带也把<b>「调用方根本没传
     * uptime」</b>识别出来了——那种情况 {@code now == 0}，同样落进墙上时钟分支。
     * 两种情况合并成一个判断即可，不必拆开。
     *
     * @param nowElapsedRealtimeNanos 调用时刻的 uptime（纳秒），来自
     *     {@code SystemClock.elapsedRealtimeNanos()}；传 0 表示调用方没有 uptime
     * @param nowWallMillis 调用时刻的墙上时钟（毫秒），来自
     *     {@code System.currentTimeMillis()}；只有在上面被守卫挡住时才用得上
     * @return 年龄（毫秒），钳到不小于 0（用户把时钟往回拨时返回 0，当作刚解算）
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
