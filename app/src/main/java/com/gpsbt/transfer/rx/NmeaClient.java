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
            try {
                Socket created = new Socket();
                created.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MILLIS);
                socket = created;
                parser.reset();
                postConnection(true, "已连接 " + host + ":" + port);
                readLoop(created);
            } catch (IOException e) {
                postConnection(false, "连接失败：" + e.getMessage());
            } finally {
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

    private void postConnection(final boolean connected, final String message) {
        if (!connected) {
            LogBus.get().log(LogBus.Level.WARN, "接收：" + message);
        } else {
            LogBus.get().log(LogBus.Level.INFO, "接收：" + message);
        }
        main.post(new Runnable() {
            @Override
            public void run() {
                callback.onConnectionChanged(connected, message);
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
