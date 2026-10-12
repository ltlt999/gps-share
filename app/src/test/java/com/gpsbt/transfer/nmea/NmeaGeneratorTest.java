package com.gpsbt.transfer.nmea;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

public class NmeaGeneratorTest {

    private static final long UTC = 1700000000000L; // 2023-11-14 22:13:20 UTC

    private final NmeaGenerator generator = new NmeaGenerator(1.0f);

    private NmeaInput sample() {
        NmeaInput in = new NmeaInput();
        in.valid = true;
        in.utcMillis = UTC;
        in.latitude = 31.230416;
        in.longitude = 121.473701;
        in.altitudeM = 12.5;
        in.speedMps = 5.14444;
        in.bearingDeg = 87.0;
        in.accuracyM = 5.0f;
        in.satellitesUsed = 3;
        in.addSat(new NmeaInput.Sat(3, 45, 120, 42, true));
        in.addSat(new NmeaInput.Sat(14, 12, 300, 28, true));
        in.addSat(new NmeaInput.Sat(22, 70, 40, 0, false));
        in.addSat(new NmeaInput.Sat(19, 80, 200, 35, true));
        in.addSat(new NmeaInput.Sat(8, 5, 10, 0, false));
        return in;
    }

    private String payload(String sentence) {
        String s = sentence.trim();
        int star = s.lastIndexOf('*');
        return s.substring(1, star);
    }

    private String checksumOf(String sentence) {
        String s = sentence.trim();
        return s.substring(s.lastIndexOf('*') + 1);
    }

    @Test
    public void timeAndDateUseUtc() {
        assertEquals("221320.00", NmeaGenerator.utcTime(UTC));
        assertEquals("141123", NmeaGenerator.utcDate(UTC));
    }

    @Test
    public void latitudeConversion() {
        assertEquals("3113.8250,N", NmeaGenerator.latitude(31.230416));
        assertEquals("0000.0000,N", NmeaGenerator.latitude(0.0));
        assertEquals("3351.4100,S", NmeaGenerator.latitude(-33.856833));
    }

    @Test
    public void longitudeConversion() {
        assertEquals("12128.4221,E", NmeaGenerator.longitude(121.473701));
        assertEquals("00000.0000,E", NmeaGenerator.longitude(0.0));
        assertEquals("11812.6050,W", NmeaGenerator.longitude(-118.210083));
    }

    @Test
    public void hdopEstimatedFromAccuracy() {
        NmeaInput in = new NmeaInput();
        in.accuracyM = 5.0f;
        assertEquals(1.0f, NmeaGenerator.hdopFor(in, 1.0f), 0.0001f);
        in.accuracyM = 100.0f;
        assertEquals(9.9f, NmeaGenerator.hdopFor(in, 1.0f), 0.0001f);
        in.accuracyM = 1.0f;
        assertEquals(0.5f, NmeaGenerator.hdopFor(in, 1.0f), 0.0001f);
        in.accuracyM = Float.NaN;
        assertEquals(2.5f, NmeaGenerator.hdopFor(in, 2.5f), 0.0001f);
    }

    @Test
    public void ggaFields() {
        String[] f = payload(generator.gga(sample())).split(",");
        assertEquals("GPGGA", f[0]);
        assertEquals("221320.00", f[1]);
        assertEquals("3113.8250", f[2]);
        assertEquals("N", f[3]);
        assertEquals("12128.4221", f[4]);
        assertEquals("E", f[5]);
        assertEquals("1", f[6]);
        assertEquals("03", f[7]);
        assertEquals("1.0", f[8]);
        assertEquals("12.5", f[9]);
        assertEquals("M", f[10]);
        assertEquals("0.0", f[11]);
        assertEquals("M", f[12]);
    }

    @Test
    public void ggaInvalidFixReportsZeroSatellites() {
        NmeaInput in = sample();
        in.valid = false;
        in.satellitesUsed = 0;
        String[] f = payload(generator.gga(in)).split(",");
        assertEquals("0", f[6]);
        assertEquals("00", f[7]);
        assertEquals("1.0", f[8]);
    }

    @Test
    public void rmcAllFields() {
        String[] f = payload(generator.rmc(sample())).split(",");
        assertEquals("GPRMC", f[0]);
        assertEquals("221320.00", f[1]);
        assertEquals("A", f[2]);
        assertEquals("3113.8250", f[3]);
        assertEquals("N", f[4]);
        assertEquals("12128.4221", f[5]);
        assertEquals("E", f[6]);
        assertEquals("10.0", f[7]);
        assertEquals("87.0", f[8]);
        assertEquals("141123", f[9]);
        assertEquals("A", f[12]);
    }

    @Test
    public void rmcInvalidReportsVoidAndZeroSpeed() {
        NmeaInput in = sample();
        in.valid = false;
        String[] f = payload(generator.rmc(in)).split(",");
        assertEquals("V", f[2]);
        assertEquals("0.0", f[7]);
        assertEquals("0.0", f[8]);
        assertEquals("N", f[12]);
    }

    @Test
    public void gsaListsOnlySatsUsedInFix() {
        String[] f = payload(generator.gsa(sample())).split(",");
        assertEquals("GPGSA", f[0]);
        assertEquals("A", f[1]);
        assertEquals("3", f[2]);
        assertEquals("03", f[3]);
        assertEquals("14", f[4]);
        assertEquals("19", f[5]);
        assertEquals("", f[6]);
        assertEquals("", f[14]);
        assertEquals("1.2", f[15]);
        assertEquals("1.0", f[16]);
        assertEquals("0.8", f[17]);
    }

