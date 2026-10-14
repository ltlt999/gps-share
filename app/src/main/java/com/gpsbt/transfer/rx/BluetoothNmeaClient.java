package com.gpsbt.transfer.rx;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.Handler;
import android.os.Looper;

import com.gpsbt.transfer.nmea.NmeaInput;
import com.gpsbt.transfer.state.LogBus;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.UUID;

/**
 * 接收端的蓝牙 SPP 通道：连上发送端的蓝牙串口服务，按行读 NMEA。
 *
 * 与 {@link NmeaClient}（TCP）的两点差别：
 * - 连接对象是**已配对的蓝牙设备**而不是 IP 地址；
 * - **不需要心跳**。蓝牙栈会在链路真正断开时立刻报错（硬件层就知道对端没了），
 *   不像 TCP 那样硬关机后要等十几分钟才放弃——这是蓝牙在稳定性上的天然优势。
 */
public final class BluetoothNmeaClient implements ReceiveLink {

    private static final Charset ASCII = Charset.forName("US-ASCII");
    private static final long RECONNECT_DELAY_MILLIS = 5000L;

    private final BluetoothAdapter adapter;
    private final String mac;
    private final String label;
    private final UUID uuid;
    private final ReceiveLink.Callback callback;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final NmeaParser parser = new NmeaParser();

    private volatile boolean running;
    private BluetoothSocket socket;

    public BluetoothNmeaClient(BluetoothAdapter adapter, String mac, String label,
                               UUID uuid, ReceiveLink.Callback callback) {
        this.adapter = adapter;
        this.mac = mac;
        this.label = label == null || label.isEmpty() ? mac : label;
        this.uuid = uuid;
        this.callback = callback;
    }

    @Override
    public String currentTarget() {
        return label;
    }

    @Override
    public void start() {
        if (running) {
            return;
        }
        running = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                connectLoop();
            }
        }, "bt-nmea-client").start();
    }

    /** 幂等。 */
    @Override
    public void stop() {
        if (!running) {
            return;
        }
        running = false;
        BluetoothSocket current = socket;
        socket = null;
        closeQuietly(current);
    }

    private void connectLoop() {
        while (running) {
            if (adapter == null) {
                postConnection(false, "本机没有蓝牙硬件");
                sleepQuietly(RECONNECT_DELAY_MILLIS);
                continue;
            }
            if (!adapter.isEnabled()) {
                postConnection(false, "蓝牙未开启，等待开启…");
                sleepQuietly(RECONNECT_DELAY_MILLIS);
                continue;
            }
            String failure = null;
            BluetoothSocket created = null;
            try {
                // 不调 cancelDiscovery()：它需要 BLUETOOTH_SCAN 权限，而本应用
                // 按设计不申请该权限（从不主动扫描）。系统扫描进行中只会让连接
                // 变慢，不会失败。
                BluetoothDevice device = adapter.getRemoteDevice(mac);
                created = device.createInsecureRfcommSocketToServiceRecord(uuid);
                created.connect();
                socket = created;
                parser.reset();
                postConnection(true, "已连接 " + label);
                readLoop(created);
            } catch (IllegalArgumentException e) {
                failure = "设备地址无效";
            } catch (SecurityException e) {
                failure = "缺少蓝牙连接权限";
            } catch (IOException e) {
                failure = e.getMessage();
            } finally {
                closeQuietly(created);
                socket = null;
            }
            if (!running) {
                return;
            }
            postConnection(false, failure == null
                    ? "连接已断开，5 秒后重连"
                    : "连不上 " + label + "（" + failure + "），5 秒后重试");
            sleepQuietly(RECONNECT_DELAY_MILLIS);
        }
    }

    private void readLoop(BluetoothSocket active) throws IOException {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(active.getInputStream(), ASCII));
        String line;
        while (running && (line = reader.readLine()) != null) {
            final NmeaInput parsed = parser.accept(line);
            if (parsed != null) {
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (running) {
                            callback.onLocation(parsed);
                        }
                    }
                });
            }
        }
    }

    private void postConnection(final boolean connected, final String message) {
        LogBus.get().log(connected ? LogBus.Level.INFO : LogBus.Level.WARN, "接收：" + message);
        main.post(new Runnable() {
            @Override
            public void run() {
                // stop() 之后不再回调：服务已经清了状态，此时再报会污染界面
                if (running) {
                    callback.onConnectionChanged(connected, message);
                }
            }
        });
    }

    /**
     * 循环里的等待。被打断也**不退出**——重连循环的生死只由 {@code running}
     * 决定，否则一次无关的 interrupt 就会让接收端永远停在这里。
     */
    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
            // 有意吞掉：循环条件自己会判断该不该继续
        }
    }

    private static void closeQuietly(BluetoothSocket target) {
        if (target == null) {
            return;
        }
        try {
            target.close();
        } catch (IOException ignored) {
            // 忽略关闭异常
        }
    }
}
