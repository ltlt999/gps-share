package com.gpsbt.transfer.net;

import android.os.Handler;
import android.os.Looper;

import com.gpsbt.transfer.state.LogBus;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.List;

/**
 * 监听 UDP 信标，维护「当前有哪些发送端在线」的列表。
 *
 * 列表每 500 毫秒对外回调一次（有变化时才回调），发送端 3 秒没发信标就认为下线。
 */
public final class DiscoveryListener {

    private static final int BUFFER = 512;
    private static final long TTL_MILLIS = 3000L;
    private static final long POLL_MILLIS = 500L;

    /** 列表变化的回调。 */
    public interface Callback {
        void onServers(List<ServerTable.Server> servers);
    }

    private final int port;
    private final Callback callback;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ServerTable table = new ServerTable(TTL_MILLIS);

    private volatile boolean running;
    private DatagramSocket socket;
    private Thread receiveThread;
    private Thread pollThread;
    /** 上一次回调的服务器键列表；只在内容变化时回调，避免界面每秒重绘。 */
    private String lastKeys = "";

    public DiscoveryListener(int port, Callback callback) {
        this.port = port;
        this.callback = callback;
    }

    public void start() {
        if (running) {
            return;
        }
        running = true;
        try {
            DatagramSocket created = new DatagramSocket(null);
            created.setReuseAddress(true);
            created.bind(new InetSocketAddress(port));
            socket = created;
        } catch (IOException e) {
            LogBus.get().log(LogBus.Level.WARN, "服务发现监听启动失败：" + e.getMessage());
            running = false;
            return;
        }
        receiveThread = new Thread(new Runnable() {
            @Override
            public void run() {
                receiveLoop();
            }
        }, "discovery-rx");
        receiveThread.start();
        pollThread = new Thread(new Runnable() {
            @Override
            public void run() {
                pollLoop();
            }
        }, "discovery-poll");
        pollThread.start();
        LogBus.get().log(LogBus.Level.INFO, "开始搜索发送端（端口 " + port + "）");
    }

    /** 幂等。 */
    public void stop() {
        if (!running) {
            return;
        }
        running = false;
        DatagramSocket current = socket;
        socket = null;
        if (current != null) {
            current.close();
        }
    }

    private void receiveLoop() {
        byte[] buffer = new byte[BUFFER];
        while (running) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(packet);
            } catch (IOException e) {
                if (running) {
                    LogBus.get().log(LogBus.Level.WARN, "服务发现监听中断：" + e.getMessage());
                }
                return;
            }
            DiscoveryBeacon.Info info = DiscoveryBeacon.decode(packet.getData(), packet.getLength());
            if (info == null || packet.getAddress() == null) {
                continue;
            }
            table.upsert(packet.getAddress().getHostAddress(), info.tcpPort, info.name,
                    System.currentTimeMillis());
        }
    }

    private void pollLoop() {
        while (running) {
            final List<ServerTable.Server> servers = table.snapshot(System.currentTimeMillis());
            // 比对键列表而不是数量：一台下线另一台上线时数量不变，
            // 只看数量会漏掉这次变化
            StringBuilder keys = new StringBuilder();
            for (ServerTable.Server server : servers) {
                keys.append(server.key()).append(';');
            }
            if (!keys.toString().equals(lastKeys)) {
                lastKeys = keys.toString();
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (running) {
                            callback.onServers(servers);
                        }
                    }
                });
            }
            try {
                Thread.sleep(POLL_MILLIS);
            } catch (InterruptedException e) {
                return;
            }
        }
    }
}