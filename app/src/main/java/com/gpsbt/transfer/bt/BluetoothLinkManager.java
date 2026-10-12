package com.gpsbt.transfer.bt;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.core.content.ContextCompat;

import com.gpsbt.transfer.R;
import com.gpsbt.transfer.link.Link;
import com.gpsbt.transfer.link.Peer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 蓝牙 SPP（RFCOMM）通道：实现 {@link Link}，单对端。服务端/客户端模式在构造时确定。 */
public final class BluetoothLinkManager implements Link {

    private static final UUID FALLBACK_UUID =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private final BluetoothAdapter adapter;
    private final String serviceName;
    private final UUID uuid;
    private final ConnectionMode mode;
    private final BluetoothDevice target;
    private final Link.Listener listener;
    /** 当前对端。蓝牙通道恒为单值；attach()/stop() 写，ReadThread 读，故需 volatile。 */
    private volatile Peer currentPeer;
    private final Handler main = new Handler(Looper.getMainLooper());

    private volatile boolean stopping;
    private AcceptThread acceptThread;
    private ConnectThread connectThread;
    private ReadThread readThread;
    private BluetoothSocket socket;
    private OutputStream out;

    public BluetoothLinkManager(Context context, BluetoothAdapter adapter,
                                String serviceName, UUID uuid,
                                ConnectionMode mode, BluetoothDevice target,
                                Link.Listener listener) {
        String fallback = context.getString(R.string.app_name);
        this.serviceName = serviceName == null || serviceName.trim().isEmpty()
                ? fallback : serviceName;
        this.adapter = adapter;
        this.uuid = uuid == null ? FALLBACK_UUID : uuid;
        this.mode = mode == null ? ConnectionMode.SERVER : mode;
        this.target = target;
        this.listener = listener;
    }

