package com.gpsbt.transfer.nmea;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class NmeaInputTest {

    @Test
    public void copyCarriesNewMetadata() {
        NmeaInput in = new NmeaInput();
        in.valid = true;
        in.utcMillis = 1_700_000_000_000L;
        in.seq = 42L;
        in.ageMillis = 842L;
        in.fixElapsedRealtimeNanos = 5_000_000_000L;
        in.verticalAccuracyM = 8.5f;
        in.speedAccuracyMps = 0.8f;
        in.bearingAccuracyDeg = 15f;
        in.source = NmeaInput.Source.NET;
        in.withSatellites = true;

        NmeaInput copy = in.copy();

        assertEquals(42L, copy.seq);
        assertEquals(842L, copy.ageMillis);
        assertEquals(5_000_000_000L, copy.fixElapsedRealtimeNanos);
        assertEquals(8.5f, copy.verticalAccuracyM, 0.001f);
        assertEquals(0.8f, copy.speedAccuracyMps, 0.001f);
        assertEquals(15f, copy.bearingAccuracyDeg, 0.001f);
        assertEquals(NmeaInput.Source.NET, copy.source);
        assertTrue(copy.withSatellites);
        // 既有字段同样要跟着复制
        assertTrue(copy.valid);
        assertEquals(1_700_000_000_000L, copy.utcMillis);
    }

    @Test
    public void agePrefersElapsedRealtime() {
        NmeaInput in = new NmeaInput();
        in.fixElapsedRealtimeNanos = 10_000_000_000L;
        in.utcMillis = 1_700_000_000_000L;
        // uptime 差 842 ms → 年龄 842；墙上时钟会给出完全不同的值
        assertEquals(842L, in.computeAgeMillis(10_842_000_000L, 9_999_999_999L));
    }

    @Test
    public void ageFallsBackToWallClock() {
        NmeaInput in = new NmeaInput();
        in.fixElapsedRealtimeNanos = 0L;
        in.utcMillis = 1_700_000_000_000L;
        assertEquals(1500L, in.computeAgeMillis(0L, 1_700_000_001_500L));
    }

    @Test
    public void ageIsZeroWhenNothingKnown() {
        NmeaInput in = new NmeaInput();
        assertEquals(0L, in.computeAgeMillis(0L, 0L));
    }

    @Test
    public void ageFallsBackToWallClockWhenUptimeWentBackwards() {
        NmeaInput in = new NmeaInput();
        in.fixElapsedRealtimeNanos = 5_000_000_000L;   // now 反而更早
        in.utcMillis = 1_700_000_000_000L;
        assertEquals(1500L, in.computeAgeMillis(999_000_000L, 1_700_000_001_500L));
    }

    @Test
    public void ageClampsToZeroWhenClockWentBackwards() {
        NmeaInput in = new NmeaInput();
        in.utcMillis = 1_700_000_000_000L;
        assertEquals(0L, in.computeAgeMillis(0L, 1_699_999_000_000L));
    }

    @Test
    public void sourceWireRoundTrip() {
        assertEquals("GPS", NmeaInput.Source.GPS.wire());
        assertEquals("NET", NmeaInput.Source.NET.wire());
        assertEquals("NONE", NmeaInput.Source.NONE.wire());
        assertEquals(NmeaInput.Source.GPS, NmeaInput.Source.fromWire("gps"));
        assertEquals(NmeaInput.Source.GPS, NmeaInput.Source.fromWire(" gps "));
        assertEquals(NmeaInput.Source.NET, NmeaInput.Source.fromWire("NET"));
        assertEquals(NmeaInput.Source.NONE, NmeaInput.Source.fromWire("??"));
        assertEquals(NmeaInput.Source.NONE, NmeaInput.Source.fromWire(null));
    }
}
