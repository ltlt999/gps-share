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
        String frame = generator.buildFrame(source, all);

        NmeaInput parsed = null;
        // 生成器的顺序是 GGA → RMC → GSA → GSV → VTG：卫星语句在位置语句**之后**，
        // 所以第一轮发布的快照还没有卫星。喂两轮，第二轮的位置快照就带上了
        // 第一轮攒下的卫星——这正是真实接收端的稳态行为（卫星滞后一轮）。
        for (int cycle = 0; cycle < 2; cycle++) {
            for (String line : frame.split("\r\n")) {
                NmeaInput snapshot = parser.accept(line);
                if (snapshot != null) {
                    parsed = snapshot;
                }
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
        String frame = generator.buildFrame(source, EnumSet.of(NmeaSentenceType.GSV));

        for (String line : frame.split("\r\n")) {
            parser.accept(line);
        }

        NmeaInput parsed = parser.accept(generator.gga(source));
        assertNotNull(parsed);
        assertEquals(3, parsed.satellitesInView());
        assertEquals(3, parsed.satellites().get(0).prn);
        assertEquals(42, parsed.satellites().get(0).snr);
        assertEquals("GP", parsed.satellites().get(0).talker);
    }

    @Test
    public void gsaMarksUsedSatellites() {
        NmeaInput source = sample();
        for (String line : generator.gsa(source).split("\r\n")) {
            parser.accept(line);
        }
        for (String line : generator.gsv(source)) {
            parser.accept(line);
        }

        NmeaInput parsed = parser.accept(generator.gga(source));
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
        NmeaInput parsed = parser.accept(generator.rmc(source));

        assertNotNull(parsed);
        assertEquals(-33.8688d, parsed.latitude, 1e-4);
        assertEquals(-151.2093d, parsed.longitude, 1e-4);
    }

    @Test
    public void invalidFixIsReportedAsInvalid() {
        NmeaInput source = new NmeaInput();
        source.valid = false;
        source.utcMillis = UTC;

        NmeaInput parsed = parser.accept(generator.gga(source));

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

        NmeaInput parsed = parser.accept(generator.gga(new NmeaInput()));
        assertNotNull(parsed);
        assertTrue(!parsed.valid);
        assertEquals(0, parsed.satellitesInView());
    }

    @Test
    public void gsvSecondCycleDoesNotAccumulateForever() {
        NmeaInput source = sample();
        for (int i = 0; i < 3; i++) {
            for (String line : generator.gsv(source)) {
                parser.accept(line);
            }
        }

        NmeaInput parsed = parser.accept(generator.gga(source));
        assertNotNull(parsed);
        // 每轮 GSV 的第一页会清空上一轮，不能累积成 9 颗
        assertEquals(3, parsed.satellitesInView());
    }
}
