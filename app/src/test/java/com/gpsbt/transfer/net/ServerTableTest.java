package com.gpsbt.transfer.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.List;

public class ServerTableTest {

    private ServerTable table;

    @Before
    public void setUp() {
        table = new ServerTable(3000L);
    }

    @Test
    public void beaconUpsertsByHostAndPort() {
        table.upsert("192.168.43.1", 10110, "车机的手机", 1000L);
        table.upsert("192.168.43.1", 10110, "车机的手机", 2000L);

        assertEquals(1, table.size());
        assertEquals(2000L, table.snapshot(2000L).get(0).lastSeenAt);
    }

    @Test
    public void differentPortsAreDifferentServers() {
        table.upsert("192.168.43.1", 10110, "A", 1000L);
        table.upsert("192.168.43.1", 10111, "B", 1000L);

        assertEquals(2, table.size());
    }

    @Test
    public void staleEntriesAreDropped() {
        table.upsert("192.168.43.1", 10110, "A", 1000L);
        table.upsert("192.168.43.2", 10110, "B", 5000L);

        List<ServerTable.Server> alive = table.snapshot(7000L);

        // 3 秒没再收到信标就认为对方下线
        assertEquals(1, alive.size());
        assertEquals("192.168.43.2", alive.get(0).host);
    }

    @Test
    public void everythingExpiresWhenSilent() {
        table.upsert("192.168.43.1", 10110, "A", 1000L);

        assertTrue(table.snapshot(6000L).isEmpty());
    }

    @Test
    public void snapshotIsSortedByHostThenPort() {
        table.upsert("192.168.43.2", 10110, "B", 1000L);
        table.upsert("192.168.43.1", 10111, "A2", 1000L);
        table.upsert("192.168.43.1", 10110, "A1", 1000L);

        List<ServerTable.Server> list = table.snapshot(1000L);

        assertEquals("192.168.43.1", list.get(0).host);
        assertEquals(10110, list.get(0).port);
        assertEquals(10111, list.get(1).port);
        assertEquals("192.168.43.2", list.get(2).host);
    }

    @Test
    public void emptyHostOrBadPortIsIgnored() {
        table.upsert("", 10110, "A", 1000L);
        table.upsert("192.168.43.1", 0, "A", 1000L);
        table.upsert(null, 10110, "A", 1000L);

        assertEquals(0, table.size());
    }

    /**
     * upsert 在 UDP 接收线程调用、snapshot 在轮询线程调用，两者同时操作
     * 同一个 LinkedHashMap 会抛 ConcurrentModificationException。
     *
     * 并发缺陷没法用单测稳定复现，因此这里直接断言「方法带 synchronized」——
     * 结构断言不如行为断言漂亮，但它是唯一能确定性锁住这条契约的方式。
     */
    @Test
    public void mutatingMethodsAreSynchronizedForCrossThreadUse() throws Exception {
        assertTrue(java.lang.reflect.Modifier.isSynchronized(
                ServerTable.class.getMethod("upsert", String.class, int.class,
                        String.class, long.class).getModifiers()));
        assertTrue(java.lang.reflect.Modifier.isSynchronized(
                ServerTable.class.getMethod("snapshot", long.class).getModifiers()));
    }
}