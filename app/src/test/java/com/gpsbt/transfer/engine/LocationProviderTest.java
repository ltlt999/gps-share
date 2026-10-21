package com.gpsbt.transfer.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.gpsbt.transfer.nmea.NmeaInput;

import org.junit.Test;

public class LocationProviderTest {

    private static final long SEC = 1000L;

    @Test
    public void networkFixSuppressedWhileGpsHealthy() {
        // 3 秒前刚有 GPS 定位 → 网络定位被忽略
        assertFalse(LocationProvider.shouldEmitNetworkFix(
                100_000L, 90_000L, 103_000L, 10 * SEC));
    }

    @Test
    public void networkFixEmittedAfterGpsSilence() {
        // 11 秒没有 GPS 定位 → 网络兜底
        assertTrue(LocationProvider.shouldEmitNetworkFix(
                100_000L, 90_000L, 111_000L, 10 * SEC));
    }

    @Test
    public void networkFixWaitsFromStartWhenNoGpsEver() {
        // 从未有过 GPS 定位：以启动时刻为锚点
        assertFalse(LocationProvider.shouldEmitNetworkFix(
                0L, 100_000L, 105_000L, 10 * SEC));
        assertTrue(LocationProvider.shouldEmitNetworkFix(
                0L, 100_000L, 111_000L, 10 * SEC));
    }

    @Test
    public void boundaryIsInclusive() {
        // 恰好 10 秒：还没超时（> 判定）
        assertFalse(LocationProvider.shouldEmitNetworkFix(
                0L, 0L, 10 * SEC, 10 * SEC));
        assertTrue(LocationProvider.shouldEmitNetworkFix(
                0L, 0L, 10 * SEC + 1, 10 * SEC));
    }

    // ---- 元信息采集（v2.0.0） ----

    @Test
    public void keepsRealFixTimeInsteadOfNow() {
        NmeaInput input = new NmeaInput();
        long fixTime = 1_700_000_000_000L;

        LocationProvider.applyFixMetadata(input, fixTime, 5_000_000_000L,
                12.5f, 8f, 0.8f, 15f, 1_700_000_000_900L);

        // v1.6.2 把 utcMillis 写成「当前时刻」，接收端因此无从知道
        // 这个位置是什么时候测的 —— 解算年龄在协议里根本没法传。
        assertEquals(fixTime, input.utcMillis);
        assertEquals(5_000_000_000L, input.fixElapsedRealtimeNanos);
    }

    @Test
    public void fallsBackToNowWhenFixTimeMissing() {
        NmeaInput input = new NmeaInput();

        LocationProvider.applyFixMetadata(input, 0L, 0L,
                Float.NaN, Float.NaN, Float.NaN, Float.NaN, 1_700_000_000_900L);

        assertEquals(1_700_000_000_900L, input.utcMillis);
        assertEquals(0L, input.fixElapsedRealtimeNanos);
    }

    @Test
    public void carriesThreeDimensionalAccuracy() {
        NmeaInput input = new NmeaInput();

        LocationProvider.applyFixMetadata(input, 1_700_000_000_000L, 0L,
                12.5f, 8f, 0.8f, 15f, 1_700_000_000_900L);

        assertEquals(12.5f, input.accuracyM, 0.001f);
        assertEquals(8f, input.verticalAccuracyM, 0.001f);
        assertEquals(0.8f, input.speedAccuracyMps, 0.001f);
        assertEquals(15f, input.bearingAccuracyDeg, 0.001f);
    }

    @Test
    public void keepsUnknownAccuracyAsNaN() {
        NmeaInput input = new NmeaInput();

        LocationProvider.applyFixMetadata(input, 1_700_000_000_000L, 0L,
                Float.NaN, Float.NaN, Float.NaN, Float.NaN, 1_700_000_000_900L);

        // 「未知」必须原样保持 NaN，不能变成 0 —— 0 会被当成「完美精度」
        assertTrue(Float.isNaN(input.accuracyM));
        assertTrue(Float.isNaN(input.verticalAccuracyM));
        assertTrue(Float.isNaN(input.speedAccuracyMps));
        assertTrue(Float.isNaN(input.bearingAccuracyDeg));
    }

    @Test
    public void accuracyApisAreGuardedBelowApi26() {
        // API 26 以下没有「垂直/速度/航向精度」这回事，而且那些方法在旧系统上
        // 根本不存在 —— 直接调会抛 NoSuchMethodError，版本判断必须挡住。
        android.location.Location location = new android.location.Location("gps");

        assertTrue(Float.isNaN(LocationProvider.verticalAccuracy(location, 25)));
        assertTrue(Float.isNaN(LocationProvider.speedAccuracy(location, 25)));
        assertTrue(Float.isNaN(LocationProvider.bearingAccuracy(location, 25)));
        // API 26 及以上：系统没给精度时同样是「未知」
        assertTrue(Float.isNaN(LocationProvider.verticalAccuracy(location, 26)));
        assertTrue(Float.isNaN(LocationProvider.speedAccuracy(location, 26)));
        assertTrue(Float.isNaN(LocationProvider.bearingAccuracy(location, 26)));
    }
}
