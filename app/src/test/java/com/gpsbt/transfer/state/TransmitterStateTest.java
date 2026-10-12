package com.gpsbt.transfer.state;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import com.gpsbt.transfer.state.TransmitterState.Bt;
import com.gpsbt.transfer.state.TransmitterState.Device;
import com.gpsbt.transfer.state.TransmitterState.Fix;
import com.gpsbt.transfer.state.TransmitterState.Mode;
import com.gpsbt.transfer.state.TransmitterState.Phase;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class TransmitterStateTest {

    private TransmitterState state;

    @org.junit.Before
    public void setUp() {
        state = new TransmitterState(new TransmitterState.DirectExecutor());
    }

    @Test
    public void listenerIsNotifiedForEveryMutation() {
        final AtomicInteger calls = new AtomicInteger();
        final List<TransmitterState> seen = new ArrayList<>();
        TransmitterState.Listener listener = new TransmitterState.Listener() {
            @Override
            public void onStateChanged(TransmitterState changed) {
                calls.incrementAndGet();
                seen.add(changed);
            }
        };
        state.addListener(listener);
        state.setPhase(Phase.RUNNING);
        state.setBt(Bt.WAITING);
        state.setMode(Mode.SERVER);
        state.setConfig("GPS共享", "00001101-0000-1000-8000-00805F9B34FB", 2);
        state.setStatusText("等待连接");

        assertEquals(5, calls.get());
        assertEquals(Phase.RUNNING, state.phase());
        assertEquals(Bt.WAITING, state.bt());
        assertEquals(Mode.SERVER, state.mode());
        assertEquals(2, state.rateHz());
        assertEquals("GPS共享", state.serviceName());
        assertEquals("等待连接", state.statusText());
        assertSame(state, seen.get(0));
    }

    @Test
    public void removedListenerStopsReceiving() {
        final AtomicInteger calls = new AtomicInteger();
        TransmitterState.Listener listener = new TransmitterState.Listener() {
            @Override
            public void onStateChanged(TransmitterState changed) {
                calls.incrementAndGet();
            }
        };
        state.addListener(listener);
        state.removeListener(listener);
        state.setPhase(Phase.RUNNING);
        assertEquals(0, calls.get());
    }

    @Test
    public void devicesAndFixAreExposed() {
        Device connected = new Device("车机", "AA:BB:CC:DD:EE:FF");
        state.setConnectedDevice(connected);
        assertEquals("车机", state.connectedDevice().name);

        Fix fix = new Fix();
        fix.valid = true;
        fix.latitude = 31.1;
        state.setFix(fix);
        assertSame(fix, state.fix());

        assertNull(state.pendingDevice());
    }

    @Test
    public void defaultStateIsIdle() {
        assertEquals(Phase.IDLE, state.phase());
        assertEquals(Bt.IDLE, state.bt());
        assertEquals(Mode.SERVER, state.mode());
        assertEquals(1, state.rateHz());
        assertNull(state.connectedDevice());
        assertNull(state.fix());
    }
}
