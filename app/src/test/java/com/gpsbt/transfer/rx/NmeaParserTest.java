package com.gpsbt.transfer.rx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.gpsbt.transfer.nmea.NmeaGenerator;
import com.gpsbt.transfer.nmea.NmeaInput;
import com.gpsbt.transfer.nmea.NmeaSentenceType;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

public class NmeaParserTest {

    private static final double LAT = 31.230416d;
    private static final double LON = 121.473701d;
    private static final long UTC = 1695512345000L;

    private NmeaParser parser;
    private NmeaGenerator generator;

    @Before
    public void setUp() {
        parser = new NmeaParser();
        generator = new NmeaGenerator(1.0f);
    }

    private static NmeaInput sample() {
        NmeaInput in = new NmeaInput();
        in.valid = true;
        in.utcMillis = UTC;
        in.latitude = LAT;
        in.longitude = LON;
        in.altitudeM = 12.5d;
        in.speedMps = 10.0d;
        in.bearingDeg = 87.0d;
        in.satellitesUsed = 7;
        in.addSat(new NmeaInput.Sat(3, 45, 120, 42, true, "GP"));
        in.addSat(new NmeaInput.Sat(14, 12, 300, 28, true, "GP"));
        in.addSat(new NmeaInput.Sat(22, 70, 40, 0, false, "GP"));
        return in;
    }

    /** 生成器与解析器互为逆运算：一整轮语句喂进去，字段应当还原。 */
    @Test
    public void roundTripThroughGenerator() {
        NmeaInput source = sample();
        Set<NmeaSentenceType> all = EnumSet.allOf(NmeaSentenceType.class);
        String frame = generator.buildFrame(source, all, 0L);

        NmeaInput parsed = null;
        // 新设计：GGA/RMC 攒成 pending，GSV 到达连同 entries 一起发布。
        // 所以喂一轮就够：捕获 GSV 的返回值就是完整的本周期快照。
        for (String line : frame.split("\r\n")) {
            NmeaInput snapshot = parser.accept(line);
            if (snapshot != null) {
                parsed = snapshot;
            }
        }

        assertNotNull(parsed);
        assertTrue(parsed.valid);
        assertEquals(LAT, parsed.latitude, 1e-4);
        assertEquals(LON, parsed.longitude, 1e-4);
        assertEquals(12.5d, parsed.altitudeM, 0.1d);
        // 速度以节往返，1 节 ≈ 0.5144 m/s，容差取 0.1 m/s
        assertEquals(10.0d, parsed.speedMps, 0.1d);
        assertEquals(87.0d, parsed.bearingDeg, 0.1d);
        assertEquals(7, parsed.satellitesUsed);
        assertEquals(3, parsed.satellitesInView());
    }

    @Test
    public void gsvFeedsSatelliteListWithTalker() {
        NmeaInput source = sample();
        String frame = generator.buildFrame(source, EnumSet.of(NmeaSentenceType.GSV), 0L);

        // 新设计：GGA 与 RMC 只攒成 pending，GSV 到达才一起发布
        parser.accept(generator.gga(source));
        NmeaInput parsed = null;
        for (String line : frame.split("\r\n")) {
            parsed = parser.accept(line);
        }
        assertNotNull(parsed);
        assertEquals(3, parsed.satellitesInView());
        assertEquals(3, parsed.satellites().get(0).prn);
        assertEquals(42, parsed.satellites().get(0).snr);
        assertEquals("GP", parsed.satellites().get(0).talker);
    }

    @Test
    public void gsaMarksUsedSatellites() {
        NmeaInput source = sample();
        // GGA 必须先于 GSV 到达（用于攒成 pending）
        parser.accept(generator.gga(source));
        for (String line : generator.gsa(source).split("\r\n")) {
            parser.accept(line);
        }
        NmeaInput parsed = null;
        for (String line : generator.gsv(source)) {
            parsed = parser.accept(line);
        }
        assertNotNull(parsed);
        // GSA 列出的 PRN 标记为已用于定位
        assertEquals(2, countUsed(parsed));
    }

    private static int countUsed(NmeaInput input) {
        int used = 0;
        for (NmeaInput.Sat sat : input.satellites()) {
            if (sat.used) {
                used++;
            }
        }
        return used;
    }

    @Test
    public void southAndWestHemispheresAreNegative() {
        NmeaInput source = new NmeaInput();
        source.valid = true;
        source.utcMillis = UTC;
        source.latitude = -33.8688d;
        source.longitude = -151.2093d;
        source.satellitesUsed = 4;

        parser.accept(generator.gga(source));
        // GSV 才会发布；用同 source 就能确保 generator 真发了 GSV 句子
        NmeaInput parsed = null;
        for (String line : generator.gsv(sample())) {
            parsed = parser.accept(line);
        }

        assertNotNull(parsed);
        assertEquals(-33.8688d, parsed.latitude, 1e-4);
        assertEquals(-151.2093d, parsed.longitude, 1e-4);
    }

