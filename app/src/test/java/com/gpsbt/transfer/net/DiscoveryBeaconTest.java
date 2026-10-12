package com.gpsbt.transfer.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.nio.charset.Charset;

public class DiscoveryBeaconTest {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    @Test
    public void encodeDecodeRoundTrip() {
        byte[] data = DiscoveryBeacon.encode("车机的手机", 10110);

        DiscoveryBeacon.Info info = DiscoveryBeacon.decode(data, data.length);

        assertNotNull(info);
        assertEquals("车机的手机", info.name);
        assertEquals(10110, info.tcpPort);
    }

    @Test
    public void nameWithSeparatorIsEscaped() {
        byte[] data = DiscoveryBeacon.encode("a|b", 10110);

        DiscoveryBeacon.Info info = DiscoveryBeacon.decode(data, data.length);

        assertNotNull(info);
        assertEquals("a|b", info.name);
    }

    @Test
    public void emptyNameFallsBackToDefault() {
        byte[] data = DiscoveryBeacon.encode("", 10110);

        DiscoveryBeacon.Info info = DiscoveryBeacon.decode(data, data.length);

        assertNotNull(info);
        assertEquals(DiscoveryBeacon.DEFAULT_NAME, info.name);
    }

    @Test
    public void wrongMagicIsRejected() {
        byte[] data = "OTHER/1|phone|10110\n".getBytes(UTF8);

        assertNull(DiscoveryBeacon.decode(data, data.length));
    }

    @Test
    public void malformedPayloadIsRejected() {
        assertNull(DiscoveryBeacon.decode("GPSSHARE/1|phone\n".getBytes(UTF8), 13));
        assertNull(DiscoveryBeacon.decode("GPSSHARE/1|phone|abc\n".getBytes(UTF8), 21));
        assertNull(DiscoveryBeacon.decode("GPSSHARE/1|phone|0\n".getBytes(UTF8), 19));
        assertNull(DiscoveryBeacon.decode("GPSSHARE/1|phone|70000\n".getBytes(UTF8), 23));
    }

    @Test
    public void decodeHonoursLengthArgument() {
        byte[] data = DiscoveryBeacon.encode("phone", 10110);
        byte[] padded = new byte[data.length + 8];
        System.arraycopy(data, 0, padded, 0, data.length);

        // 只解析前 data.length 字节，尾部垃圾不应影响结果
        DiscoveryBeacon.Info info = DiscoveryBeacon.decode(padded, data.length);

        assertNotNull(info);
        assertEquals("phone", info.name);
        assertEquals(10110, info.tcpPort);
    }

    @Test
    public void garbageBytesAreRejected() {
        byte[] data = new byte[]{(byte) 0xFF, (byte) 0xFE, 0x00, 0x01};

        assertNull(DiscoveryBeacon.decode(data, data.length));
    }
}
