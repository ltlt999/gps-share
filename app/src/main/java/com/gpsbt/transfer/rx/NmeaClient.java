package com.gpsbt.transfer.rx;

import android.os.Handler;
import android.os.Looper;

import com.gpsbt.transfer.nmea.NmeaInput;
import com.gpsbt.transfer.state.LogBus;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.Charset;

/**
 * 接收端 TCP 客户端：连上发送端，按行读 NMEA，解析后回调。
 *
 * 断线后每 5 秒自动重连（与发送端蓝牙客户端的重连节奏一致）。
 */
public final class NmeaClient {

    private static final Charset ASCII = Charset.forName("US-ASCII");
    private static final long RECONNECT_DELAY_MILLIS = 5000L;
    private static final int CONNECT_TIMEOUT_MILLIS = 5000;
    /**
     * 心跳：一行 NMEA 注释（`#` 开头是标准注释，任何 NMEA 解析器都会忽略）。
     *
     * 必须发：本机硬关机（拔电 / 崩溃）时不会给服务器发 FIN 或 RST，服务器读
     * 线程会一直阻塞、内核要 15 分钟才放弃重传——那台设备就会永远留在发送端的
     * 「在线接收端」列表里下不掉。心跳让服务器能在 20 秒内发现我们没了。
     */
    private static final byte[] HEARTBEAT = "#GPSSHARE-HB\n".getBytes(ASCII);
    private static final long HEARTBEAT_INTERVAL_MILLIS = 5000L;

    /** 回调一律在主线程。 */
    public interface Callback {
        /** 连上 / 断开（connected 为 false 时 message 说明原因）。 */
        void onConnectionChanged(boolean connected, String message);

        /** 解析出一条定位。 */
        void onLocation(NmeaInput location);
    }

    private final String host;
    private final int port;
    private final Callback callback;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final NmeaParser parser = new NmeaParser();

    private volatile boolean running;
    private Socket socket;

    public NmeaClient(String host, int port, Callback callback) {
        this.host = host;
        this.port = port;
        this.callback = callback;
    }

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
        }, "nmea-client").start();
    }

    /** 幂等。 */
    public void stop() {
        if (!running) {
            return;
        }
        running = false;
        closeSocket();
    }

    private void connectLoop() {
        while (running) {
            Thread heartbeat = null;
            try {
                Socket created = new Socket();
                created.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MILLIS);
                socket = created;
                parser.reset();
                postConnection(true, "已连接 " + host + ":" + port);
                heartbeat = startHeartbeat(created);
                readLoop(created);
            } catch (IOException e) {
                postConnection(false, "连接失败：" + e.getMessage());
            } finally {
                if (heartbeat != null) {
                    heartbeat.interrupt();
                }
                closeSocket();
            }
            if (!running) {
                return;
            }
            postConnection(false, "连接已断开，5 秒后重连");
            try {
                Thread.sleep(RECONNECT_DELAY_MILLIS);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void readLoop(Socket active) throws IOException {
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

    /**
     * 心跳线程：每 5 秒往服务器写一行注释。
     *
     * 读循环是阻塞的，心跳必须另起线程。写失败说明链路已断，直接退出——
     * readLoop 那边会同时感知到断开并走重连。
     */
    private Thread startHeartbeat(final Socket active) {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (running && !active.isClosed()) {
                    try {
                        active.getOutputStream().write(HEARTBEAT);
                        active.getOutputStream().flush();
                    } catch (IOException e) {
                        return;
                    }
                    try {
                        Thread.sleep(HEARTBEAT_INTERVAL_MILLIS);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        }, "nmea-heartbeat");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private void postConnection(final boolean connected, final String message) {
        if (!connected) {
            LogBus.get().log(LogBus.Level.WARN, "接收：" + message);
        } else {
            LogBus.get().log(LogBus.Level.INFO, "接收：" + message);
        }
        main.post(new Runnable() {
            @Override
            public void run() {
                // stop() 之后不再回调：服务已经清了状态，
                // 此时再报「连接已断开」会污染停止后的界面
                if (running) {
                    callback.onConnectionChanged(connected, message);
                }
            }
        });
    }

    private void closeSocket() {
        Socket current = socket;
        socket = null;
        if (current != null) {
            try {
                current.close();
            } catch (IOException ignored) {
                // 忽略关闭异常
            }
        }
    }
}
