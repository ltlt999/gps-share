package com.gpsbt.transfer.state;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.gpsbt.transfer.state.LogBus.Entry;
import com.gpsbt.transfer.state.LogBus.Level;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class LogBusTest {

    private LogBus bus;

    @Before
    public void setUp() {
        bus = LogBus.get();
        bus.clear();
    }

    @After
    public void tearDown() {
        bus.clear();
    }

    @Test
    public void listenerReceivesNewEntries() {
        final AtomicInteger calls = new AtomicInteger();
        LogBus.Listener listener = new LogBus.Listener() {
            @Override
            public void onLog(Entry entry) {
                calls.incrementAndGet();
            }
        };
        bus.addListener(listener);
        bus.log(Level.INFO, "测试");
        assertEquals(1, calls.get());
        bus.removeListener(listener);
        bus.log(Level.INFO, "第二次");
        assertEquals(1, calls.get());
    }

    @Test
    public void entriesKeepMostRecentLastAndTimestamped() {
        bus.log(Level.INFO, "第一条");
        bus.log(Level.ERROR, "第二条");
        List<Entry> entries = bus.entries();
        assertEquals(2, entries.size());
        assertEquals("第一条", entries.get(0).message);
        assertEquals(Level.ERROR, entries.get(1).level);
        assertTrue(entries.get(0).time > 0);
    }

    @Test
    public void bufferIsCapped() {
        for (int i = 0; i < LogBus.MAX_ENTRIES + 50; i++) {
            bus.log(Level.INFO, "第" + i + "条");
        }
        List<Entry> entries = bus.entries();
        assertEquals(LogBus.MAX_ENTRIES, entries.size());
        assertEquals("第50条", entries.get(0).message);
    }
}
