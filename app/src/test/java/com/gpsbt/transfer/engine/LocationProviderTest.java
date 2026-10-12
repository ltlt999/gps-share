package com.gpsbt.transfer.engine;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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
}