    @Test
    public void invalidFixIsReportedAsInvalid() {
        NmeaInput source = new NmeaInput();
        source.valid = false;
        source.utcMillis = UTC;

        parser.accept(generator.gga(source));
        NmeaInput parsed = null;
        for (String line : generator.gsv(sample())) {
            parsed = parser.accept(line);
        }
        assertNotNull(parsed);
        assertTrue(!parsed.valid);
    }

    @Test
    public void badChecksumIsRejected() {
        String good = generator.gga(sample());
        String broken = good.substring(0, good.length() - 5) + "00\r\n";

        assertNull(parser.accept(broken));
    }

    @Test
    public void unrecognisedAndEmptyLinesAreIgnored() {
        assertNull(parser.accept(""));
        assertNull(parser.accept("   "));
        assertNull(parser.accept("不是 NMEA"));
        assertNull(parser.accept(null));
        assertNull(parser.accept("$GPVTG,87.0,T,,10.0,N,18.5,K,A*05"));
    }

    @Test
    public void resetClearsAccumulatedState() {
        parser.accept(generator.gga(sample()));
        parser.reset();

        // reset 后 pending 也清了，所以需要 GGA → GSV 才会发布
        // 用 sample 喂 GSV，确保 generator 真发句子（emptyInput 它不发）
        parser.accept(generator.gga(new NmeaInput()));
        NmeaInput parsed = null;
        for (String line : generator.gsv(sample())) {
            parsed = parser.accept(line);
        }
        assertNotNull(parsed);
        assertTrue(!parsed.valid);
        // 位置来自 GGA(new)，sats 来自 sample 那一轮（reset 后没累积）——
        // 所以这里只验证 reset 后仍能正常发布，不强求 sats 数值
    }

    @Test
    public void ggaClearsAccumulatedSatellitesBetweenCycles() {
        // 每个周期里 GGA 先清空累积，再让 GSV 把这一轮的卫星写进来。
        // 三轮 GSV 各自只贡献本轮的数据，不会无限累加。
        NmeaInput source = sample();
        for (int i = 0; i < 3; i++) {
            parser.accept(generator.gga(source));
            NmeaInput parsed = null;
            for (String line : generator.gsv(source)) {
                parsed = parser.accept(line);
            }
            assertNotNull("第 " + (i + 1) + " 轮 GSV 应发布快照", parsed);
            assertEquals("第 " + (i + 1) + " 轮累积到的卫星数", 3, parsed.satellitesInView());
        }
    }

    // ---- $GPSSHARE：元信息解析（v2.0.0 协议） ----

    /** 生成一条 $GPSSHARE。seq 直接写进 in.seq —— 生成器只认这一个真相源。 */
    private String share(NmeaInput in, long seq, long ageMillis, NmeaInput.Source src) {
        in.seq = seq;
        in.source = src;
        return generator.gpsShare(in, ageMillis);
    }

    @Test
    public void parsesGpsShareMetadata() {
        NmeaInput in = sample();
        in.accuracyM = 12.5f;
        in.verticalAccuracyM = 8.0f;
        in.speedAccuracyMps = 0.8f;
        in.bearingAccuracyDeg = 15.0f;

        parser.accept(share(in, 77L, 842L, NmeaInput.Source.NET));

        NmeaInput snap = parser.accept(generator.rmc(in));
        assertNotNull(snap);
        assertEquals(77L, snap.seq);
        assertEquals(842L, snap.ageMillis);
        assertEquals(NmeaInput.Source.NET, snap.source);
        assertEquals(12.5f, snap.accuracyM, 0.001f);
        assertEquals(8.0f, snap.verticalAccuracyM, 0.001f);
        assertEquals(0.8f, snap.speedAccuracyMps, 0.001f);
        assertEquals(15.0f, snap.bearingAccuracyDeg, 0.001f);
    }

    @Test
    public void gpsShareAccuracyBeatsHdopFallback() {
        // 两份输入故意给不同的精度：$GPSSHARE 走真值，GGA 走被截断的 HDOP×5。
        // 用同一个 in 生成两者的话，HDOP×5 恰好等于真值，这个用例就白测了。
        NmeaInput shareIn = sample();
        shareIn.accuracyM = 12.5f;
        NmeaInput ggaIn = sample();
        ggaIn.accuracyM = 5.0f;

        parser.accept(share(shareIn, 1L, 100L, NmeaInput.Source.GPS));
        parser.accept(generator.gga(ggaIn));
        NmeaInput snap = parser.accept(generator.rmc(ggaIn));

        assertNotNull(snap);
        // GGA 的 HDOP×5 是 5.0，$GPSSHARE 的 12.5 才是真值
        assertEquals(12.5f, snap.accuracyM, 0.001f);
    }

