package com.gpsbt.transfer.rx;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.Handler;
import android.os.Looper;

import com.gpsbt.transfer.nmea.NmeaInput;
import com.gpsbt.transfer.state.LogBus;
import com.gpsbt.transfer.state.NmeaLog;

import java.io.IOException;
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
    /** 读流骨架：解析、计数、上报都在里面，两条通道共用同一套行为。 */
    private final NmeaStreamPump pump;

    private volatile boolean running;
    private BluetoothSocket socket;

    public BluetoothNmeaClient(BluetoothAdapter adapter, String mac, String label,
                               UUID uuid, ReceiveLink.Callback callback) {
        this.adapter = adapter;
        this.mac = mac;
        this.label = label == null || label.isEmpty() ? mac : label;
        this.uuid = uuid;
        this.callback = callback;
        this.pump = new NmeaStreamPump(new NmeaStreamPump.Listener() {
            @Override
            public void onSentence(String line) {
                // 收到的每一条都记日志：与发送端对称，日志页的「卫星 / 数据」
                // 筛选在接收角色下也有内容
                NmeaLog.sentence(line);
            }

            @Override
            public void onSnapshot(NmeaInput snapshot) {
                // stop() 之后不再回调：服务已经清了状态
                if (running) {
                    callback.onLocation(snapshot);
                }
            }

            @Override
            public void onProgress(long bytes, long sentences) {
                if (running) {
                    callback.onProgress(bytes, sentences);
                }
            }
        }, main);
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
            String crash = null;
            BluetoothSocket created = null;
            try {
                // 不调 cancelDiscovery()：它需要 BLUETOOTH_SCAN 权限，而本应用
                // 按设计不申请该权限（从不主动扫描）。系统扫描进行中只会让连接
                // 变慢，不会失败。
                BluetoothDevice device = adapter.getRemoteDevice(mac);
                created = device.createInsecureRfcommSocketToServiceRecord(uuid);
                created.connect();
                socket = created;
                pump.resetParser();
                postConnection(true, "已连接 " + label);
                readLoop(created);
            } catch (IllegalArgumentException e) {
                failure = "设备地址无效";
            } catch (SecurityException e) {
                failure = "缺少蓝牙连接权限";
            } catch (IOException e) {
                failure = e.getMessage();
            } catch (RuntimeException e) {
                // 读流路径里任何没预料到的异常都不能把接收端带走：
                // 它会一路逃出这个线程，重连循环再也不会跑 ——
                // 界面还显示着「连接中」，实际早就没人收数据了，
                // 而且没有任何报错，用户只能干瞪眼。
                // 按「连接断开」处理：原地重连（不是设备地址的问题）。
                LogBus.get().log(LogBus.Level.ERROR, "接收异常：" + e);
                crash = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            } finally {
                closeQuietly(created);
                socket = null;
            }
            if (!running) {
                return;
            }
            postConnection(false, failure != null
                    ? "连不上 " + label + "（" + failure + "），5 秒后重试"
                    : (crash == null
                            ? "连接已断开，5 秒后重连"
                            : "接收异常（" + crash + "），5 秒后重连"));
            sleepQuietly(RECONNECT_DELAY_MILLIS);
        }
    }

    private void readLoop(BluetoothSocket active) throws IOException {
        // 读、解析、计数都在骨架里：TCP 与蓝牙两条通道的行为必须一致
        pump.pump(active.getInputStream());
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
