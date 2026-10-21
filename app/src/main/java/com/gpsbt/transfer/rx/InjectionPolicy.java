package com.gpsbt.transfer.rx;

import com.gpsbt.transfer.nmea.NmeaInput;

import java.util.Locale;

/**
 * 注入判据与参数计算：**纯逻辑，不碰任何 Android API**，因此可单测。
 *
 * <p>回答三个问题：
 * <ol>
 *   <li>这份数据该不该注入？（同一解算重复到达 / 只是卫星快照 / 无效 / 太旧 / 数值越界）
 *   <li>注入的时间戳应该是多少？（本机时钟 − 解算年龄）
 *   <li>精度怎么填？（有就传真值，没有就不设，绝不编默认值）
 * </ol>
 *
 * <p><b>时间戳的算法是这里最要紧的一处。</b>Android 的 {@code Location} 同时带墙上
 * 时钟（{@code getTime()}）和 uptime（{@code getElapsedRealtimeNanos()}），
 * <b>两个都必须减去解算年龄</b> —— 只改一个的话，平台算出的年龄和 App 算出的
 * 年龄会互相打架（一个说 0 秒，一个说 1 秒）。
 *
 * <p><b>年龄是时长，不是时刻。</b>两机的墙上时钟可能差好几分钟，但「这个位置是
 * 多久前测的」这件事两边算出来是一样的 —— 年龄天然免疫时钟不同步。
 */
public final class InjectionPolicy {

    /** 超过这个年龄就不注入：位置太旧，App 会当成陈旧数据丢掉。 */
    public static final long MAX_AGE_MILLIS = 5000L;

    /** 精度的合理区间（米）。超出即视为异常数据。 */
    private static final float MIN_ACCURACY_M = 0.1f;
    private static final float MAX_ACCURACY_M = 100000f;

    /** 年龄的合理区间（毫秒）。 */
    private static final long MAX_PLAUSIBLE_AGE_MILLIS = 60000L;

    public enum Decision {
        /** 可以注入。 */
        INJECT,
        /** 同一个解算重复到达。 */
        DUPLICATE,
        /** 只是卫星快照：供界面显示，不触发注入。 */
        SATELLITES_ONLY,
        /** 定位本身无效。 */
        INVALID,
        /** 太旧了。 */
        EXPIRED,
        /** 数值越界。 */
        OUT_OF_RANGE
    }

    /** 注入所需的全部参数。都是基本类型，不含 Android 类型，便于断言。 */
    public static final class Payload {
        public double latitude;
        public double longitude;
        public double altitudeM;
        public double speedMps;
        public double bearingDeg;
        public float accuracyM = Float.NaN;
        public float verticalAccuracyM = Float.NaN;
        public float speedAccuracyMps = Float.NaN;
        public float bearingAccuracyDeg = Float.NaN;
        /** 本机墙上时钟减去解算年龄。 */
        public long timeMillis;
        /**
         * 本机 uptime 减去解算年龄；0 表示拿不到本机 uptime，不该设。
         *
         * <p>宁可留空也不填负值：负的 uptime 会让平台算出的年龄变成负数，
         * 比不设还糟。
         */
        public long elapsedRealtimeNanos;
    }

    /** 上一次注入过的解算身份；null = 还没注入过。 */
    private String lastIdentity;

    /** 断线重连时调用：忘记上一个解算的身份。 */
    public synchronized void reset() {
        lastIdentity = null;
    }

    /**
     * 这个解算的身份标识。有序号用序号，否则退回「位置 + 时刻」。
     *
     * <p>序号不存在于 v1.6.2 的协议里，所以对旧版发送端只能退化成位置身份。
     */
    public static String identityOf(NmeaInput in) {
        if (in.seq > 0L) {
            return "seq:" + in.seq;
        }
        return String.format(Locale.US, "fallback:%.6f,%.6f,%d",
                in.latitude, in.longitude, in.utcMillis);
    }

    /**
     * 判定这份数据该不该注入。
     *
     * <p>顺序：先看是不是只是卫星快照（不注入、也不能占身份），再判重复，
     * 再判有效性、年龄、数值范围。
     *
     * <p><b>卫星快照必须排在重复判定之前。</b>卫星快照与位置快照是同一个解算，
     * 身份相同；若让卫星快照先把身份记下，随后到达的位置快照就会被判成重复 ——
     * 用户关掉 RMC 语句时（位置要拖到下一帧开头才发布），位置将永远注入不了。
     */
    public synchronized Decision decide(NmeaInput in) {
        if (in == null) {
            return Decision.INVALID;
        }
        if (in.withSatellites) {
            return Decision.SATELLITES_ONLY;
        }
        String identity = identityOf(in);
        if (identity.equals(lastIdentity)) {
            return Decision.DUPLICATE;
        }
        if (!in.valid) {
            return Decision.INVALID;
        }
        if (in.ageMillis > MAX_AGE_MILLIS) {
            return Decision.EXPIRED;
        }
        if (!inRange(in)) {
            return Decision.OUT_OF_RANGE;
        }
        lastIdentity = identity;
        return Decision.INJECT;
    }

    private static boolean inRange(NmeaInput in) {
        if (Double.isNaN(in.latitude) || in.latitude < -90d || in.latitude > 90d) {
            return false;
        }
        if (Double.isNaN(in.longitude) || in.longitude < -180d || in.longitude > 180d) {
            return false;
        }
        // 精度未知（NaN）不是精度非法：没有 ACC 的帧照样能注入。
        // 但 0 米精度是谎报（没有哪台设备真能精确到 0 米），按异常数据处理。
        if (!Float.isNaN(in.accuracyM)
                && (in.accuracyM < MIN_ACCURACY_M || in.accuracyM > MAX_ACCURACY_M)) {
            return false;
        }
        return in.ageMillis >= 0L && in.ageMillis <= MAX_PLAUSIBLE_AGE_MILLIS;
    }

    /**
     * 算出注入用的参数：时间戳按「本机时钟 − 解算年龄」重锚。
     *
     * @param nowWallMillis 本机当前墙上时钟
     * @param nowElapsedNanos 本机当前 uptime（纳秒）；传 0 表示拿不到
     */
    public static Payload payloadOf(NmeaInput in, long nowWallMillis, long nowElapsedNanos) {
        Payload payload = new Payload();
        payload.latitude = in.latitude;
        payload.longitude = in.longitude;
        payload.altitudeM = in.altitudeM;
        payload.speedMps = in.speedMps;
        payload.bearingDeg = in.bearingDeg;
        payload.accuracyM = in.accuracyM;
        payload.verticalAccuracyM = in.verticalAccuracyM;
        payload.speedAccuracyMps = in.speedAccuracyMps;
        payload.bearingAccuracyDeg = in.bearingAccuracyDeg;
        long age = Math.max(0L, in.ageMillis);
        payload.timeMillis = nowWallMillis - age;
        payload.elapsedRealtimeNanos = nowElapsedNanos > 0L
                ? nowElapsedNanos - age * 1_000_000L
                : 0L;
        return payload;
    }
}