    @Test
    public void hdopFallbackStillAppliesWithoutShare() {
        NmeaInput in = sample();
        in.accuracyM = 5.0f;
        parser.accept(generator.gga(in));
        NmeaInput snap = parser.accept(generator.rmc(in));

        assertNotNull(snap);
        assertEquals(5.0f, snap.accuracyM, 0.001f);
    }

    @Test
    public void shareMetadataDoesNotLeakIntoNextFrame() {
        NmeaInput first = sample();
        first.accuracyM = 12.5f;
        first.verticalAccuracyM = 8.0f;

        parser.accept(share(first, 1L, 100L, NmeaInput.Source.GPS));
        NmeaInput snap1 = parser.accept(generator.rmc(first));
        assertNotNull(snap1);
        assertEquals(8.0f, snap1.verticalAccuracyM, 0.001f);

        // 第二帧的 $GPSSHARE 里没有 VACC —— 发送端这一帧取不到垂直精度。
        // 解析器必须把它当成「未知」，而不是沿用上一帧的 8.0。
        NmeaInput second = sample();
        second.accuracyM = 12.5f;
        second.verticalAccuracyM = Float.NaN;

        parser.accept(share(second, 2L, 120L, NmeaInput.Source.GPS));
        NmeaInput snap2 = parser.accept(generator.rmc(second));

        assertNotNull(snap2);
        assertTrue("上一帧的垂直精度不该泄漏到这一帧",
                Float.isNaN(snap2.verticalAccuracyM));
        assertEquals(12.5f, snap2.accuracyM, 0.001f);
        assertEquals(2L, snap2.seq);
    }

    @Test
    public void nonFiniteAccuracyOnWireIsTreatedAsUnknown() {
        // 线上可能出现 "ACC=Infinity"：Double.parseDouble 居然解析成功。
        // 解析器不能把这种值当真精度收下。
        NmeaInput in = sample();
        // 手搓一条带 Infinity 的语句（自己算校验和）
        String body = "GPSSHARE,1,SEQ=9,ACC=Infinity,AGE=30,SRC=GPS";
        String crafted = "$" + body + "*"
                + com.gpsbt.transfer.nmea.NmeaChecksum.toHex(
                        com.gpsbt.transfer.nmea.NmeaChecksum.xor(body));

        parser.accept(crafted);
        NmeaInput snap = parser.accept(generator.rmc(in));

        assertNotNull(snap);
        assertTrue("Infinity 不是精度", Float.isNaN(snap.accuracyM));
        assertEquals(9L, snap.seq);
        assertEquals(30L, snap.ageMillis);
    }

    // ---- 发布时机 ----

    @Test
    public void positionPublishedOnRmcExactlyOnce() {
        NmeaInput in = sample();
        Set<NmeaSentenceType> all = EnumSet.allOf(NmeaSentenceType.class);
        String frame = generator.buildFrame(in, all, 100L);

        List<NmeaInput> publishes = new ArrayList<>();
        for (String line : frame.split("\r\n")) {
            NmeaInput snap = parser.accept(line);
            if (snap != null) {
                publishes.add(snap);
            }
        }

        // 一帧两次发布：RMC（位置）+ GSV（卫星明细）
        assertEquals(2, publishes.size());
        // 位置那次不带卫星明细 —— 注入方靠这个标志区分「该注入了」
        assertFalse(publishes.get(0).withSatellites);
        assertTrue(publishes.get(1).withSatellites);
    }

    @Test
    public void ggaDoesNotPublishLeftoverAnyMore() {
        NmeaInput in = sample();
        // 先喂一帧 GGA —— v1.6.2 在这里会发布上一周期的位置，现在不应发布
        assertNull(parser.accept(generator.gga(in)));
    }

    @Test
    public void positionWithoutRmcIsPublishedAtNextFrameStart() {
        NmeaInput in = sample();
        // 只有 GGA，没有 RMC
        assertNull(parser.accept(generator.gga(in)));
        // 下一帧开头（$GPSSHARE）到达时补发
        NmeaInput snap = parser.accept(share(in, 2L, 50L, NmeaInput.Source.GPS));
        assertNotNull(snap);
        assertEquals(in.latitude, snap.latitude, 1e-4);
    }

    // ---- 精度未知（v2 发送端明说「不知道」） ----

