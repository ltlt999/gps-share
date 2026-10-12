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

    private final int port;
    private final Link.Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final PeerRegistry registry = new PeerRegistry();

    private volatile boolean stopping;
    private volatile boolean started;
    private ServerSocket server;
    private Thread acceptThread;

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

    /** 断开所有接收端，继续监听等待重连。 */
    @Override
    public void resetConnection() {
        registry.closeAll();
    }

    /** 当前监听端口。 */
    public int port() {
        return port;
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
        if (!client.open()) {
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

        TcpClient(Socket socket, String address) {
            this.socket = socket;
            this.address = address;
        }

        @Override
        public String address() {
            return address;
        }

        boolean open() {
            try {
                out = socket.getOutputStream();
                in = socket.getInputStream();
            } catch (IOException e) {
                LogBus.get().log(LogBus.Level.WARN, "WiFi 取流失败：" + e.getMessage());
                close();
                return false;
            }
            reader = new Thread(new Runnable() {
                @Override
                public void run() {
                    readLoop();
                }
            }, "net-read-" + address);
            reader.start();
            return true;
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
                // 接收端不发数据，收到即忽略
            }
            if (!alive) {
                return;
            }
            alive = false;
            registry.remove(this);
            closeQuietly(socket);
            LogBus.get().log(LogBus.Level.WARN, "WiFi 接收端断开：" + address);
            final Peer peer = new Peer(address, address, 0L);
            main.post(new Runnable() {
                @Override
                public void run() {
                    listener.onPeerLeft(peer, "接收端已断开");
                }
            });
        }
    }
}
