package com.gpsbt.transfer.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

import android.Manifest;

import com.gpsbt.transfer.MainActivity;
import com.gpsbt.transfer.boot.BootNotifier;
import com.gpsbt.transfer.R;
import com.gpsbt.transfer.bt.BluetoothLinkManager;
import com.gpsbt.transfer.bt.ConnectionMode;
import com.gpsbt.transfer.engine.LocationProvider;
import com.gpsbt.transfer.engine.TransmitEngine;
import com.gpsbt.transfer.link.Link;
import com.gpsbt.transfer.link.Peer;
import com.gpsbt.transfer.link.Transport;
import com.gpsbt.transfer.net.DiscoveryBroadcaster;
import com.gpsbt.transfer.net.NetLinkManager;
import com.gpsbt.transfer.nmea.NmeaGenerator;
import com.gpsbt.transfer.nmea.NmeaInput;
import com.gpsbt.transfer.nmea.NmeaSentenceType;
import com.gpsbt.transfer.state.LogBus;
import com.gpsbt.transfer.state.TransmitterState;
import com.gpsbt.transfer.state.TransmitStats;
import com.gpsbt.transfer.ui.view.SatelliteRadarView;
import com.gpsbt.transfer.util.Formatters;
import com.gpsbt.transfer.util.Prefs;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 前台服务：定位 → NMEA → 蓝牙串口。
 * 入口有三处：主页按钮、开机广播（BootReceiver）、界面冷启动兜底（BootAutoStart）。
 * 使用 START_NOT_STICKY，被杀后不自动重启；是否开机自启由设置开关决定。
 */
public class GpsOutputService extends Service {

    public static final String ACTION_START = "com.gpsbt.transfer.action.START";
    public static final String ACTION_STOP = "com.gpsbt.transfer.action.STOP";
    public static final String EXTRA_MODE = "com.gpsbt.transfer.extra.MODE";
    public static final String EXTRA_DEVICE_MAC = "com.gpsbt.transfer.extra.DEVICE_MAC";
    public static final String EXTRA_FROM_BOOT = "com.gpsbt.transfer.extra.FROM_BOOT";
    public static final String EXTRA_FROM_UI = "com.gpsbt.transfer.extra.FROM_UI";

    private static final int NOTIFICATION_ID = 1001;
    private static final String CHANNEL_ID = "gps_bt_output";

    /** 后台启动的前台服务拿不到定位时，多久后放弃（仅 Android 12+ 且非界面启动时生效）。 */
    private static final long BG_FIX_TIMEOUT_MS = 20000L;
    /** 开机后等待蓝牙开启的最大时长（每 2 秒一次）。 */
    private static final int BT_WAIT_MAX_ATTEMPTS = 60;

    /** 服务是否正在运行，供开机自启判断是否重复启动。 */
    private static volatile boolean running;

    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> future;
    private ScheduledExecutorService relinkExecutor;
    private long lastNotificationAt;

    private Prefs prefs;
    private LocationProvider locationProvider;
    private TransmitEngine engine;
    private Link link;
    private DiscoveryBroadcaster discovery;
    private TransmitStats stats;
    private TransmitterState state;
    private volatile ConnectionMode mode = ConnectionMode.SERVER;
    private volatile String deviceMac;
    private volatile boolean fromBoot;
    /** 是否从正在显示的界面启动（此时属前台操作，定位可用，不受 Android 12+ 限制）。 */
    private volatile boolean uiStarted;
    private volatile boolean fixReceived;
    private volatile long startedAt;
    /** 「蓝牙未就绪」日志节流（30 秒一次），链路恢复后复位。 */
    private volatile long linkNotReadyLoggedAt;

