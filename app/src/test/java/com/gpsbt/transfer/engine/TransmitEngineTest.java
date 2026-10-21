package com.gpsbt.transfer.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.gpsbt.transfer.nmea.NmeaGenerator;
import com.gpsbt.transfer.nmea.NmeaInput;
import com.gpsbt.transfer.nmea.NmeaSentenceType;

import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class TransmitEngineTest {

    private final List<byte[]> written = new ArrayList<>();
    private TransmitEngine engine;

    private TransmitEngine.Sink sink = new TransmitEngine.Sink() {
        @Override
        public void write(byte[] data, int length) {
            byte[] copy = new byte[length];
            System.arraycopy(data, 0, copy, 0, length);
            written.add(copy);
        }
    };

    private final NmeaGenerator generator = new NmeaGenerator(1.0f);

    @Before
    public void setUp() throws IOException {
        written.clear();
        engine = new TransmitEngine(sink, generator, NmeaSentenceType.all(), 5000L);
    }

    private NmeaInput sample(long utcMillis) {
        NmeaInput in = new NmeaInput();
        in.valid = true;
        in.utcMillis = utcMillis;
        in.latitude = 31.230416;
        in.longitude = 121.473701;
        in.altitudeM = 12.5;
        in.speedMps = 5.14444;
        in.bearingDeg = 87.0;
        in.accuracyM = 5.0f;
        in.satellitesUsed = 1;
        in.addSat(new NmeaInput.Sat(3, 45, 120, 42, true));
        return in;
    }

    @Test
    public void tickWithoutInputWritesNothing() throws IOException {
        assertTrue(engine.tick(1000L).isEmpty());
        assertTrue(written.isEmpty());
        assertFalse(engine.hasPending());
    }

    @Test
    public void tickWritesFrameOnce() throws IOException {
        engine.publish(sample(1000L), 1000L);
        List<String> sentences = engine.tick(2000L);
        // $GPSSHARE + GGA + RMC + GSA + GSV(1 页) + VTG = 6 条
        assertEquals(6, sentences.size());
        assertEquals(1, written.size());
        assertEquals(6, engine.sentences());
        assertEquals(1, engine.frames());
        assertTrue(engine.bytes() > 0);
        assertTrue(engine.hasPending());
    }

    @Test
    public void tickRefreshesUtcTimeFromClock() throws IOException {
        // 输入时间戳是 1970-01-01，帧内时间必须取自 tick 的当前时钟
        engine.publish(sample(1000L), 1700000000000L);
        List<String> sentences = engine.tick(1700000001000L);
        // $GPSSHARE 恒在帧首，GGA 紧随其后
        assertTrue(sentences.get(1).startsWith("$GPGGA,221321.00,"));
    }

    @Test
    public void repeatedTicksWriteEveryCycle() throws IOException {
        engine.publish(sample(1000L), 1000L);
        for (int i = 1; i <= 3; i++) {
            engine.tick(1000L + i * 1000L);
        }
        assertEquals(3, written.size());
        assertEquals(18, engine.sentences());
    }

    @Test
    public void staleInputSuppressesOutput() throws IOException {
        engine.publish(sample(1000L), 1000L);
        assertTrue(engine.tick(2000L).size() > 0);
        written.clear();
        assertTrue(engine.tick(7000L).isEmpty());
        assertTrue(written.isEmpty());
        assertEquals(6, engine.sentences());
    }

    @Test
    public void onlyGgaWhenSentenceDisabled() throws IOException {
        engine = new TransmitEngine(sink, generator, Collections.singleton(NmeaSentenceType.GGA), 5000L);
        engine.publish(sample(1000L), 1000L);
        // 开关只管标准语句；$GPSSHARE 恒在帧首，故为 2 条
        assertEquals(2, engine.tick(2000L).size());
        assertEquals(2, engine.sentences());
    }

    @Test
    public void countersResetAndRateComputed() throws IOException {
        engine.publish(sample(1000L), 1000L);
        engine.tick(2000L);
        // 1 帧 / 10 秒 ⇒ 0.1 帧每秒
        assertEquals(0.1, engine.rateHz(12000L), 0.01);
        engine.resetCounters();
        assertEquals(0, engine.sentences());
        assertEquals(0, engine.frames());
        assertEquals(0, engine.bytes());
        assertEquals(0.0, engine.rateHz(12000L), 0.01);
    }

    @Test
    public void sinkReceivesAsciiBytesEndingWithCrLf() throws IOException {
        engine.publish(sample(1000L), 1000L);
        engine.tick(2000L);
        String text = new String(written.get(0), StandardCharsets.US_ASCII);
        assertTrue(text.endsWith("\r\n"));
        assertTrue(text.contains("$GPGGA,"));
    }
}
