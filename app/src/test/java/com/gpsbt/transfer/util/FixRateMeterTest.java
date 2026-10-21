package com.gpsbt.transfer.util;

import static org.junit.Assert.assertEquals;

import org.junit.Before;
import org.junit.Test;

public class FixRateMeterTest {

    private FixRateMeter meter;

    @Before
    public void setUp() {
        meter = new FixRateMeter();
    }

    @Test
    public void oneFixHasNoRate() {
        meter.onFix(1000L);
        // 一次定位推不出速率
        assertEquals(0d, meter.rateHz(), 0.001);
    }

    @Test
    public void oneHertzFixes() {
        for (int i = 0; i < 5; i++) {
            meter.onFix(1000L + i * 1000L);
        }
        assertEquals(1d, meter.rateHz(), 0.01);
    }

    @Test
    public void twoHertzFixes() {
        for (int i = 0; i < 6; i++) {
            meter.onFix(1000L + i * 500L);
        }
        assertEquals(2d, meter.rateHz(), 0.01);
    }

    @Test
    public void duplicateTimestampsCountOnce() {
        // 部分 ROM 会把同一次解算反复推给应用；算进去速率会翻好几倍
        meter.onFix(1000L);
        meter.onFix(1000L);
        meter.onFix(1000L);
        meter.onFix(2000L);
        assertEquals(1d, meter.rateHz(), 0.01);
    }

    @Test
    public void windowIsBounded() {
        // 100 次 10 Hz 的定位：窗口只留最近 32 次，速率仍是 10 Hz
        for (int i = 0; i < 100; i++) {
            meter.onFix(1000L + i * 100L);
        }
        assertEquals(10d, meter.rateHz(), 0.05);
    }

    @Test
    public void resetClearsSamples() {
        meter.onFix(1000L);
        meter.onFix(2000L);
        meter.reset();
        assertEquals(0d, meter.rateHz(), 0.001);
    }
}
