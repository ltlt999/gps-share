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

    private String frameText(int index) {
        return new String(written.get(index), StandardCharsets.US_ASCII);
    }

    @Test
    public void tickWithoutInputWritesNothing() throws IOException {
        assertTrue(engine.tick(1000L, 0L).isEmpty());
        assertTrue(written.isEmpty());
        assertFalse(engine.hasPending());
    }

    @Test
    public void tickWritesFrameOnce() throws IOException {
        engine.publish(sample(1000L));
        List<String> sentences = engine.tick(2000L, 0L);
        // $GPSSHARE + GGA + RMC + GSA + GSV(1 页) + VTG = 6 条
        assertEquals(6, sentences.size());
        assertEquals(1, written.size());
        assertEquals(6, engine.sentences());
        assertEquals(1, engine.frames());
        assertTrue(engine.bytes() > 0);
        // 取走后队列就空了：同一个解算不会被重复发
        assertFalse(engine.hasPending());
    }

    @Test
    public void frameCarriesFixTimeNotSendTime() throws IOException {
        // 解算时刻是 1970-01-01 00:00:01，发送时刻是 00:00:02。
        // 帧里的时间必须是解算时刻 —— 这正是 v1.6.2 失真的地方。
        engine.publish(sample(1000L));
        List<String> sentences = engine.tick(2000L, 0L);

        // $GPSSHARE 恒在帧首，GGA 紧随其后
        assertTrue(sentences.get(1).startsWith("$GPGGA,000001.00,"));
        // 不是发送时刻（000002.00）
        assertFalse(sentences.get(1).startsWith("$GPGGA,000002.00,"));
    }

    @Test
    public void frameTimeFollowsTheFixNotTheClock() throws IOException {
        engine.publish(sample(1000L));
        engine.tick(2000L, 0L);
        written.clear();

        // 换一个解算时刻：帧里的时间跟着解算走
        engine.publish(sample(3000L));
        engine.tick(4000L, 0L);

        assertTrue(frameText(0).contains("$GPGGA,000003.00,"));
    }

    @Test
    public void frameCarriesRealAge() throws IOException {
        engine.publish(sample(1000L));
        // 墙上时钟 2000 − 解算时刻 1000 = 1000 ms
        engine.tick(2000L, 0L);

        String frame = frameText(0);
        assertTrue(frame.contains(",AGE=1000"));
    }

    @Test
    public void agePrefersElapsedRealtime() throws IOException {
        NmeaInput in = sample(1000L);
        // 解算时刻对应的 uptime = 5 秒，此刻 uptime = 5.842 秒 → 年龄 842 ms
        in.fixElapsedRealtimeNanos = 5_000_000_000L;
        engine.publish(in);
        engine.tick(999_999_999L, 5_842_000_000L);

        // 墙上时钟算出来的年龄是天文数字（999999999 − 1000），
        // 只有走 uptime 才是对的 842
        assertTrue(frameText(0).contains(",AGE=842"));
    }

    @Test
    public void repeatedTicksDoNotResendSameFix() throws IOException {
        engine.publish(sample(1000L));
        engine.tick(2000L, 0L);
        engine.tick(3000L, 0L);
        engine.tick(4000L, 0L);
        // 同一个解算只发一次 —— v1.6.2 每秒重发一次同一个位置
        assertEquals(1, written.size());
        assertEquals(6, engine.sentences());
    }

    @Test
    public void queueKeepsEveryFix() throws IOException {
        engine.publish(sample(1000L));
        engine.publish(sample(2000L));
        engine.publish(sample(3000L));

        engine.tick(4000L, 0L);
        engine.tick(4000L, 0L);
        engine.tick(4000L, 0L);

        // 三个解算一个都不能丢
        assertEquals(3, written.size());
        assertEquals(18, engine.sentences());
        assertFalse(engine.hasPending());
    }

    @Test
    public void staleInputSuppressesOutput() throws IOException {
        engine.publish(sample(1000L));
        // 墙上时钟 7000 − 解算时刻 1000 = 6000 ms，超过 5 秒的过期线
        assertTrue(engine.tick(7000L, 0L).isEmpty());
        assertTrue(written.isEmpty());
        assertEquals(0, engine.sentences());
        // 丢掉之后队列就空了，不能卡住
        assertFalse(engine.hasPending());
    }

    @Test
    public void onlyGgaWhenSentenceDisabled() throws IOException {
        engine = new TransmitEngine(sink, generator, Collections.singleton(NmeaSentenceType.GGA), 5000L);
        engine.publish(sample(1000L));
        // 开关只管标准语句；$GPSSHARE 恒在帧首，故为 2 条
        assertEquals(2, engine.tick(2000L, 0L).size());
        assertEquals(2, engine.sentences());
    }

    @Test
    public void countersResetAndRateComputed() throws IOException {
        engine.publish(sample(1000L));
        engine.tick(2000L, 0L);
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
        engine.publish(sample(1000L));
        engine.tick(2000L, 0L);
        String text = frameText(0);
        assertTrue(text.endsWith("\r\n"));
        assertTrue(text.contains("$GPGGA,"));
    }

    // ---- 保活帧 ----

    @Test
    public void keepaliveCarriesLastFixMetadata() throws IOException {
        NmeaInput in = sample(1000L);
        in.seq = 9L;
        in.source = NmeaInput.Source.GPS;
        engine.publish(in);
        engine.tick(2000L, 0L);
        written.clear();

        engine.tickKeepalive(5000L, 0L);

        String keepalive = frameText(0);
        assertTrue(keepalive.startsWith("$GPSSHARE,"));
        assertTrue(keepalive.contains("SEQ=9"));
        assertTrue(keepalive.contains("SRC=GPS"));
        // 年龄是「上一次解算距今多久」：5000 − 1000 = 4000
        assertTrue(keepalive.contains(",AGE=4000"));
        assertTrue(keepalive.contains("$GPZDA"));
    }

    @Test
    public void keepaliveAgeGrowsWithTime() throws IOException {
        engine.publish(sample(1000L));
        engine.tick(2000L, 0L);
        written.clear();

        engine.tickKeepalive(3000L, 0L);
        engine.tickKeepalive(6000L, 0L);

        // 接收端靠 AGE 判断「多久没有新定位了」，必须单调递增
        assertTrue(frameText(0).contains(",AGE=2000"));
        assertTrue(frameText(1).contains(",AGE=5000"));
    }

    @Test
    public void keepaliveWithoutAnyFixSendsOnlyZda() throws IOException {
        engine.tickKeepalive(2000L, 0L);

        String keepalive = frameText(0);
        // 从来没有过解算时不能造 $GPSSHARE 空壳：
        // AGE=0 在接收端正是「把时间戳重锚到本机此刻」的信号，
        // 会把「从来没有过定位」表达成「一个 0 毫秒前刚解算出来的定位」
        assertFalse(keepalive.contains("$GPSSHARE"));
        assertTrue(keepalive.startsWith("$GPZDA,"));
    }

    @Test
    public void keepaliveDoesNotCountAsPositionFrame() throws IOException {
        engine.publish(sample(1000L));
        engine.tick(2000L, 0L);
        long framesAfterTick = engine.frames();

        engine.tickKeepalive(3000L, 0L);

        // 保活帧不是位置帧：发送速率要反映「每秒几个新定位」
        assertEquals(framesAfterTick, engine.frames());
    }
}
