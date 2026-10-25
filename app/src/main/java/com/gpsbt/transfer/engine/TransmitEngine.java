package com.gpsbt.transfer.engine;

import com.gpsbt.transfer.nmea.NmeaGenerator;
import com.gpsbt.transfer.nmea.NmeaInput;
import com.gpsbt.transfer.nmea.NmeaSentenceType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Set;

/**
 * 把定位快照转成 NMEA 帧写入链路。
 *
 * <p>v2.0.0 起改为**事件驱动**：定位一到就入队，由调用方立刻取走，
 * 不再等定频时钟 —— 那段 0～1000 ms 的等待是纯粹的白白延迟。
 *
 * <p><b>队列而非单个「待发」引用。</b>v1.6.2 只有一个 pending 字段，新解算会把旧的
 * 覆盖掉；连续出解时中间那几次就这么丢了。先进先出保证一个都不丢。
 *
 * <p><b>帧里的时间是解算时刻，不是发送时刻。</b>解算年龄单独走 {@code $GPSSHARE}
 * 的 {@code AGE}，由接收端本地重锚 —— 年龄是时长，与两机时钟差无关。
 */
public final class TransmitEngine {

    public interface Sink {
        /** 写一帧数据；链路未连接等 IO 失败时抛 IOException，调用方据此停止。 */
        void write(byte[] data, int length) throws IOException;
    }

    private final Sink sink;
    private final NmeaGenerator generator;
    private final Set<NmeaSentenceType> enabled;
    private final long staleMillis;

    /** 待发解算。先进先出：连续多个解算时一个都不能丢。 */
    private final Deque<NmeaInput> queue = new ArrayDeque<>();
    /** 最后一次写出的解算：保活帧的元数据来源。 */
    private volatile NmeaInput lastSent;

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

    /**
     * 主线程调用（定位回调里）：把解算放进队列，不写流。
     *
     * 真正的写链路在调用方的后台执行器里（{@link #tick}）。这里只入队，
     * 是为了不给主线程引入任何 IO 阻塞。
     */
    public void publish(NmeaInput input) {
        if (input == null) {
            return;
        }
        synchronized (queue) {
            queue.addLast(input);
        }
    }

    public boolean hasPending() {
        synchronized (queue) {
            return !queue.isEmpty();
        }
    }

    /**
     * 取走队首的解算并写成一帧。
     *
     * @param nowMillis 当前墙上时钟
     * @param nowElapsedNanos 当前 uptime（纳秒），用来算解算年龄；传 0 表示拿不到
     * @return 本帧已写入的语句列表；队列为空时为空列表
     * @throws IOException 写入失败（例如链路已断开）
     */
    public List<String> tick(long nowMillis, long nowElapsedNanos) throws IOException {
        NmeaInput input;
        synchronized (queue) {
            input = queue.pollFirst();
        }
        if (input == null) {
            return Collections.emptyList();
        }
        long age = input.computeAgeMillis(nowElapsedNanos, nowMillis);
        if (age > staleMillis) {
            // 太旧的解算（某些 ROM 会投递陈旧定位）不算数：不发位置，只把它丢掉。
            // 接收端本来也会按「年龄超过 5 秒即过期」拒掉，发过去只是白占带宽。
            return Collections.emptyList();
        }
        String frame = generator.buildFrame(input, enabled, age);
        byte[] data = frame.getBytes(StandardCharsets.US_ASCII);
        if (data.length > 0) {
            sink.write(data, data.length);
        }
        if (startedAt == 0) {
            startedAt = nowMillis;
        }
        lastFrameAt = nowMillis;
        lastSent = input;
        frames++;
        sentences += countSentences(frame);
        bytes += data.length;

        List<String> result = new ArrayList<>();
        for (String line : frame.split("\r\n")) {
            if (!line.isEmpty()) {
                result.add(line + "\r\n");
            }
        }
        return result;
    }

    /**
     * 无新解算时的链路保活：写一帧 {@code $GPSSHARE}（带上一次解算的元数据）+ {@code $GPZDA}。
     *
     * <p>让链路上始终有数据流动 —— 否则发送端拿不到定位时长时间静默，
     * 接收端断开后发送端永远感知不到。接收端靠 {@code AGE} 知道「多久没有新定位了」。
     *
     * @return 已写入的帧文本；未发送时返回 null
     * @throws IOException 写入失败（链路已死）
     */
    public String tickKeepalive(long nowMillis, long nowElapsedNanos) throws IOException {
        NmeaInput last = lastSent;
        long age = last == null ? 0L : last.computeAgeMillis(nowElapsedNanos, nowMillis);
        String frame = generator.keepaliveFrame(last, age, nowMillis);
        byte[] data = frame.getBytes(StandardCharsets.US_ASCII);
        sink.write(data, data.length);
        sentences += countSentences(frame);
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