    /** 读取已配对设备列表所需的权限是否已授予。 */
    public static boolean hasBondedListPermission(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH)
                == PackageManager.PERMISSION_GRANTED;
    }

    public static boolean isBluetoothAvailable() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        return adapter != null && adapter.isEnabled();
    }

    public boolean isEnabled() {
        return adapter != null && adapter.isEnabled();
    }

    /** 已配对设备，按名称排序。缺权限时返回空列表。 */
    public static List<BluetoothDevice> bondedDevices(Context context) {
        // lint 需要看到直接的权限检查，因此内联判断而不是调用辅助方法
        String required = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? Manifest.permission.BLUETOOTH_CONNECT
                : Manifest.permission.BLUETOOTH;
        if (ContextCompat.checkSelfPermission(context, required) != PackageManager.PERMISSION_GRANTED) {
            return Collections.emptyList();
        }
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            return Collections.emptyList();
        }
        Set<BluetoothDevice> bonded = adapter.getBondedDevices();
        List<BluetoothDevice> result = new ArrayList<BluetoothDevice>(bonded);
        Collections.sort(result, new Comparator<BluetoothDevice>() {
            @Override
            public int compare(BluetoothDevice left, BluetoothDevice right) {
                return displayName(left).compareToIgnoreCase(displayName(right));
            }
        });
        return result;
    }

    public static String displayName(BluetoothDevice device) {
        try {
            String name = device.getName();
            return name == null || name.isEmpty() ? device.getAddress() : name;
        } catch (SecurityException e) {
            return device.getAddress();
        }
    }

    @Override
    public boolean isConnected() {
        return socket != null && socket.isConnected() && out != null;
    }

    @Override
    public void start() {
        if (mode == ConnectionMode.CLIENT) {
            startClient(target);
        } else {
            startServer();
        }
    }

    // ---------- 服务器模式 ----------

    private void startServer() {
        if (adapter == null) {
            postState(Link.State.FAILED, "设备不支持蓝牙");
            return;
        }
        if (!adapter.isEnabled()) {
            postState(Link.State.FAILED, "蓝牙未开启，请在系统设置中开启");
            return;
        }
        stopping = false;
        acceptThread = new AcceptThread();
        acceptThread.start();
    }

    // ---------- 客户端模式 ----------

    private void startClient(BluetoothDevice device) {
        if (adapter == null || device == null) {
            postState(Link.State.FAILED, "设备不支持蓝牙或目标设备无效");
            return;
        }
        if (!adapter.isEnabled()) {
            postState(Link.State.FAILED, "蓝牙未开启，请在系统设置中开启");
            return;
        }
        stopping = false;
        postState(Link.State.CONNECTING, "正在连接 " + displayName(device));
        connectThread = new ConnectThread(device);
        connectThread.start();
    }

    // ---------- 发送 ----------

    /** 未连接时抛 IOException；调用方负责据此停止服务。 */
    @Override
    public synchronized void write(byte[] data) throws IOException {
        OutputStream stream = out;
        BluetoothSocket current = socket;
        if (stream == null || current == null || !current.isConnected()) {
            throw new IOException("蓝牙未连接");
        }
        try {
            stream.write(data);
            stream.flush();
        } catch (IOException e) {
            // 写失败 = 连接已死：立即清理当前连接，accept 循环继续等待下一次连接
            brokenConnection(current);
            throw e;
        }
    }

    /** 仅当失败的仍是当前连接时才清理，避免误伤已重新建立的新连接。 */
    private synchronized void brokenConnection(BluetoothSocket failed) {
        if (socket != failed) {
            return;
        }
        if (out != null) {
            try {
                out.close();
            } catch (IOException ignored) {
                // 忽略
            }
            out = null;
        }
        closeQuietlySocket(failed);
        socket = null;
    }

    // ---------- 停止 ----------

    @Override
    public synchronized void stop() {
        stopping = true;
        closeSocket();
        currentPeer = null;
        if (acceptThread != null) {
            acceptThread.cancel();
            acceptThread = null;
        }
        if (connectThread != null) {
            connectThread.cancel();
            connectThread = null;
        }
        postState(Link.State.IDLE, "蓝牙链路已关闭");
    }

    private synchronized void closeSocket() {
        if (readThread != null) {
            readThread.shutdown();
            readThread = null;
        }
        if (out != null) {
            try {
                out.close();
            } catch (IOException ignored) {
                // 忽略关闭异常
            }
            out = null;
        }
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // 忽略关闭异常
            }
            socket = null;
        }
    }

    /** 关闭当前连接（保留 server socket，accept 循环继续等待下一次连接）。 */
    @Override
    public synchronized void resetConnection() {
        closeSocket();
    }

    private synchronized void attach(BluetoothSocket accepted) {
        if (stopping) {
            closeQuietlySocket(accepted);
            return;
        }
        // 同一 server socket 上接受新连接时，先断开旧连接
        closeSocket();
        socket = accepted;
        InputStream in;
        try {
            out = accepted.getOutputStream();
            in = accepted.getInputStream();
        } catch (IOException e) {
            closeSocket();
            postState(Link.State.FAILED, "获取流失败：" + e.getMessage());
            return;
        }
        // 读取线程：发送端只发数据，读到 EOF/异常即代表接收端断开——
        // 没有它就只能靠"写失败"发现断链，半死连接会被静默写下去而毫无察觉
        readThread = new ReadThread(in);
        readThread.start();
        BluetoothDevice device = accepted.getRemoteDevice();
        String name = device == null ? "未知设备" : displayName(device);
        String mac = device == null ? "" : device.getAddress();
        Peer peer = new Peer(name, mac, System.currentTimeMillis());
        currentPeer = peer;
        postState(Link.State.CONNECTED, "已连接 " + name);
        listener.onPeerJoined(peer);
    }

    /** 只读线程：接收端正常不发数据，读到任何字节都忽略；EOF/异常即判定对端断开。 */
    private final class ReadThread extends Thread {
        private final InputStream in;
        private volatile boolean running = true;

        ReadThread(InputStream in) {
            this.in = in;
        }

        void shutdown() {
            running = false;
        }

        @Override
        public void run() {
            byte[] buffer = new byte[256];
            while (running && !stopping) {
                int length;
                try {
                    length = in.read(buffer);
                } catch (IOException e) {
                    postDisconnected("读取中断：" + e.getMessage());
                    return;
                }
                if (length < 0) {
                    postDisconnected("接收端已断开连接");
                    return;
                }
                // 接收端不发送数据，收到即忽略
            }
        }

        private void postDisconnected(final String message) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    if (running && !stopping) {
                        listener.onPeerLeft(currentPeer, message);
                    }
                }
            });
        }
    }

    private static void closeQuietlySocket(BluetoothSocket target) {
        if (target == null) {
            return;
        }
        try {
            target.close();
        } catch (IOException ignored) {
            // 忽略
        }
    }

    private void postState(final Link.State state, final String message) {
        main.post(new Runnable() {
            @Override
            public void run() {
                listener.onState(state, message);
            }
        });
    }

    private final class AcceptThread extends Thread {
        private BluetoothServerSocket server;

        @Override
        public void run() {
            try {
                // server socket 只创建一次：通道与 SDP 记录终身不变，
                // 接收端任何时候用 SDP 查询都能命中同一通道
                server = adapter.listenUsingInsecureRfcommWithServiceRecord(serviceName, uuid);
            } catch (SecurityException e) {
                postState(Link.State.FAILED, "缺少蓝牙连接权限");
                return;
            } catch (IOException e) {
                postState(Link.State.FAILED, "创建蓝牙服务失败：" + e.getMessage());
                return;
            }
            postState(Link.State.WAITING, "蓝牙服务已开启，等待设备连接");
            // accept 循环：连接断开后回到 accept 等待下一次连接，
            // 不再关闭重建 server socket（那是通道漂移与 SDP 缓存错位的根源）
            while (!stopping) {
                BluetoothSocket accepted;
                try {
                    accepted = server.accept();
                } catch (IOException e) {
                    if (!stopping) {
                        postState(Link.State.FAILED, "监听中断：" + e.getMessage());
                    }
                    break;
                }
                if (stopping || accepted == null) {
                    closeQuietlySocket(accepted);
                    break;
                }
                attach(accepted);
            }
            closeServer();
        }

        void cancel() {
            closeServer();
        }

        private void closeServer() {
            if (server != null) {
                try {
                    server.close();
                } catch (IOException ignored) {
                    // 忽略
                }
                server = null;
            }
        }

        private void closeQuietly(BluetoothSocket target) {
            if (target == null) {
                return;
            }
            try {
                target.close();
            } catch (IOException ignored) {
                // 忽略
            }
        }
    }

    private final class ConnectThread extends Thread {
        private final BluetoothDevice device;
        private BluetoothSocket target;

        ConnectThread(BluetoothDevice device) {
            this.device = device;
        }

        @Override
        public void run() {
            try {
                target = device.createInsecureRfcommSocketToServiceRecord(uuid);
                target.connect();
            } catch (SecurityException e) {
                postState(Link.State.FAILED, "缺少蓝牙连接权限");
                return;
            } catch (IOException e) {
                postState(Link.State.FAILED, "连接失败：" + e.getMessage());
                closeQuietly();
                return;
            }
            if (stopping) {
                closeQuietly();
                return;
            }
            attach(target);
        }

        void cancel() {
            closeQuietly();
        }

        private void closeQuietly() {
            if (target != null) {
                try {
                    target.close();
                } catch (IOException ignored) {
                    // 忽略
                }
                target = null;
            }
        }
    }
}