    public static void start(Context context, ConnectionMode mode, String deviceMac) {
        Intent intent = new Intent(context, GpsOutputService.class);
        intent.setAction(ACTION_START);
        intent.putExtra(EXTRA_MODE, (mode == null ? ConnectionMode.SERVER : mode).name());
        intent.putExtra(EXTRA_DEVICE_MAC, deviceMac);
        Context app = context.getApplicationContext();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            app.startForegroundService(intent);
        } else {
            app.startService(intent);
        }
    }

    /** 开机自启入口：前置条件不满足时只通知不启动，蓝牙栈未就绪时由服务等待。 */
    public static void startFromBoot(Context context, ConnectionMode mode, String deviceMac) {
        Intent intent = new Intent(context, GpsOutputService.class);
        intent.setAction(ACTION_START);
        intent.putExtra(EXTRA_FROM_BOOT, true);
        intent.putExtra(EXTRA_MODE, (mode == null ? ConnectionMode.SERVER : mode).name());
        intent.putExtra(EXTRA_DEVICE_MAC, deviceMac);
        Context app = context.getApplicationContext();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            app.startForegroundService(intent);
        } else {
            app.startService(intent);
        }
    }

    /** 服务当前是否正在传输。 */
    public static boolean isRunning() {
        return running;
    }

    /**
     * 启动前置条件检查。
     *
     * 必须在调用 {@link #startFromBoot} 之前先过这一关：Android 14+ 在缺少定位权限时
     * 启动 location 类型的前台服务会直接抛 SecurityException 导致闪退，而
     * {@code startForeground()} 又必须调用，所以只能从源头避免启动服务。
     *
     * @return null 表示可以启动；否则返回不可启动的原因文案
     */
    public static String prerequisiteFailure(Context context) {
        if (!LocationProvider.hasPermission(context)) {
            return context.getString(R.string.boot_reason_location);
        }
        Prefs prefs = Prefs.get(context);
        // WiFi 通道不依赖蓝牙硬件与配对，跳过蓝牙相关的前置检查
        if (prefs.getTransport() != Transport.BLUETOOTH) {
            return null;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            return context.getString(R.string.boot_reason_bluetooth_permission);
        }
        if (BluetoothAdapter.getDefaultAdapter() == null) {
            return context.getString(R.string.boot_reason_no_bluetooth);
        }
        if (prefs.getMode() == ConnectionMode.CLIENT) {
            String mac = prefs.getLastDeviceMac();
            if (mac == null || mac.isEmpty()) {
                return context.getString(R.string.boot_reason_no_device);
            }
        }
        return null;
    }

    /**
     * 从正在显示的界面启动服务。
     * 属前台操作，不受 Android 12+「后台启动的前台服务不能读取定位」限制，
     * 因此不做"拿不到定位就停止"的超时判断——GPS 冷启动可能要几十秒到几分钟。
     */
    public static void startFromUi(Context context, ConnectionMode mode, String deviceMac) {
        Intent intent = new Intent(context, GpsOutputService.class);
        intent.setAction(ACTION_START);
        intent.putExtra(EXTRA_FROM_BOOT, true);
        intent.putExtra(EXTRA_FROM_UI, true);
        intent.putExtra(EXTRA_MODE, (mode == null ? ConnectionMode.SERVER : mode).name());
        intent.putExtra(EXTRA_DEVICE_MAC, deviceMac);
        Context app = context.getApplicationContext();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            app.startForegroundService(intent);
        } else {
            app.startService(intent);
        }
    }

    public static void stop(Context context) {
        Intent intent = new Intent(context, GpsOutputService.class);
        intent.setAction(ACTION_STOP);
        context.getApplicationContext().startService(intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = Prefs.get(this);
        state = TransmitterState.get();
        stats = new TransmitStats();
        createChannel();
        state.setStats(stats);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || !ACTION_START.equals(intent.getAction())) {
            LogBus.get().log(LogBus.Level.INFO, "用户停止传输");
            stopSelfSafely();
            return START_NOT_STICKY;
        }

        try {
            mode = ConnectionMode.valueOf(intent.getStringExtra(EXTRA_MODE));
        } catch (RuntimeException e) {
            mode = ConnectionMode.SERVER;
        }
        deviceMac = intent.getStringExtra(EXTRA_DEVICE_MAC);
        fromBoot = intent.getBooleanExtra(EXTRA_FROM_BOOT, false);
        uiStarted = intent.getBooleanExtra(EXTRA_FROM_UI, false);

        // 必须在前台化之前检查：Android 14+ 缺定位权限就启动 location 型前台服务会抛
        // SecurityException。调用方（开机广播 / 界面兜底）已先检查过，这里是第二道防线
        String blocked = prerequisiteFailure(this);
        if (blocked != null) {
            BootNotifier.reportBlocked(this, blocked);
            stopSelf();
            return START_NOT_STICKY;
        }

        try {
            goForeground();
        } catch (SecurityException e) {
            // 个别定制系统即便权限齐全也可能拒绝 location 型前台服务，降级为提示而不是闪退
            LogBus.get().log(LogBus.Level.ERROR, "前台服务启动被拒绝：" + e.getMessage());
            BootNotifier.reportBlocked(this, getString(R.string.boot_reason_location));
            stopSelf();
            return START_NOT_STICKY;
        }
        startedAt = System.currentTimeMillis();
        fixReceived = false;
        running = true;

        if (!startPipeline()) {
            stopSelfSafely();
            return START_NOT_STICKY;
        }
        // 关键约定：禁用"被杀后自动重启"
        return START_NOT_STICKY;
    }

    private boolean startPipeline() {
        if (!LocationProvider.hasPermission(this)) {
            failStart(getString(R.string.boot_reason_location));
            return false;
        }
        if (prefs.getTransport() == Transport.WIFI) {
            return startTransmission(null);
        }

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            failStart(getString(R.string.boot_reason_no_bluetooth));
            return false;
        }
        if (mode == ConnectionMode.CLIENT && (deviceMac == null || deviceMac.isEmpty())) {
            failStart(getString(R.string.boot_reason_no_device));
            return false;
        }

        if (!adapter.isEnabled()) {
            if (!fromBoot) {
                failStart(getString(R.string.boot_reason_bluetooth_off));
                return false;
            }
            // 开机自启：蓝牙栈可能尚未就绪，等待而非直接放弃
            state.setPhase(TransmitterState.Phase.RUNNING);
            state.setMode(mode);
            state.setConfig(prefs.getServiceName(), prefs.getUuid(), prefs.getRateHz());
            state.setStatusText("等待蓝牙开启…");
            LogBus.get().log(LogBus.Level.INFO, "等待蓝牙开启…");
            waitForBluetooth(adapter);
            return true;
        }
        return startTransmission(adapter);
    }

    /** 启动失败：开机自启时发通知说明原因，用户手动启动时只更新界面状态。 */
    private void failStart(String reason) {
        state.setStatusText(reason + "，无法开始传输");
        LogBus.get().log(LogBus.Level.ERROR, "启动失败：" + reason);
        if (fromBoot) {
            BootNotifier.notifyBlocked(this, reason);
        }
    }

    /** 蓝牙尚未开启时的开机自启等待，最多等 60 秒。 */
    private void waitForBluetooth(final BluetoothAdapter adapter) {
        final java.util.concurrent.ScheduledExecutorService waiter =
                java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        waiter.scheduleWithFixedDelay(new Runnable() {
            private int attempts;

            @Override
            public void run() {
                attempts++;
                if (adapter.isEnabled()) {
                    waiter.shutdownNow();
                    if (!startTransmission(adapter)) {
                        stopSelfSafely();
                    }
                    return;
                }
                if (attempts >= BT_WAIT_MAX_ATTEMPTS) {
                    waiter.shutdownNow();
                    LogBus.get().log(LogBus.Level.WARN, "开机自动传输未启动：蓝牙未开启");
                    BootNotifier.notifyBlocked(GpsOutputService.this,
                            getString(R.string.boot_reason_bluetooth_off));
                    stopSelfSafely();
                }
            }
        }, 2, 2, java.util.concurrent.TimeUnit.SECONDS);
    }

    private boolean startTransmission(BluetoothAdapter adapter) {
        boolean wifi = prefs.getTransport() == Transport.WIFI;
        // 重复的启动请求（开机自启 + 界面兜底、连点开始等）必须先彻底清理旧流水线：
        // 否则旧 link 的 server socket 仍在监听并会"抢走"接收端的连接，
        // 而真正在发送的新流水线手里没有连接——两端都显示已连接，却一个字节都发不出去
        stopExistingPipeline();

        state.setPhase(TransmitterState.Phase.RUNNING);
        state.setMode(mode);
        state.setConfig(prefs.getServiceName(), prefs.getUuid(), prefs.getRateHz());
        state.setStatusText(wifi
                ? "WiFi 服务启动中…"
                : (mode == ConnectionMode.SERVER ? "蓝牙服务启动中…" : "正在连接设备…"));

        Set<NmeaSentenceType> configured = prefs.getSentences();
        final Set<NmeaSentenceType> enabled =
                configured.isEmpty() ? NmeaSentenceType.all() : configured;
        final NmeaGenerator generator = new NmeaGenerator(prefs.getDefaultHdop());

        engine = new TransmitEngine(new TransmitEngine.Sink() {
            @Override
            public void write(byte[] data, int length) throws IOException {
                byte[] payload = new byte[length];
                System.arraycopy(data, 0, payload, 0, length);
                link.write(payload);
            }
        }, generator, enabled, prefs.getStaleMillis());

        locationProvider = new LocationProvider(this);
        locationProvider.start(prefs.isAllowNetwork(), 1000 / Math.max(1, prefs.getRateHz()),
                locationCallback);

        startScheduler(prefs.getRateHz());
        if (!connectLink(adapter)) {
            return false;
        }
        LogBus.get().log(LogBus.Level.INFO,
                "传输已启动，模式：" + (mode == ConnectionMode.SERVER ? "等待被连接" : "主动连接"));
        return true;
    }

    /** 清理可能存在的旧流水线（链路、定位、调度、重连任务）。可重复调用。 */
    private synchronized void stopExistingPipeline() {
        stopRelink();
        stopDiscovery();
        if (link != null) {
            link.stop();
            link = null;
        }
        if (locationProvider != null) {
            locationProvider.stop();
            locationProvider = null;
        }
        stopScheduler();
    }

    /** 只建立（或重建）链路，不重置定位与调度——断线重连时复用。 */
    private boolean connectLink(BluetoothAdapter adapter) {
        if (prefs.getTransport() == Transport.WIFI) {
            link = new NetLinkManager(prefs.getWifiPort(), linkListener);
            link.start();
            startDiscovery();
            return true;
        }
        BluetoothDevice device = null;
        if (mode == ConnectionMode.CLIENT) {
            if (adapter == null) {
                state.setStatusText("蓝牙不可用");
                return false;
            }
            try {
                device = adapter.getRemoteDevice(deviceMac);
            } catch (IllegalArgumentException e) {
                state.setStatusText("目标设备地址无效：" + deviceMac);
                LogBus.get().log(LogBus.Level.ERROR, "目标设备地址无效：" + deviceMac);
                return false;
            }
            state.setPendingDevice(new TransmitterState.Device(
                    BluetoothLinkManager.displayName(device), deviceMac));
        }
        link = new BluetoothLinkManager(adapter, prefs.getServiceName(),
                UUID.fromString(prefs.getUuid()), mode, device, linkListener);
        if (mode == ConnectionMode.SERVER) {
            state.setStatusText("蓝牙服务启动中…");
        }
        link.start();
        return true;
    }

    /** 开启服务发现广播；设置里关掉时不发。 */
    private void startDiscovery() {
        if (!prefs.isDiscoveryEnabled()) {
            return;
        }
        discovery = new DiscoveryBroadcaster(prefs.getServiceName(),
                prefs.getWifiPort(), prefs.getDiscoveryPort());
        discovery.start();
    }

    private void stopDiscovery() {
        if (discovery != null) {
            discovery.stop();
            discovery = null;
        }
    }

    /**
     * 链路断开 / 写失败后的自动重建：5 秒后重新监听或重连，
     * 定位与发送调度保持运行，服务不再因断线整体退出。
     */
    private void scheduleRelink() {
        if (!running) {
            return;
        }
        stopRelink();
        state.setPeers(null);
        state.setBt(TransmitterState.Bt.WAITING);
        state.setStatusText("蓝牙链路中断，5 秒后重新建立");
        LogBus.get().log(LogBus.Level.WARN, "蓝牙链路中断，5 秒后重新建立");
        relinkExecutor = Executors.newSingleThreadScheduledExecutor();
        relinkExecutor.schedule(new Runnable() {
            @Override
            public void run() {
                if (!running) {
                    return;
                }
                if (link != null) {
                    link.stop();
                    link = null;
                }
                if (prefs.getTransport() == Transport.WIFI) {
                    if (!connectLink(null)) {
                        stopSelfSafely();
                    }
                    return;
                }
                BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                if (adapter == null || !adapter.isEnabled()) {
                    state.setStatusText("蓝牙未开启，等待重新开启…");
                    LogBus.get().log(LogBus.Level.WARN, "蓝牙未开启，继续等待");
                    scheduleRelink();
                    return;
                }
                if (!connectLink(adapter)) {
                    stopSelfSafely();
                }
            }
        }, 5, TimeUnit.SECONDS);
    }

    private void stopRelink() {
        if (relinkExecutor != null) {
            relinkExecutor.shutdownNow();
            relinkExecutor = null;
        }
    }

    private void startScheduler(int rateHz) {
        stopScheduler();
        final long period = 1000L / Math.max(1, rateHz);
        scheduler = Executors.newSingleThreadScheduledExecutor();
        future = scheduler.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                tickInternal();
            }
        }, 0, period, TimeUnit.MILLISECONDS);
    }

    private void stopScheduler() {
        if (future != null) {
            future.cancel(false);
            future = null;
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    private void tickInternal() {
        long now = System.currentTimeMillis();
        // 只有"开机广播在后台启动 + Android 12+"才会拿不到定位（系统禁止后台启动的
        // 前台服务读定位）。界面启动属前台操作，不受此限制，因此不能超时停掉——
        // GPS 冷启动可能要几十秒到几分钟，等不到定位就停会误伤正常使用
        boolean bgBlocked = fromBoot && !uiStarted
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
        if (bgBlocked && !fixReceived && now - startedAt > BG_FIX_TIMEOUT_MS) {
            LogBus.get().log(LogBus.Level.WARN, "后台启动的服务无法获取定位，降级为一键开始");
            notifyLocationRestricted();
            stopSelfSafely();
            return;
        }
        final TransmitEngine current = engine;
        final Link currentLink = link;
        if (current == null || currentLink == null || !currentLink.isConnected()) {
            // 每 30 秒提示一次：失败窗口里能看到「发送端没有客户端」，便于定位
            if (now - linkNotReadyLoggedAt > 30000L) {
                linkNotReadyLoggedAt = now;
                LogBus.get().log(LogBus.Level.WARN,
                        linkLabel() + "未就绪，暂停发送（等待接收端连接）");
            }
            return;
        }
        linkNotReadyLoggedAt = 0;
        try {
            List<String> sentences = current.tick(now);
            if (!sentences.isEmpty()) {
                // tick 已通过 Sink 写入蓝牙流，这里只刷新统计与通知
                stats.rateHz = current.rateHz(now);
                maybeUpdateNotification(now);
                for (String sentence : sentences) {
                    logSentence(sentence);
                }
            } else {
                // 无定位时发 ZDA 保活帧：链路保持有数据流动，
                // 接收端断开能在 1 秒内被写入失败暴露
                String keepalive = current.tickKeepalive(now);
                if (keepalive != null) {
                    logSentence(keepalive);
                }
            }
        } catch (IOException e) {
            LogBus.get().log(LogBus.Level.ERROR, "发送失败：" + e.getMessage());
            if (mode == ConnectionMode.SERVER) {
                // 服务器模式：清理死连接，accept 循环仍在等待下一次连接
                if (currentLink != null) {
                    currentLink.resetConnection();
                }
                state.setPeers(null);
                state.setBt(TransmitterState.Bt.WAITING);
                state.setStatusText("蓝牙链路中断，等待接收端重新连接");
                refreshNotification();
            } else {
                scheduleRelink();
            }
        }
    }

    /** 原始 NMEA 语句按类别记录：卫星语句（GSV/GSA）单独一类，便于日志筛选。 */
    private static void logSentence(String sentence) {
        String text = sentence == null ? "" : sentence.trim();
        if (text.isEmpty()) {
            return;
        }
        String upper = text.toUpperCase(Locale.US);
        LogBus.get().log(upper.contains("GSV") || upper.contains("GSA")
                ? LogBus.Level.SAT : LogBus.Level.DATA, text);
    }

    private final LocationProvider.Callback locationCallback = new LocationProvider.Callback() {
        @Override
        public void onFix(NmeaInput input) {
            fixReceived = true;
            LocationProvider provider = locationProvider;
            final NmeaInput full = provider == null ? input : provider.withSatellites(input);
            final TransmitEngine current = engine;
            if (current != null) {
                current.publish(full, System.currentTimeMillis());
            }

            TransmitterState.Fix fix = new TransmitterState.Fix();
            fix.valid = full.valid;
            fix.satellitesInView = full.satellitesInView();
            fix.satellitesUsed = full.satellitesUsed;
            fix.latitude = full.latitude;
            fix.longitude = full.longitude;
            fix.altitudeM = full.altitudeM;
            fix.speedMps = full.speedMps;
            fix.bearingDeg = full.bearingDeg;
            fix.accuracyM = full.accuracyM;
            fix.fixAt = System.currentTimeMillis();
            state.setFix(fix);

            List<SatelliteRadarView.Sat> converted = new ArrayList<SatelliteRadarView.Sat>();
            for (NmeaInput.Sat sat : full.satellites()) {
                converted.add(new SatelliteRadarView.Sat(sat.prn, sat.elevationDeg,
                        sat.azimuthDeg, sat.snr, sat.used, sat.talker));
            }
            state.setSatellites(converted);
        }

        @Override
        public void onStatus(String message) {
            state.setStatusText(message);
            LogBus.get().log(LogBus.Level.INFO, message);
        }

        @Override
        public void onSatelliteUpdate(int inView, int used) {
            LogBus.get().log(LogBus.Level.SAT, "卫星：可见 " + inView + "，用于定位 " + used);
        }
    };

    private final Link.Listener linkListener = new Link.Listener() {
        @Override
        public void onState(Link.State linkState, String message) {
            state.setBt(mapBt(linkState));
            state.setStatusText(message);
            LogBus.get().log(mapLevel(linkState), linkLabel() + "：" + message);
            if (linkState == Link.State.FAILED) {
                // 监听建立失败属于致命错误（权限/蓝牙不可用），停止服务
                stopSelfSafely();
                return;
            }
            refreshNotification();
        }

        @Override
        public void onPeerJoined(Peer peer) {
            state.addPeer(peer);
            state.setPendingDevice(null);
            stats.startedAt = System.currentTimeMillis();
            LogBus.get().log(LogBus.Level.INFO,
                    linkLabel() + "已连接 " + peer.name + "（" + peer.address + "）");
            refreshNotification();
        }

        @Override
        public void onPeerLeft(Peer peer, String reason) {
            LogBus.get().log(LogBus.Level.WARN, linkLabel() + "已断开：" + reason);
            state.removePeer(peer);
            if (mode == ConnectionMode.SERVER && prefs.getTransport() == Transport.BLUETOOTH) {
                // 蓝牙单对端：清理死连接，accept 循环继续等待下一次连接
                Link currentLink = link;
                if (currentLink != null) {
                    currentLink.resetConnection();
                }
                state.setBt(TransmitterState.Bt.WAITING);
                state.setStatusText("蓝牙已断开，等待接收端重新连接");
                refreshNotification();
            } else if (prefs.getTransport() == Transport.WIFI) {
                // WiFi 多对端：只摘掉走掉的那一个，其余接收端不受影响
                state.setStatusText(peerCountText());
                refreshNotification();
            } else {
                state.setStatusText("蓝牙连接已断开，尝试重新连接");
                scheduleRelink();
            }
        }
    };

    private static TransmitterState.Bt mapBt(Link.State value) {
        switch (value) {
            case WAITING:
                return TransmitterState.Bt.WAITING;
            case CONNECTING:
                return TransmitterState.Bt.CONNECTING;
            case CONNECTED:
                return TransmitterState.Bt.CONNECTED;
            case FAILED:
                return TransmitterState.Bt.FAILED;
            case DISCONNECTED:
                return TransmitterState.Bt.DISCONNECTED;
            case IDLE:
            default:
                return TransmitterState.Bt.IDLE;
        }
    }

    private static LogBus.Level mapLevel(Link.State value) {
        switch (value) {
            case FAILED:
                return LogBus.Level.ERROR;
            case DISCONNECTED:
                return LogBus.Level.WARN;
            default:
                return LogBus.Level.INFO;
        }
    }

    /** 日志前缀：当前传输方式的短名。 */
    private String linkLabel() {
        return prefs.getTransport() == Transport.WIFI ? "WiFi" : "蓝牙";
    }

    /** WiFi 下的状态文案：还在收就报在线台数，全走光了就报等待。 */
    private String peerCountText() {
        int count = state.peers().size();
        return count == 0
                ? "接收端已断开，继续等待连接"
                : "接收端 " + count + " 台在线";
    }

    // ---------- 通知 ----------

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                getString(R.string.channel_transmit_name), NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.channel_transmit_desc));
        channel.setShowBadge(false);
        NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    private void goForeground() {
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void refreshNotification() {
        NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification());
        }
    }

    private Notification buildNotification() {
        TransmitterState.Bt bt = state.bt();
        TransmitterState.Fix fix = state.fix();
        String title;
        if (bt == TransmitterState.Bt.CONNECTED) {
            title = getString(R.string.notif_sending);
        } else if (bt == TransmitterState.Bt.WAITING || bt == TransmitterState.Bt.CONNECTING) {
            title = getString(R.string.notif_waiting);
        } else if (fix != null && fix.valid) {
            title = getString(R.string.notif_sending);
        } else {
            title = getString(R.string.notif_no_fix);
        }
        TransmitterState.Device device = state.connectedDevice();
        StringBuilder text = new StringBuilder();
        if (device != null) {
            text.append(getString(R.string.notif_connected_to, device.name));
        } else {
            text.append(state.statusText());
        }
        text.append("　");
        if (fix != null && fix.valid) {
            text.append(Formatters.latitude(fix.latitude))
                    .append("　").append(Formatters.longitude(fix.longitude));
        }
        long sent = engine == null ? 0 : engine.sentences();
        long started = stats.startedAt;
        text.append("　").append(getString(R.string.notif_stats, sent,
                Formatters.duration(started == 0 ? -1 : System.currentTimeMillis() - started)));

        Intent open = new Intent(this, MainActivity.class);
        PendingIntent content = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stopIntent = new Intent(this, GpsOutputService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopAction = PendingIntent.getService(this, 1, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_satellite)
                .setContentTitle(title)
                .setContentText(text.toString())
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text.toString()))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(content)
                .addAction(0, getString(R.string.notif_action_stop), stopAction)
                .build();
    }

    /** Android 12+ 后台启动的服务无法访问定位时，提示用户一键开始。 */
    private void notifyLocationRestricted() {
        NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        Intent intent = new Intent(this, GpsOutputService.class);
        intent.setAction(ACTION_START);
        intent.putExtra(EXTRA_FROM_BOOT, false);
        intent.putExtra(EXTRA_MODE, mode.name());
        intent.putExtra(EXTRA_DEVICE_MAC, deviceMac);
        PendingIntent action = PendingIntent.getService(this, 2, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_satellite)
                .setContentTitle(getString(R.string.notif_restricted_title))
                .setContentText(getString(R.string.notif_restricted_body))
                .setStyle(new NotificationCompat.BigTextStyle()
                        .bigText(getString(R.string.notif_restricted_body)))
                .setAutoCancel(true)
                .addAction(0, getString(R.string.notif_action_start), action)
                .build();
        manager.notify(3001, notification);
    }

    private void maybeUpdateNotification(long now) {
        if (now - lastNotificationAt < 2000L) {
            return;
        }
        lastNotificationAt = now;
        refreshNotification();
    }

    private synchronized void stopSelfSafely() {
        running = false;
        stopRelink();
        stopDiscovery();
        if (locationProvider != null) {
            locationProvider.stop();
            locationProvider = null;
        }
        if (link != null) {
            link.stop();
            link = null;
        }
        stopScheduler();
        engine = null;
        state.resetForStop();
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        stopSelfSafely();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
