package com.gpsbt.transfer.rx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 传输层计数：线上字节数与语句条数。
 *
 * <p>这两个数是接收端「已接收数据量 / 已接收语句」的来源，与定位快照无关 ——
 * 发送端没有新定位时只发保活帧，那时一个快照都不发布，计数必须照样走。
 */
public class NmeaLineReaderTest {

    private static final String KEEPALIVE =
            "$GPSSHARE,1,SEQ=7,AGE=1200,SRC=GPS*4E\r\n"
                    + "$GPZDA,123456.00,23,09,26,00,00*5B\r\n";

    @Test
    public void countsLinesAndBytes() throws IOException {
        byte[] raw = KEEPALIVE.getBytes(StandardCharsets.US_ASCII);
        NmeaLineReader reader = new NmeaLineReader(new ByteArrayInputStream(raw));

        assertEquals("$GPSSHARE,1,SEQ=7,AGE=1200,SRC=GPS*4E", reader.readLine());
        assertEquals("$GPZDA,123456.00,23,09,26,00,00*5B", reader.readLine());
        assertNull(reader.readLine());

        assertEquals(2L, reader.lineCount());
        // 字节数是**线上真实字节数**：与发送端写出去的一一对应
        assertEquals(raw.length, reader.bytesRead());
    }

    @Test
    public void countsKeepaliveOnlyTraffic() throws IOException {
        // 关键场景：发送端没有定位时只有保活帧。
        // 这时「已接收数据量 / 已接收语句」必须照样增长 ——
        // 否则用户看到「链路连着但数字不动」，会误判成假连接。
        byte[] raw = KEEPALIVE.getBytes(StandardCharsets.US_ASCII);
        NmeaLineReader reader = new NmeaLineReader(new ByteArrayInputStream(raw));

        String line = reader.readLine();
        assertEquals("$GPSSHARE,1,SEQ=7,AGE=1200,SRC=GPS*4E", line);
        assertEquals(1L, reader.lineCount());
        assertTrue(reader.bytesRead() > 0L);
    }

    @Test
    public void countsEveryLineEvenOnesTheParserIgnores() throws IOException {
        // 校验和不过、或者暂时不认识的语句类型，传输层照样要数 ——
        // 「已接收语句」量的是链路上跑了多少，不是解析成功多少。
        String raw = "$GPVTG,87.0,T,,10.0,N,18.5,K,A*05\r\n"
                + "$GPGGA,123456.00,3113.8250,N,12128.4220,E,1,07,1.0,10.0,M,,M,,*00\r\n";
        NmeaLineReader reader = new NmeaLineReader(
                new ByteArrayInputStream(raw.getBytes(StandardCharsets.US_ASCII)));

        assertEquals("$GPVTG,87.0,T,,10.0,N,18.5,K,A*05", reader.readLine());
        assertEquals("$GPGGA,123456.00,3113.8250,N,12128.4220,E,1,07,1.0,10.0,M,,M,,*00",
                reader.readLine());
        assertNull(reader.readLine());
        assertEquals(2L, reader.lineCount());
    }

    @Test
    public void keepsTrailingPartialLine() throws IOException {
        // 流在句子中间断了：残留的半句也算一条。
        // 丢掉它的话，接收端会少算一句，两端口径就对不上。
        String raw = "$GPSSHARE,1,SEQ=7,AGE=0,SRC=GPS*4E\r\n$GPZDA,1234";
        NmeaLineReader reader = new NmeaLineReader(
                new ByteArrayInputStream(raw.getBytes(StandardCharsets.US_ASCII)));

        assertEquals("$GPSSHARE,1,SEQ=7,AGE=0,SRC=GPS*4E", reader.readLine());
        assertEquals("$GPZDA,1234", reader.readLine());
        assertNull(reader.readLine());
        assertEquals(2L, reader.lineCount());
    }

    @Test
    public void handlesLinesWithoutCarriageReturn() throws IOException {
        // 有的对端只发 \n：行尾的 \r 要去掉，内容不能带进去
        String raw = "$GPSSHARE,1,SEQ=7,AGE=0,SRC=GPS*4E\n";
        NmeaLineReader reader = new NmeaLineReader(
                new ByteArrayInputStream(raw.getBytes(StandardCharsets.US_ASCII)));

        assertEquals("$GPSSHARE,1,SEQ=7,AGE=0,SRC=GPS*4E", reader.readLine());
        assertNull(reader.readLine());
        assertEquals(1L, reader.lineCount());
        assertEquals(raw.getBytes(StandardCharsets.US_ASCII).length, reader.bytesRead());
    }

    @Test
    public void reassemblesLinesSplitAcrossReads() throws IOException {
        // 一次 read 只能拿到半句：必须攒到行尾再切出来
        String raw = "$GPSSHARE,1,SEQ=7,AGE=0,SRC=GPS*4E\r\n";
        NmeaLineReader reader = new NmeaLineReader(
                new ByteArrayInputStream(raw.getBytes(StandardCharsets.US_ASCII)) {
                    @Override
                    public int read(byte[] b, int off, int len) {
                        // 每次只给 7 个字节，模拟分片到达
                        return super.read(b, off, Math.min(len, 7));
                    }
                });

        assertEquals("$GPSSHARE,1,SEQ=7,AGE=0,SRC=GPS*4E", reader.readLine());
        assertNull(reader.readLine());
        assertEquals(1L, reader.lineCount());
        assertEquals(raw.getBytes(StandardCharsets.US_ASCII).length, reader.bytesRead());
    }

    @Test
    public void emptyStreamYieldsNothing() throws IOException {
        NmeaLineReader reader = new NmeaLineReader(new ByteArrayInputStream(new byte[0]));
        assertNull(reader.readLine());
        assertEquals(0L, reader.lineCount());
        assertEquals(0L, reader.bytesRead());
    }
}
