package com.gpsbt.transfer.engine;

import com.gpsbt.transfer.nmea.NmeaGenerator;
import com.gpsbt.transfer.nmea.NmeaInput;
import com.gpsbt.transfer.nmea.NmeaSentenceType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/** 定频把最新定位转成 NMEA 帧写入蓝牙流。 */
public final class TransmitEngine {

    public interface Sink {
        /** 写一帧数据；蓝牙未连接等 IO 失败时抛 IOException，调用方据此停止。 */
        void write(byte[] data, int length) throws IOException;
    }

    private final Sink sink;
    private final NmeaGenerator generator;
    private final Set<NmeaSentenceType> enabled;
    private final long staleMillis;

    private volatile NmeaInput pending;
    private volatile long pendingAt;
    private volatile long sentences;
    private volatile long frames;
    private volatile long bytes;
    private volatile long startedAt;
    private volatile long lastFrameAt;

    public TransmitEngine(Sink sink, NmeaGenerator generator,
                          Set<NmeaSentenceType> enabled, long staleMillis) {
        this.sink = sink;
        this.generator = generator;
        this.enabled = enabled;
        this.staleMillis = staleMillis;
    }

    /** 后台线程调用，只保存引用，不写流。 */
    public void publish(NmeaInput input, long nowMillis) {
        pending = input;
        pendingAt = nowMillis;
    }

    public boolean hasPending() {
        return pending != null;
    }

    /**
     * @return 本周期已写入的语句列表；无定位或定位过期时为空列表
     * @throws IOException 写入失败（例如蓝牙已断开）
     */
    public List<String> tick(long nowMillis) throws IOException {
        NmeaInput input = pending;
        if (input == null || nowMillis - pendingAt > staleMillis) {
            return Collections.emptyList();
        }
        NmeaInput frameInput = input;
        if (input.utcMillis != nowMillis) {
            frameInput = input.copy();
            frameInput.utcMillis = nowMillis;
        }
        String frame = generator.buildFrame(frameInput, enabled, 0L);
        byte[] data = frame.getBytes(StandardCharsets.US_ASCII);
        if (data.length > 0) {
            sink.write(data, data.length);
        }
        if (startedAt == 0) {
            startedAt = nowMillis;
        }
        lastFrameAt = nowMillis;
        frames++;
        sentences += countSentences(frame);
        bytes += data.length;

        List<String> result = new ArrayList<String>();
        for (String line : frame.split("\r\n")) {
            if (!line.isEmpty()) {
                result.add(line + "\r\n");
            }
        }
        return result;
    }

    /**
     * 无定位可发时的链路保活：写一帧 ZDA 时间帧。
     * 让蓝牙链路上始终有数据流动——否则发送端拿不到定位时长时间静默，
     * 接收端断开后发送端永远感知不到（写入只在有数据时发生）。
     *
     * @return 已写入的语句文本；未发送时返回 null
     * @throws IOException 蓝牙写入失败（链路已死）
     */
    public String tickKeepalive(long nowMillis) throws IOException {
        String frame = generator.zda(nowMillis);
        byte[] data = frame.getBytes(StandardCharsets.US_ASCII);
        sink.write(data, data.length);
        sentences++;
        bytes += data.length;
        lastFrameAt = nowMillis;
        return frame.trim();
    }

    public long sentences() {
        return sentences;
    }

    public long frames() {
        return frames;
    }

    public long bytes() {
        return bytes;
    }

    /** 累计平均发送速率（帧/秒，1 帧 = 一个发送周期内的全部语句）。 */
    public double rateHz(long nowMillis) {
        if (startedAt == 0 || nowMillis <= startedAt) {
            return 0d;
        }
        return frames * 1000.0 / (nowMillis - startedAt);
    }

    public void resetCounters() {
        sentences = 0;
        frames = 0;
        bytes = 0;
        startedAt = 0;
        lastFrameAt = 0;
    }

    private static int countSentences(String frame) {
        int count = 0;
        for (String line : frame.split("\r\n")) {
            if (!line.isEmpty()) {
                count++;
            }
        }
        return count;
    }
}
