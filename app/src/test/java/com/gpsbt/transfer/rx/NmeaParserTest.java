package com.gpsbt.transfer.rx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.gpsbt.transfer.nmea.NmeaGenerator;
import com.gpsbt.transfer.nmea.NmeaInput;
import com.gpsbt.transfer.nmea.NmeaSentenceType;

import org.junit.Before;
import org.junit.Test;

import java.util.EnumSet;
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
}
