package com.gpsbt.transfer.rx;

import android.os.Handler;

import com.gpsbt.transfer.nmea.NmeaInput;

import java.io.IOException;
import java.io.InputStream;

/**
 * 接收端两条通道（WiFi TCP / 蓝牙 SPP）共用的「读流 → 解析 → 上报」骨架。
 *
 * <p>两条通道的差别只在「怎么连上」；连上之后的读、解析、计数、上报完全一样，
 * 所以抽到这里，行为不会两边各写一份、走偏。
 *
 * <p><b>计数在这里上报，而不是在解析器里。</b>发送端没有新定位时只发保活帧
 * （{@code $GPSSHARE} + {@code $GPZDA}），解析器一个快照都不发布 ——
 * 计数跟着快照走的话，「已接收数据量」会整个冻住，用户会误判成假连接。
 * 计数跟着<b>字节流</b>走，与有没有定位无关。
 *
 * <p><b>计数跨重连不清零。</b>断线重连时解析器的累积状态要清（那是「本次连接」的数据），
 * 但「这条链路上一共跑了多少字节、多少句」不该清 —— 发送端那边的计数可不会归零，
 * 两端口径必须一样。
 */
final class NmeaStreamPump {

    /** 上报节奏：最快 200 ms 一次。 */
    private static final long PROGRESS_INTERVAL_MILLIS = 200L;

    interface Listener {
        /** 收到一条原始语句（写日志用）。 */
        void onSentence(String line);

        /** 解析出一个快照（位置或卫星明细）。 */
        void onSnapshot(NmeaInput snapshot);

        /** 线上累计字节数与语句条数。 */
        void onProgress(long bytes, long sentences);
    }

    private final NmeaParser parser = new NmeaParser();
    private final Listener listener;
    private final Handler main;

    /** 本客户端生命周期累计的线上字节数（跨重连累加）。 */
    private volatile long totalBytes;
    /** 本客户端生命周期累计的语句条数（跨重连累加）。 */
    private volatile long totalSentences;
    /** 上次上报进度的时间（墙上时钟，毫秒）。 */
    private long lastReportedAt;

    NmeaStreamPump(Listener listener, Handler main) {
        this.listener = listener;
        this.main = main;
    }

    /** 新连接建立：解析器的累积状态清零（计数不清）。 */
    void resetParser() {
        parser.reset();
    }

    /**
     * 在后台线程里读流，直到流结束。
     *
     * @throws IOException 读失败（链路断了）
     */
    void pump(InputStream in) throws IOException {
        NmeaLineReader reader = new NmeaLineReader(in);
        // 本次连接的起点：上一段连接累计的量
        final long baseBytes = totalBytes;
        final long baseSentences = totalSentences;
        String line;
        while ((line = reader.readLine()) != null) {
            totalBytes = baseBytes + reader.bytesRead();
            totalSentences = baseSentences + reader.lineCount();
            listener.onSentence(line);
            reportProgress(false);
            final NmeaInput parsed = parser.accept(line);
            if (parsed != null) {
                final NmeaInput snapshot = parsed;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        listener.onSnapshot(snapshot);
                    }
                });
            }
        }
        // 流结束时补报一次：节流窗口里最后那几条也要算数
        totalBytes = baseBytes + reader.bytesRead();
        totalSentences = baseSentences + reader.lineCount();
        reportProgress(true);
    }

    /**
     * 上报进度。
     *
     * @param force true 表示无视节流窗口（流结束时用）
     */
    private void reportProgress(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastReportedAt < PROGRESS_INTERVAL_MILLIS) {
            return;
        }
        lastReportedAt = now;
        final long bytes = totalBytes;
        final long sentences = totalSentences;
        main.post(new Runnable() {
            @Override
            public void run() {
                listener.onProgress(bytes, sentences);
            }
        });
    }
}
