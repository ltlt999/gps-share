package com.gpsbt.transfer.rx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.gpsbt.transfer.nmea.NmeaInput;

import org.junit.Before;
import org.junit.Test;

public class InjectionPolicyTest {

    private InjectionPolicy policy;

    @Before
    public void setUp() {
        policy = new InjectionPolicy();
    }

    private static NmeaInput fix(long seq, long ageMillis) {
        NmeaInput in = new NmeaInput();
        in.valid = true;
        in.seq = seq;
        in.ageMillis = ageMillis;
        in.utcMillis = 1_700_000_000_000L;
        in.latitude = 31.2;
        in.longitude = 121.4;
        in.altitudeM = 12.5;
        in.speedMps = 3.0;
        in.bearingDeg = 87.0;
        in.accuracyM = 12.5f;
        in.source = NmeaInput.Source.GPS;
        return in;
    }

    @Test
    public void injectsFirstFix() {
        assertEquals(InjectionPolicy.Decision.INJECT, policy.decide(fix(1L, 100L)));
    }

    @Test
    public void sameSeqIsDuplicate() {
        policy.decide(fix(1L, 100L));
        assertEquals(InjectionPolicy.Decision.DUPLICATE, policy.decide(fix(1L, 200L)));
    }

    @Test
    public void newSeqIsInjected() {
        policy.decide(fix(1L, 100L));
        assertEquals(InjectionPolicy.Decision.INJECT, policy.decide(fix(2L, 100L)));
    }

    @Test
    public void invalidFixIsRejected() {
        NmeaInput in = fix(1L, 100L);
        in.valid = false;
        assertEquals(InjectionPolicy.Decision.INVALID, policy.decide(in));
    }

    @Test
    public void expiredFixIsRejected() {
        assertEquals(InjectionPolicy.Decision.EXPIRED, policy.decide(fix(1L, 6000L)));
    }

    @Test
    public void outOfRangeCoordinatesAreRejected() {
        NmeaInput in = fix(1L, 100L);
        in.latitude = 91.0;
        assertEquals(InjectionPolicy.Decision.OUT_OF_RANGE, policy.decide(in));

        NmeaInput lon = fix(2L, 100L);
        lon.longitude = -181.0;
        assertEquals(InjectionPolicy.Decision.OUT_OF_RANGE, policy.decide(lon));
    }

    @Test
    public void negativeAgeIsRejected() {
        assertEquals(InjectionPolicy.Decision.OUT_OF_RANGE, policy.decide(fix(1L, -1L)));
    }

    @Test
    public void implausibleAccuracyIsRejected() {
        NmeaInput zero = fix(1L, 100L);
        zero.accuracyM = 0f;
        assertEquals(InjectionPolicy.Decision.OUT_OF_RANGE, policy.decide(zero));

        NmeaInput huge = fix(2L, 100L);
        huge.accuracyM = 200000f;
        assertEquals(InjectionPolicy.Decision.OUT_OF_RANGE, policy.decide(huge));
    }

    @Test
    public void unknownAccuracyIsAccepted() {
        // 精度未知不是精度非法：没有 ACC 的帧照样能注入
        NmeaInput in = fix(1L, 100L);
        in.accuracyM = Float.NaN;
        assertEquals(InjectionPolicy.Decision.INJECT, policy.decide(in));
    }

    @Test
    public void fallbackIdentityUsesPositionAndTime() {
        NmeaInput a = fix(0L, 100L);
        a.seq = 0L;
        NmeaInput b = fix(0L, 100L);
        b.seq = 0L;
        b.latitude = 31.3;
        policy.decide(a);
        assertEquals(InjectionPolicy.Decision.DUPLICATE, policy.decide(a));
        assertEquals(InjectionPolicy.Decision.INJECT, policy.decide(b));
    }

    @Test
    public void senderRestartIsAccepted() {
        // 发送端重启后 SEQ 归 1：判定用「不同」而不是「更大」，
        // 否则重启后的新位置会被当成重复而丢掉
        policy.decide(fix(57L, 100L));
        assertEquals(InjectionPolicy.Decision.INJECT, policy.decide(fix(1L, 100L)));
    }

    @Test
    public void satelliteSnapshotIsNotInjected() {
        NmeaInput sat = fix(1L, 100L);
        sat.withSatellites = true;
        assertEquals(InjectionPolicy.Decision.SATELLITES_ONLY, policy.decide(sat));
    }

    @Test
    public void satelliteSnapshotDoesNotConsumeIdentity() {
        // 卫星快照与位置快照是同一个解算，身份相同。
        // 若卫星快照先把身份吃掉，随后到达的位置快照就会被判成重复 ——
        // 关掉 RMC 语句时（位置拖到下一帧开头才发布）位置将永远注入不了。
        NmeaInput sat = fix(1L, 100L);
        sat.withSatellites = true;
        assertEquals(InjectionPolicy.Decision.SATELLITES_ONLY, policy.decide(sat));
        assertEquals(InjectionPolicy.Decision.INJECT, policy.decide(fix(1L, 100L)));
    }

    @Test
    public void payloadAnchorsTimeToNowMinusAge() {
        NmeaInput in = fix(1L, 842L);
        InjectionPolicy.Payload p = InjectionPolicy.payloadOf(in, 1_700_000_000_000L, 10_000_000_000L);

        assertEquals(1_699_999_999_158L, p.timeMillis);
        assertEquals(10_000_000_000L - 842_000_000L, p.elapsedRealtimeNanos);
        assertEquals(12.5f, p.accuracyM, 0.001f);
        assertEquals(31.2, p.latitude, 0.0001);
    }

    @Test
    public void payloadKeepsAllAccuracies() {
        NmeaInput in = fix(1L, 0L);
        in.verticalAccuracyM = 8.0f;
        in.speedAccuracyMps = 0.8f;
        in.bearingAccuracyDeg = 15.0f;
        InjectionPolicy.Payload p = InjectionPolicy.payloadOf(in, 0L, 0L);
        assertEquals(8.0f, p.verticalAccuracyM, 0.001f);
        assertEquals(0.8f, p.speedAccuracyMps, 0.001f);
        assertEquals(15.0f, p.bearingAccuracyDeg, 0.001f);
    }

    @Test
    public void payloadWithoutLocalUptimeKeepsElapsedAtZero() {
        NmeaInput in = fix(1L, 842L);
        InjectionPolicy.Payload p = InjectionPolicy.payloadOf(in, 1_700_000_000_000L, 0L);

        // 拿不到本机 uptime 时不能瞎填一个负值：负的 uptime 会让平台
        // 算出的年龄变成负数，比不设还糟
        assertEquals(0L, p.elapsedRealtimeNanos);
        // 墙上时钟照常减
        assertEquals(1_699_999_999_158L, p.timeMillis);
    }

    @Test
    public void payloadKeepsUnknownAccuracyAsNaN() {
        NmeaInput in = fix(1L, 0L);
        in.accuracyM = Float.NaN;
        in.verticalAccuracyM = Float.NaN;
        InjectionPolicy.Payload p = InjectionPolicy.payloadOf(in, 0L, 0L);
        assertTrue(Float.isNaN(p.accuracyM));
        assertTrue(Float.isNaN(p.verticalAccuracyM));
    }

    @Test
    public void resetForgetsIdentity() {
        policy.decide(fix(1L, 100L));
        policy.reset();
        assertEquals(InjectionPolicy.Decision.INJECT, policy.decide(fix(1L, 100L)));
    }
}
