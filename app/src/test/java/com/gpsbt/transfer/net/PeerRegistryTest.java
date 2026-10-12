package com.gpsbt.transfer.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class PeerRegistryTest {

    /** 记录写入内容的假客户端；可设成写入即失败。 */
    private static final class FakeClient implements PeerRegistry.Client {
        final String address;
        final List<String> written = new ArrayList<>();
        boolean failOnWrite;
        boolean closed;

        FakeClient(String address) {
            this.address = address;
        }

        @Override
        public String address() {
            return address;
        }

        @Override
        public void write(byte[] data) throws IOException {
            if (failOnWrite) {
                throw new IOException("写失败");
            }
            written.add(new String(data));
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    @Test
    public void addRemoveAndSnapshot() {
        PeerRegistry registry = new PeerRegistry();
        FakeClient a = new FakeClient("192.168.43.2:10110");
        FakeClient b = new FakeClient("192.168.43.3:10110");

        assertTrue(registry.isEmpty());
        registry.add(a);
        registry.add(b);
        assertEquals(2, registry.size());
        assertFalse(registry.isEmpty());

        assertTrue(registry.remove(a));
        assertEquals(1, registry.size());
        assertFalse(registry.remove(a));
    }

    @Test
    public void broadcastReachesEveryClient() {
        PeerRegistry registry = new PeerRegistry();
        FakeClient a = new FakeClient("192.168.43.2:10110");
        FakeClient b = new FakeClient("192.168.43.3:10110");
        registry.add(a);
        registry.add(b);

        registry.broadcast("$GPGGA,1,2,3\r\n".getBytes());

        assertEquals(1, a.written.size());
        assertEquals(1, b.written.size());
        assertEquals("$GPGGA,1,2,3\r\n", a.written.get(0));
    }

    @Test
    public void failingClientIsDroppedWithoutAffectingOthers() {
        PeerRegistry registry = new PeerRegistry();
        FakeClient dead = new FakeClient("192.168.43.2:10110");
        FakeClient alive = new FakeClient("192.168.43.3:10110");
        dead.failOnWrite = true;
        registry.add(dead);
        registry.add(alive);

        // 关键契约：广播不抛异常，失败的对端被静默剔除
        registry.broadcast("x".getBytes());

        assertEquals(1, registry.size());
        assertTrue(dead.closed);
        assertEquals(1, alive.written.size());
    }

    @Test
    public void closeAllClosesEveryClientAndEmpties() {
        PeerRegistry registry = new PeerRegistry();
        FakeClient a = new FakeClient("192.168.43.2:10110");
        FakeClient b = new FakeClient("192.168.43.3:10110");
        registry.add(a);
        registry.add(b);

        registry.closeAll();

        assertTrue(a.closed);
        assertTrue(b.closed);
        assertTrue(registry.isEmpty());
    }

    @Test
    public void snapshotIsACopy() {
        PeerRegistry registry = new PeerRegistry();
        FakeClient a = new FakeClient("192.168.43.2:10110");
        registry.add(a);

        List<PeerRegistry.Client> snapshot = registry.snapshot();
        registry.closeAll();

        assertEquals(1, snapshot.size());
        assertSame(a, snapshot.get(0));
        assertTrue(registry.isEmpty());
    }
}
