package com.gpsbt.transfer.rx;

import java.io.IOException;
import java.io.InputStream;

/**
 * 按行读 NMEA 字节流，同时累计<b>线上真实字节数</b>与<b>语句条数</b>。
 *
 * <p><b>为什么不用 {@code BufferedReader.readLine()}</b>：它只给字符串，拿不到
 * 「线上到底跑了多少字节」。而「已接收数据量」必须是真实字节数 —— 发送端那边
 * 数的是写出去的字节，两边对得上才说明链路没丢东西。自己切行，两样一起拿到。
 *
 * <p><b>为什么计数放在传输层而不是解析器里。</b>解析器只在<b>解析成功并发布快照</b>时
 * 才有机会上报，而发送端在没有新定位时只发保活帧（{@code $GPSSHARE} + {@code $GPZDA}），
 * 解析器一个快照都不发布 —— 计数会整个冻住，用户看到「链路连着但数据量不动」，
 * 误以为是假连接。计数必须跟着<b>字节流</b>走，与有没有定位无关。
 *
 * <p>纯逻辑，不碰 Android API，可单测。
 */
public final class NmeaLineReader {

    private final InputStream in;
    private final byte[] buffer = new byte[1024];
    /** 还没切出行尾的残留内容。 */
    private final StringBuilder pending = new StringBuilder();
    /** 从流里读到的字节总数。 */
    private long bytesRead;
    /** 已经切出来的语句条数。 */
    private long lineCount;

    public NmeaLineReader(InputStream in) {
        this.in = in;
    }

    /**
     * 读一条语句（不含行尾的 {@code \r\n}）。
     *
     * @return null 表示流已结束
     */
    public String readLine() throws IOException {
        while (true) {
            int newline = pending.indexOf("\n");
            if (newline >= 0) {
                String raw = pending.substring(0, newline);
                pending.delete(0, newline + 1);
                return count(raw);
            }
            int read = in.read(buffer);
            if (read < 0) {
                if (pending.length() == 0) {
                    return null;
                }
                // 流在句子中间断了：残留的半句也算一条（字节数早就计过了）。
                // 丢掉它的话，接收端会少算一句，两端口径就对不上了。
                String raw = pending.toString();
                pending.setLength(0);
                return count(raw);
            }
            bytesRead += read;
            for (int i = 0; i < read; i++) {
                // NMEA 是 ASCII，逐字节取低 8 位就是字符本身
                pending.append((char) (buffer[i] & 0xFF));
            }
        }
    }

    /** 去掉行尾的 {@code \r}，并记一条语句。 */
    private String count(String raw) {
        String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
        lineCount++;
        return line;
    }

    /** 从流里读到的字节总数（线上真实流量）。 */
    public long bytesRead() {
        return bytesRead;
    }

    /** 已经切出来的语句条数。 */
    public long lineCount() {
        return lineCount;
    }
}
