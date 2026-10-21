package com.gpsbt.transfer.nmea;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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
        for (Sat sat : satellites) {
            copy.addSat(sat);
        }
        return copy;
    }
}
