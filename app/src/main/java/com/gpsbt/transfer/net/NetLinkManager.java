package com.gpsbt.transfer.net;

import android.os.Handler;
import android.os.Looper;

import com.gpsbt.transfer.link.Link;
import com.gpsbt.transfer.link.Peer;
import com.gpsbt.transfer.state.LogBus;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;

/**
 * WiFi / TCP 服务端通道：实现 {@link Link}，多对端。
 *
 * 与蓝牙实现的差异（均为有意为之）：
 * - {@code write()} **永不抛 IOException**——单个接收端写失败由注册表静默剔除，
 *   发送循环不应因此停掉服务；
 * - {@code stop()} 幂等，重复调用不再触发回调；
 * - 所有 Listener 回调统一经 Handler 投递到主线程。
 */
public final class NetLinkManager implements Link {

    private static final int READ_BUFFER = 256;
    /**
     * 接收端硬关机（拔电 / 崩溃）时 TCP 不会发 FIN 或 RST，服务器读线程会一直
     * 阻塞、内核要 15 分钟才放弃重传——对端就成了「在线列表里下不掉的幽灵」。
     * 接收端每 5 秒回一次心跳，这里据此把超时没心跳的连接扫掉。
     */
    private static final long PEER_IDLE_TIMEOUT_MILLIS = 20000L;
    private static final long SWEEP_INTERVAL_MILLIS = 5000L;

    private final int port;
    private final Link.Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final PeerRegistry registry = new PeerRegistry();

    private volatile boolean stopping;
    private volatile boolean started;
    /** accept 线程写、stop() 读，跨线程故 volatile。 */
    private volatile ServerSocket server;
    private Thread acceptThread;
    private Thread sweepThread;

    public NetLinkManager(int port, Link.Listener listener) {
        this.port = port;
        this.listener = listener;
    }