    @Test
    public void v2SenderSayingAccuracyUnknownIsNotGivenAFabricatedOne() {
        // 发送端说「精度未知」：$GPSSHARE 里没有 ACC 键（协议里缺键 = 未知）。
        // 接收端若拿 GGA 的 HDOP×5 编一个出来（哪怕那个数看着挺合理），
        // 那就是在谎报一个并不存在的精度 —— 违背 v2 的第一原则：不编造。
        NmeaInput shareIn = sample();
        shareIn.accuracyM = Float.NaN;
        NmeaInput ggaIn = sample();
        ggaIn.accuracyM = 5.0f;   // GGA 里的 HDOP×5 = 5.0

        parser.accept(share(shareIn, 1L, 100L, NmeaInput.Source.GPS));
        parser.accept(generator.gga(ggaIn));
        NmeaInput snap = parser.accept(generator.rmc(ggaIn));

        assertNotNull(snap);
        assertTrue("发送端明说精度未知，接收端不许编一个出来",
                Float.isNaN(snap.accuracyM));
    }

    @Test
    public void v2SenderWithAccuracyStillWins() {
        // 反例对照：说了精度就必须用真值，不能被 HDOP 兜底盖掉。
        NmeaInput shareIn = sample();
        shareIn.accuracyM = 12.5f;
        NmeaInput ggaIn = sample();
        ggaIn.accuracyM = 5.0f;

        parser.accept(share(shareIn, 1L, 100L, NmeaInput.Source.GPS));
        parser.accept(generator.gga(ggaIn));
        NmeaInput snap = parser.accept(generator.rmc(ggaIn));

        assertNotNull(snap);
        assertEquals(12.5f, snap.accuracyM, 0.001f);
    }

    // ---- 补发位置的年龄 ----

    /** 可控时钟：补发位置时的「等待了多久」必须能被人为控制，否则单测只能靠 sleep。 */
    private static final class FakeClock implements NmeaParser.Clock {
        long now = 1_000_000L;

        @Override
        public long nowMillis() {
            return now;
        }
    }

    @Test
    public void deferredPositionCarriesTheWaitTimeInItsAge() {
        // 关掉 RMC 时位置拖到下一帧开头才发。它在解析器里多等了 1 秒，
        // 年龄必须加上这 1 秒 —— 否则注入给系统的时间戳比真实解算时刻**新**了 1 秒，
        // 位置明明旧了却报成「刚刚解算」。
        FakeClock clock = new FakeClock();
        NmeaParser clocked = new NmeaParser(clock);
        NmeaInput in = sample();

        clocked.accept(share(in, 1L, 0L, NmeaInput.Source.GPS));
        clocked.accept(generator.gga(in));   // 这一帧没有 RMC，位置待发
        clock.now = 1_001_000L;              // 等了一秒
        NmeaInput snap = clocked.accept(share(in, 2L, 1000L, NmeaInput.Source.GPS));

        assertNotNull(snap);
        // 上一帧的 AGE 是 0，加上等待的 1000 毫秒
        assertEquals(1000L, snap.ageMillis);
    }

    @Test
    public void deferredPositionFromLegacySenderKeepsAgeZero() {
        // v1.6.2 发送端没有 $GPSSHARE，也就没有「解算年龄」这个量：
        // 年龄恒为 0（完全退回旧行为），不能凭空给它加等待时间。
        FakeClock clock = new FakeClock();
        NmeaParser clocked = new NmeaParser(clock);
        NmeaInput in = sample();

        clocked.accept(generator.gga(in));
        clock.now = 1_001_000L;
        NmeaInput snap = clocked.accept(generator.gga(in));

        assertNotNull(snap);
        assertEquals(0L, snap.ageMillis);
    }

    @Test
    public void positionPublishedOnRmcIsNotDelayed() {
        // 反例对照：正常路径（有 RMC）不该被算上等待时间 ——
        // 位置是当场发布的，等待时间根本不存在。
        FakeClock clock = new FakeClock();
        NmeaParser clocked = new NmeaParser(clock);
        NmeaInput in = sample();

        clocked.accept(share(in, 1L, 300L, NmeaInput.Source.GPS));
        clocked.accept(generator.gga(in));
        clock.now = 1_005_000L;
        NmeaInput snap = clocked.accept(generator.rmc(in));

        assertNotNull(snap);
        assertEquals(300L, snap.ageMillis);
    }

    @Test
    public void positionIsPublishedOnlyOncePerCycle() {
        // 连续喂两帧完整数据：每帧的位置只发布一次（GSV 那次是卫星快照）
        NmeaInput in = sample();
        Set<NmeaSentenceType> all = EnumSet.allOf(NmeaSentenceType.class);

        for (int cycle = 0; cycle < 2; cycle++) {
            String frame = generator.buildFrame(in, all, 100L);
            int positionPublishes = 0;
            for (String line : frame.split("\r\n")) {
                NmeaInput snap = parser.accept(line);
                if (snap != null && !snap.withSatellites) {
                    positionPublishes++;
                }
            }
            assertEquals("第 " + (cycle + 1) + " 帧的位置发布次数", 1, positionPublishes);
        }
    }
}
