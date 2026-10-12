package com.gpsbt.transfer.state;

import android.os.Handler;
import android.os.Looper;

import com.gpsbt.transfer.bt.ConnectionMode;
import com.gpsbt.transfer.link.Peer;
import com.gpsbt.transfer.ui.view.SatelliteRadarView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 全局 UI 状态中心：后台写、UI 读，监听回调切到主线程。 */
public final class TransmitterState {

    public enum Phase { IDLE, RUNNING }

    /** 连接状态。枚举顺序与 `link.Link.State` 一致。 */
    public enum Bt { IDLE, WAITING, CONNECTING, CONNECTED, FAILED, DISCONNECTED }

    /** 连接方式。枚举顺序与 `bt.ConnectionMode` 一致。 */
    public enum Mode { SERVER, CLIENT }

    /** 传输方式。顺序与 `link.Transport` 一致。 */
    public enum Transport { BLUETOOTH, WIFI }

    public static final class Device {
        public final String name;
        public final String mac;

        public Device(String name, String mac) {
            this.name = name;
            this.mac = mac;
        }

        public String name() {
            return name;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Device)) {
                return false;
            }
            Device that = (Device) other;
            return mac != null && mac.equals(that.mac);
        }

        @Override
        public int hashCode() {
            return mac == null ? 0 : mac.hashCode();
        }
    }

    /** 定位快照（UI 展示用，字段可被后台线程更新）。 */
    public static final class Fix {
        public volatile boolean valid;
        public volatile int satellitesInView;
        public volatile int satellitesUsed;
        public volatile double latitude;
        public volatile double longitude;
        public volatile double altitudeM;
        public volatile double speedMps;
        public volatile double bearingDeg;
        public volatile float accuracyM = Float.NaN;
        public volatile long fixAt;
    }

    public interface Listener {
        void onStateChanged(TransmitterState state);
    }

    public interface CallbackExecutor {
        void execute(Runnable command);
    }

    /** 同步执行，单元测初始。 */
    public static final class DirectExecutor implements CallbackExecutor {
        @Override
        public void execute(Runnable command) {
            command.run();
        }
    }

    /** 切到主线程。 */
    public static final class MainThreadExecutor implements CallbackExecutor {
        private final Handler handler;

        public MainThreadExecutor(Handler handler) {
            this.handler = handler;
        }

        @Override
        public void execute(Runnable command) {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                command.run();
            } else {
                handler.post(command);
            }
        }
    }

    private static volatile TransmitterState instance;

    private final List<Listener> listeners = new ArrayList<>();
    private volatile CallbackExecutor executor = new DirectExecutor();

    private volatile Phase phase = Phase.IDLE;
    private volatile Bt bt = Bt.IDLE;
    private volatile Mode mode = Mode.SERVER;
    private volatile List<Peer> peers = Collections.emptyList();
    private volatile Device pendingDevice;
    private volatile String serviceName = "";
    private volatile String uuid = "";
    private volatile int rateHz = 1;
    private volatile String statusText = "";
    private volatile Fix fix;
    private volatile TransmitStats stats = new TransmitStats();
    private volatile List<SatelliteRadarView.Sat> satellites = Collections.emptyList();
    private volatile Transport transport = Transport.BLUETOOTH;

    private TransmitterState() {
    }

    public static TransmitterState get() {
        TransmitterState local = instance;
        if (local == null) {
            synchronized (TransmitterState.class) {
                local = instance;
                if (local == null) {
                    local = new TransmitterState();
                    instance = local;
                }
            }
        }
        return local;
    }

    /** 仅供单元测试注入。 */
    TransmitterState(CallbackExecutor injected) {
        this.executor = injected;
    }

    public void setExecutor(CallbackExecutor executor) {
        this.executor = executor == null ? new DirectExecutor() : executor;
    }

    public void addListener(Listener listener) {
        synchronized (listeners) {
            if (!listeners.contains(listener)) {
                listeners.add(listener);
            }
        }
    }

    public void removeListener(Listener listener) {
        synchronized (listeners) {
            listeners.remove(listener);
        }
    }

    public Phase phase() {
        return phase;
    }

    public Bt bt() {
        return bt;
    }

    public Mode mode() {
        return mode;
    }

    public List<Peer> peers() {
        return peers;
    }

    /**
     * 派生值：第一个对端。蓝牙恒为单对端，因此等价于「已连接的设备」。
     * 多对端场景请改用 {@link #peers()}。
     */
    public Device connectedDevice() {
        List<Peer> snapshot = peers;
        if (snapshot.isEmpty()) {
            return null;
        }
        Peer first = snapshot.get(0);
        return new Device(first.name, first.address);
    }

    public Transport transport() {
        return transport;
    }

    public Device pendingDevice() {
        return pendingDevice;
    }

    public String serviceName() {
        return serviceName;
    }

    public String uuid() {
        return uuid;
    }

    public int rateHz() {
        return rateHz;
    }

    public String statusText() {
        return statusText;
    }

    public Fix fix() {
        return fix;
    }

    public TransmitStats stats() {
        return stats;
    }

    public List<SatelliteRadarView.Sat> satellites() {
        return satellites;
    }

    public void setPhase(Phase value) {
        phase = value;
        notifyListeners();
    }

    public void setBt(Bt value) {
        bt = value;
        notifyListeners();
    }

    public void setMode(Mode value) {
        mode = value;
        notifyListeners();
    }

    public void setMode(ConnectionMode value) {
        setMode(value == ConnectionMode.CLIENT ? Mode.CLIENT : Mode.SERVER);
    }

    public void setPeers(List<Peer> value) {
        peers = value == null
                ? Collections.<Peer>emptyList()
                : Collections.unmodifiableList(new ArrayList<>(value));
        notifyListeners();
    }

    /** 追加一个对端；已存在（地址相同）则忽略。 */
    public void addPeer(Peer peer) {
        if (peer == null) {
            return;
        }
        List<Peer> next = new ArrayList<>(peers);
        if (!next.contains(peer)) {
            next.add(peer);
        }
        setPeers(next);
    }

    /** 移除一个对端；不存在则不做任何通知。 */
    public void removePeer(Peer peer) {
        if (peer == null) {
            return;
        }
        List<Peer> next = new ArrayList<>(peers);
        if (next.remove(peer)) {
            setPeers(next);
        }
    }

    public void setTransport(Transport value) {
        transport = value == null ? Transport.BLUETOOTH : value;
        notifyListeners();
    }

    public void setPendingDevice(Device value) {
        pendingDevice = value;
        notifyListeners();
    }

    public void setConfig(String serviceName, String uuid, int rateHz) {
        this.serviceName = serviceName == null ? "" : serviceName;
        this.uuid = uuid == null ? "" : uuid;
        this.rateHz = rateHz;
        notifyListeners();
    }

    public void setStatusText(String value) {
        statusText = value == null ? "" : value;
        notifyListeners();
    }

    public void setFix(Fix value) {
        fix = value;
        notifyListeners();
    }

    public void setStats(TransmitStats value) {
        stats = value == null ? new TransmitStats() : value;
        notifyListeners();
    }

    public void setSatellites(List<SatelliteRadarView.Sat> value) {
        satellites = value == null
                ? Collections.<SatelliteRadarView.Sat>emptyList()
                : Collections.unmodifiableList(new ArrayList<>(value));
        notifyListeners();
    }

    public void resetForStop() {
        phase = Phase.IDLE;
        bt = Bt.IDLE;
        peers = Collections.emptyList();
        transport = Transport.BLUETOOTH;
        pendingDevice = null;
        statusText = "";
        fix = null;
        stats = new TransmitStats();
        satellites = Collections.emptyList();
        notifyListeners();
    }

    private void notifyListeners() {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                List<Listener> snapshot;
                synchronized (listeners) {
                    snapshot = new ArrayList<>(listeners);
                }
                for (Listener listener : snapshot) {
                    listener.onStateChanged(TransmitterState.this);
                }
            }
        });
    }
}