    @Override
    public void start() {
        if (started) {
            return;
        }
        started = true;
        stopping = false;
        acceptThread = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop();
            }
        }, "net-accept");
        acceptThread.start();
        sweepThread = new Thread(new Runnable() {
            @Override
            public void run() {
                sweepLoop();
            }
        }, "net-sweep");
        // 守护线程：stop() 后最多睡一个扫描周期就退出，不必等它自然结束
        sweepThread.setDaemon(true);
        sweepThread.start();
    }

    /**
     * 扫掉「已经死了但 TCP 没告诉我们」的接收端。
     *
     * 只对发过心跳的对端生效（lastReceivedAt > 0）。从不发数据的对端——例如 PC
     * 上只读的串口工具——保持原有行为：靠 FIN/RST 或写失败来判定断开，不会被误踢。
     */
    private void sweepLoop() {
        while (!stopping) {
            try {
                Thread.sleep(SWEEP_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                return;
            }
            long now = System.currentTimeMillis();
            for (PeerRegistry.Client client : registry.snapshot()) {
                long last = client.lastReceivedAt();
                if (last <= 0 || now - last <= PEER_IDLE_TIMEOUT_MILLIS) {
                    continue;
                }
                registry.remove(client);
                client.close();
                LogBus.get().log(LogBus.Level.WARN,
                        "WiFi 接收端心跳超时，已断开：" + client.address());
                postPeerLeft(client.address());
            }
        }
    }

    /** 幂等：重复调用不做事，也不再触发回调。 */
    @Override
    public void stop() {
        if (!started) {
            return;
        }
        started = false;
        stopping = true;
        closeServer();
        registry.closeAll();
        postState(Link.State.IDLE, "WiFi 服务已关闭");
    }

    /**
     * 广播给所有接收端。**永不抛异常**——单个客户端失败由注册表处理。
     * 接口声明了 throws IOException，但本实现不需要；保留声明是为了满足接口契约。
     */
    @Override
    public void write(byte[] data) {
        registry.broadcast(data);
    }

    @Override
    public boolean isConnected() {
        return !registry.isEmpty();
    }

    /** 断开所有接收端，继续监听等待重连。每个被断开的对端都会回调 onPeerLeft。 */
    @Override
    public void resetConnection() {
        List<PeerRegistry.Client> dropped = registry.snapshot();
        registry.closeAll();
        // 必须逐个回调：否则状态层的对端列表会留下永远不会消失的幽灵条目
        for (PeerRegistry.Client client : dropped) {
            postPeerLeft(client.address());
        }
    }

    /** 当前监听端口。 */
    public int port() {
        return port;
    }

    /**
     * 投递一次 onPeerLeft。停止过程中不再回调——`stop()` 已经清了状态，
     * 此时再报「接收端断开」会污染停止后的界面。
     */
    private void postPeerLeft(final String address) {
        final Peer peer = new Peer(address, address, 0L);
        main.post(new Runnable() {
            @Override
            public void run() {
                if (stopping) {
                    return;
                }
                listener.onPeerLeft(peer, "接收端已断开");
            }
        });
    }

    // ---------- accept ----------

    private void acceptLoop() {
        try {
            ServerSocket socket = new ServerSocket();
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(port));
            server = socket;
        } catch (IOException e) {
            postState(Link.State.FAILED, "WiFi 服务启动失败：" + e.getMessage());
            return;
        }
        postState(Link.State.WAITING, "WiFi 服务已开启，等待接收端连接（端口 " + port + "）");
        while (!stopping) {
            Socket accepted;
            try {
                accepted = server.accept();
            } catch (IOException e) {
                if (!stopping) {
                    postState(Link.State.FAILED, "WiFi 监听中断：" + e.getMessage());
                }
                break;
            }
            if (stopping || accepted == null) {
                closeQuietly(accepted);
                break;
            }
            attach(accepted);
        }
        closeServer();
    }

    private void attach(Socket socket) {
        if (stopping) {
            closeQuietly(socket);
            return;
        }
        String address = socket.getInetAddress() == null
                ? "未知地址" : socket.getInetAddress().getHostAddress();
        String label = address + ":" + socket.getPort();
        TcpClient client = new TcpClient(socket, label);
        if (!client.openStreams()) {
            return;
        }
        // 取流期间可能已经 stop()，再确认一次
        if (stopping) {
            closeQuietly(socket);
            return;
        }
        registry.add(client);
        final Peer peer = new Peer(label, label, System.currentTimeMillis());
        main.post(new Runnable() {
            @Override
            public void run() {
                if (stopping) {
                    return;
                }
                postStateInternal(Link.State.CONNECTED, "接收端已连接：" + peer.address);
                listener.onPeerJoined(peer);
            }
        });
        LogBus.get().log(LogBus.Level.INFO, "WiFi 接收端接入：" + label);
        // 读线程最后启动：保证 onPeerJoined 先入主线程队列，
        // 否则对端秒断时会出现「先报断开、后报接入」的颠倒回调
        client.startReader();
    }

    private void closeServer() {
        ServerSocket socket = server;
        server = null;
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // 忽略关闭异常
            }
        }
    }

    private static void closeQuietly(Socket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
            // 忽略关闭异常
        }
    }

    // ---------- 回调 ----------

    private void postState(final Link.State state, final String message) {
        main.post(new Runnable() {
            @Override
            public void run() {
                postStateInternal(state, message);
            }
        });
    }

    private void postStateInternal(Link.State state, String message) {
        listener.onState(state, message);
    }

    /** 一个 TCP 接收端。读线程检测到 EOF / 异常即判定对端断开。 */
    private final class TcpClient implements PeerRegistry.Client {

        private final Socket socket;
        private final String address;
        private OutputStream out;
        private InputStream in;
        private Thread reader;
        private volatile boolean alive = true;
        /** 最后一次收到该客户端任何字节的时刻；0 表示从未收到过（不做超时判断）。 */
        private volatile long lastReceivedAt;

        TcpClient(Socket socket, String address) {
            this.socket = socket;
            this.address = address;
        }

        @Override
        public String address() {
            return address;
        }

        @Override
        public long lastReceivedAt() {
            return lastReceivedAt;
        }

        /** 只取流，不起读线程——由 attach() 决定何时启动，以固定回调顺序。 */
        boolean openStreams() {
            try {
                out = socket.getOutputStream();
                in = socket.getInputStream();
            } catch (IOException e) {
                LogBus.get().log(LogBus.Level.WARN, "WiFi 取流失败：" + e.getMessage());
                close();
                return false;
            }
            return true;
        }

        void startReader() {
            reader = new Thread(new Runnable() {
                @Override
                public void run() {
                    readLoop();
                }
            }, "net-read-" + address);
            reader.start();
        }

        @Override
        public void write(byte[] data) throws IOException {
            OutputStream stream = out;
            if (stream == null || !alive) {
                throw new IOException("接收端已断开");
            }
            stream.write(data);
            stream.flush();
        }

        @Override
        public void close() {
            if (!alive) {
                return;
            }
            alive = false;
            closeQuietly(socket);
        }

        private void readLoop() {
            byte[] buffer = new byte[READ_BUFFER];
            while (alive && !stopping) {
                int length;
                try {
                    length = in.read(buffer);
                } catch (IOException e) {
                    break;
                }
                if (length < 0) {
                    break;
                }
                // 内容本身忽略，但「收到了」这件事要记下来：接收端靠它回心跳，
                // 我们靠它判断对端是不是还活着（硬关机时 TCP 不会发 FIN）
                lastReceivedAt = System.currentTimeMillis();
            }
            if (!alive || stopping) {
                // 已经关过了（close()/closeAll），或服务正在停止——都不该再回调
                return;
            }
            alive = false;
            registry.remove(this);
            closeQuietly(socket);
            LogBus.get().log(LogBus.Level.WARN, "WiFi 接收端断开：" + address);
            postPeerLeft(address);
        }
    }
}