    @Test
    public void gsvPagesOfFourWhenEightSats() {
        NmeaInput in = sample();
        in.addSat(new NmeaInput.Sat(21, 30, 60, 20, false));
        in.addSat(new NmeaInput.Sat(27, 66, 180, 44, true));
        in.addSat(new NmeaInput.Sat(31, 15, 240, 12, false));
        List<String> sentences = generator.gsv(in);
        assertEquals(2, sentences.size());

        String[] page1 = payload(sentences.get(0)).split(",");
        assertEquals("GPGSV", page1[0]);
        assertEquals("2", page1[1]);
        assertEquals("1", page1[2]);
        assertEquals("8", page1[3]);
        assertEquals("03", page1[4]);
        assertEquals("45", page1[5]);
        assertEquals("120", page1[6]);
        assertEquals("42", page1[7]);

        String[] page2 = payload(sentences.get(1)).split(",");
        assertEquals("2", page2[1]);
        assertEquals("2", page2[2]);
        assertEquals("8", page2[3]);
        // 第 5～8 颗依次是 PRN 8 / 21 / 27 / 31
        assertEquals("08", page2[4]);
        assertEquals("21", page2[8]);
    }

    @Test
    public void gsvBlankSnrWhenZeroAndSerializesElevationAzimuth() {
        List<String> sentences = generator.gsv(sample());
        String[] page1 = payload(sentences.get(0)).split(",");
        // 第 3 颗卫星 snr=0 → 空字段
        assertEquals("22", page1[12]);
        assertEquals("70", page1[13]);
        assertEquals("040", page1[14]);
        assertEquals("", page1[15]);
    }

    @Test
    public void gsvGroupsByConstellation() {
        NmeaInput in = sample();
        // 北斗（201-237）与 GLONASS（65-96）编号段
        in.addSat(new NmeaInput.Sat(206, 55, 210, 40, true));
        in.addSat(new NmeaInput.Sat(68, 30, 80, 33, false));
        List<String> sentences = generator.gsv(in);
        // 5 颗 GPS（2 页）+ 1 颗北斗 + 1 颗 GLONASS，各组独立分页
        assertEquals(4, sentences.size());

        String[] gps = payload(sentences.get(0)).split(",");
        assertEquals("GPGSV", gps[0]);
        assertEquals("2", gps[1]);
        assertEquals("5", gps[3]);

        String[] bd = payload(sentences.get(2)).split(",");
        assertEquals("BDGSV", bd[0]);
        assertEquals("1", bd[1]);
        assertEquals("1", bd[3]);
        assertEquals("206", bd[4]);

        String[] gl = payload(sentences.get(3)).split(",");
        assertEquals("GLGSV", gl[0]);
        assertEquals("68", gl[4]);
    }

    @Test
    public void gsvUsesConstellationTalkerWhenProvided() {
        // 系统星座类型优先于编号推断：PRN 06 落在 GPS 段，但系统明确说是北斗
        NmeaInput in = new NmeaInput();
        in.addSat(new NmeaInput.Sat(6, 40, 90, 30, true, "BD"));
        in.addSat(new NmeaInput.Sat(3, 45, 120, 42, true, "GP"));
        List<String> sentences = generator.gsv(in);
        assertEquals(2, sentences.size());
        assertEquals("BDGSV", payload(sentences.get(0)).split(",")[0]);
        assertEquals("GPGSV", payload(sentences.get(1)).split(",")[0]);
    }

    @Test
    public void gsvEmptyWhenNoSatellites() {
        NmeaInput in = sample();
        in.clearSats();
        assertTrue(generator.gsv(in).isEmpty());
    }

    @Test
    public void vtgFields() {
        String[] f = payload(generator.vtg(sample())).split(",");
        assertEquals("GPVTG", f[0]);
        assertEquals("87.0", f[1]);
        assertEquals("T", f[2]);
        assertEquals("M", f[4]);
        assertEquals("10.0", f[5]);
        assertEquals("N", f[6]);
        assertEquals("18.5", f[7]);
        assertEquals("K", f[8]);
        assertEquals("A", f[9]);
    }

    @Test
    public void buildFrameEmitsInFixedOrderWithChecksumAndCrLf() {
        String frame = generator.buildFrame(sample(), NmeaSentenceType.all());
        assertTrue(frame.endsWith("\r\n"));
        int gga = frame.indexOf("$GPGGA");
        int rmc = frame.indexOf("$GPRMC");
        int gsa = frame.indexOf("$GPGSA");
        int gsv = frame.indexOf("$GPGSV");
        int vtg = frame.indexOf("$GPVTG");
        assertTrue(gga > -1 && rmc > gga && gsa > rmc && gsv > gsa && vtg > gsv);

        for (String line : frame.split("\r\n")) {
            if (line.isEmpty()) {
                continue;
            }
            assertEquals(NmeaChecksum.toHex(NmeaChecksum.xor(payload(line))), checksumOf(line));
        }
    }

    @Test
    public void buildFrameSkipsDisabledSentences() {
        Set<NmeaSentenceType> only = EnumSet.of(NmeaSentenceType.GGA);
        String frame = generator.buildFrame(sample(), only);
        assertTrue(frame.contains("$GPGGA"));
        assertFalse(frame.contains("$GPRMC"));
        assertFalse(frame.contains("$GPGSA"));
        assertFalse(frame.contains("$GPGSV"));
        assertFalse(frame.contains("$GPVTG"));
        // split 已去掉尾部空串，1 帧即 1 个元素
        assertEquals(1, frame.split("\r\n").length);
    }

    @Test
    public void knotsConversionFactor() {
        assertEquals(10.0, 5.14444 * NmeaGenerator.MS_TO_KNOTS, 0.01);
    }
}
