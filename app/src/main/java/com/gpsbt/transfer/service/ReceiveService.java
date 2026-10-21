package com.gpsbt.transfer.service;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

import com.gpsbt.transfer.MainActivity;
import com.gpsbt.transfer.R;
import com.gpsbt.transfer.boot.BootNotifier;
import com.gpsbt.transfer.boot.ServiceWatchdog;
import com.gpsbt.transfer.bt.BluetoothLinkManager;
import com.gpsbt.transfer.engine.LocationProvider;
import com.gpsbt.transfer.link.Peer;
import com.gpsbt.transfer.link.Transport;
import com.gpsbt.transfer.net.NetworkTargets;
import com.gpsbt.transfer.nmea.NmeaInput;
import com.gpsbt.transfer.rx.BluetoothNmeaClient;
import com.gpsbt.transfer.rx.MockLocationInjector;
import com.gpsbt.transfer.rx.NmeaClient;
import com.gpsbt.transfer.rx.ReceiveLink;
import com.gpsbt.transfer.license.本地配置;
import com.gpsbt.transfer.state.LogBus;
import com.gpsbt.transfer.state.TransmitStats;
import com.gpsbt.transfer.state.TransmitterState;
import com.gpsbt.transfer.ui.view.SatelliteRadarView;
import com.gpsbt.transfer.util.Formatters;
import com.gpsbt.transfer.util.Prefs;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 接收端前台服务：连发送端 → 解析 NMEA → 注入系统定位。
 *
 * 与 {@link GpsOutputService} 是两条互斥的流水线，同一时刻只跑一条。
 */
public class ReceiveService extends Service {

    public static final String ACTION_START = "com.gpsbt.transfer.action.RECEIVE_START";
    public static final String ACTION_STOP = "com.gpsbt.transfer.action.RECEIVE_STOP";
    public static final String EXTRA_HOST = "com.gpsbt.transfer.extra.HOST";
    public static final String EXTRA_PORT = "com.gpsbt.transfer.extra.PORT";

    private static final int NOTIFICATION_ID = 1002;
    private static final String CHANNEL_ID = "gps_share_receive";

    private static volatile boolean running;
    /**
     * 当前活着的服务实例。停止时直接用它，不走 startService(ACTION_STOP)——
     * 那条路在服务没运行时会把服务重新拉起来再立刻停掉，表现为「要点两次才停」。
     */
    private static volatile ReceiveService instance;

    private Prefs prefs;
    private TransmitterState state;
    private TransmitStats stats;
    private ReceiveLink client;
    private MockLocationInjector injector;
    /** 注入连续失败时对外报告。 */
    private final MockLocationInjector.FailureListener injectionFailureListener =
            new MockLocationInjector.FailureListener() {
                @Override
                public void onInjectionFailed(String message) {
                    LogBus.get().log(LogBus.Level.ERROR, "定位注入连续失败：" + message);
                    state.setStatusText(getString(R.string.receive_inject_failed));
                    refreshNotification();
                }
            };
    private String host;
    private int port;
    /** 停止幂等守卫：stopSelfSafely 被多次调用时只真正执行一次。 */
    private boolean stopped;
    /** 当前流水线是按什么配置建的；用来判断重复的启动请求是否真的需要重启。 */
    private String runningConfig = "";
    /** 蓝牙模式下要连的设备名（缓存，避免每次刷新通知都查一遍蓝牙）。 */
    private String bluetoothTargetLabel = "";
    /** 接收速率的统计窗口：每秒算一次上一秒收到了多少条。 */
    private long rateWindowStart;
    private long rateWindowSentences;

    public static boolean isRunning() {
        return running;
    }

    public static void start(Context context, String host, int port) {
        Intent intent = new Intent(context, ReceiveService.class);
        intent.setAction(ACTION_START);
        intent.putExtra(EXTRA_HOST, host);
        intent.putExtra(EXTRA_PORT, port);
        Context app = context.getApplicationContext();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            app.startForegroundService(intent);
        } else {
            app.startService(intent);
        }
    }

    /**
     * 停止接收。**幂等**：没有活实例时什么都不做。
     *
     * 不能用 startService(ACTION_STOP)——服务没运行时那条路会把服务重新拉起来
     * 再立刻停掉，用户体感就是「停止按钮点第一次反而启动了，要点第二次」。
     */
    public static void stop(Context context) {
        ReceiveService local = instance;
        if (local == null) {
            LogBus.get().log(LogBus.Level.INFO, "接收本就未在运行，无需停止");
            return;
        }
        local.stopSelfSafely(true);
    }

    /**
     * 启动接收端的前置条件。
     *
     * @return null 表示可以启动；否则返回不可启动的原因文案
     */
    public static String prerequisiteFailure(Context context) {
        if (!LocationProvider.hasPermission(context)) {
            return context.getString(R.string.boot_reason_location);
        }
        // 授权把关放在服务层，界面、开机广播、看门狗都绕不过去
        if (!本地配置.isUsable(context)) {
            return context.getString(R.string.license_expired_title);
        }
        Prefs prefs = Prefs.get(context);
        if (prefs.getTransport() == Transport.BLUETOOTH) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    && ContextCompat.checkSelfPermission(context,
                    Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                return context.getString(R.string.boot_reason_bluetooth_permission);
            }
            if (BluetoothAdapter.getDefaultAdapter() == null) {
                return context.getString(R.string.boot_reason_no_bluetooth);
            }
            if (prefs.getReceiveDeviceMac().isEmpty()) {
                return context.getString(R.string.receive_device_required);
            }
        }
        // WiFi 下地址不再是硬性条件：拿不到时客户端会原地等，
        // 热点连上后靠「默认网关」也能找到发送端
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        prefs = Prefs.get(this);
        state = TransmitterState.get();
        // 接收端有自己的统计对象：之前不设的话，运行数据里显示的是上一次
        // 发送会话留下的陈旧数字（setStats 原本只在发送端调过）
        stats = new TransmitStats();
        state.setStats(stats);
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        boolean systemRestart = intent == null;
        if (!systemRestart && !ACTION_START.equals(intent.getAction())) {
            LogBus.get().log(LogBus.Level.INFO, "用户停止接收");
            stopSelfSafely(true);
            return START_NOT_STICKY;
        }
        if (systemRestart) {
            // START_STICKY：系统回收进程后把服务拉起来，此时没有 Intent。
            // **绝不能当成「用户停止」**——按设置里记着的配置原地恢复，
            // 否则服务被回收后就再也起不来了（表现为「必须打开一次应用」）。
            LogBus.get().log(LogBus.Level.INFO, "接收服务被系统回收，按上次配置自动恢复");
            host = prefs.getLastServerHost();
            port = prefs.getLastServerPort();
        } else {
            host = intent.getStringExtra(EXTRA_HOST);
            if (host == null) {
                host = "";
            }
            host = host.trim();
            port = intent.getIntExtra(EXTRA_PORT, prefs.getLastServerPort());
        }

        // 第二道防线：调用方（界面 / 开机广播 / 看门狗）已先查过一遍，但服务
        // 自己也要把关——授权到期、权限被撤销都可能发生在两次调用之间。
        // 发送端一直有这一层，接收端之前漏了。
        String blocked = prerequisiteFailure(this);
        if (blocked != null) {
            LogBus.get().log(LogBus.Level.ERROR, "接收无法启动：" + blocked);
            BootNotifier.reportBlocked(this, blocked);
            stopSelf();
            return START_NOT_STICKY;
        }

        // 幂等：已在按同一配置运行就什么都不做，绝不把正在跑的连接拆掉重来。
        // 这一层守卫放在服务里（而不是只靠界面不误触发），因为配置的真相在
        // 服务手上——界面、开机广播、通知动作都可能重复发启动请求。
        String config = currentConfig();
        if (running && config.equals(runningConfig)) {
            LogBus.get().log(LogBus.Level.INFO, "接收配置未变，忽略重复的启动请求");
            return START_NOT_STICKY;
        }

        // 地址留空也允许启动：接收端可能比 WiFi 先起来，等网络连上后
        // 候选地址（记下的 + 默认网关）自然就有了，客户端会自己连上。
        try {
            goForeground();
        } catch (SecurityException e) {
            LogBus.get().log(LogBus.Level.ERROR, "前台服务启动被拒绝：" + e.getMessage());
            stopSelf();
            return START_NOT_STICKY;
        }

        stopExisting();
        stopped = false;
        running = true;
        runningConfig = config;
        state.setPhase(TransmitterState.Phase.RUNNING);
        state.setStatusText(initialStatus());

        injector = new MockLocationInjector(this);
        injector.setFailureListener(injectionFailureListener);
        if (!injector.start()) {
            state.setStatusText(getString(R.string.receive_mock_denied));
        } else {
            state.setStatusText(host.isEmpty() && prefs.getTransport() != Transport.BLUETOOTH
                    ? "已就绪，正在等待热点连接…" : "已就绪，" + initialStatus());
        }

        client = createLink();
        client.start();
        refreshNotification();

        // 记住「用户希望它跑」，并安排看门狗。
        // 服务被系统回收后：START_STICKY 让系统重启它；万一 ROM 拦截了重启，
        // 看门狗兜底。两者都不依赖用户去打开界面。
        prefs.setServiceWanted(true);
        ServiceWatchdog.schedule(this);
        // STICKY：进程被杀后系统会带着 null Intent 把它拉回来（见上面 systemRestart 分支）。
        // 用户主动停止走的是 stopSelfSafely(true) + stopSelf()，不会被重启。
        return START_STICKY;
    }

    private void stopExisting() {
        if (client != null) {
            client.stop();
            client = null;
        }
        if (injector != null) {
            injector.stop();
            injector = null;
        }
        // 同发送端：旧链路的断开不会回调，显式清掉，避免「连接设备」残留
        state.setPeers(null);
        rateWindowStart = 0L;
        rateWindowSentences = 0L;
        stats.rateHz = 0d;
    }

    /**
     * 接收速率（条/秒）：每秒算一次上一秒收到了多少条。
     *
     * 按窗口算而不是取瞬时值——与发送端的 TransmitEngine 一个思路，
     * 数字不会因为一两帧的抖动乱跳。
     */
    private void updateReceiveRate(long now) {
        if (rateWindowStart == 0L) {
            rateWindowStart = now;
            rateWindowSentences = stats.sentences;
            return;
        }
        long elapsed = now - rateWindowStart;
        if (elapsed >= 1000L) {
            stats.rateHz = (stats.sentences - rateWindowSentences) * 1000.0d / elapsed;
            rateWindowStart = now;
            rateWindowSentences = stats.sentences;
        }
    }

    /**
     * 候选发送端地址，每次尝试前重新问一次。
     *
     * 先试记下的地址；再试**当前默认网关**——接收端连在发送端热点上，网关
     * 就是发送端本身。记下的地址可能失效（热点重启后网段会变），网关则永远
     * 是当前有效的。网络还没连上时两者都可能拿不到，这时客户端会原地等待，
     * 等 WiFi 连上后自然就能连上发送端。
     */
    /**
     * 按传输方式选通道：
     * - WiFi → TCP，候选地址每次尝试前动态解析（记下的地址 + 默认网关）；
     * - 蓝牙 → SPP，连到用户在界面上选定的已配对发送端。
     */
    private ReceiveLink createLink() {
        if (prefs.getTransport() == Transport.BLUETOOTH) {
            String mac = prefs.getReceiveDeviceMac();
            bluetoothTargetLabel = deviceLabel(mac);
            return new BluetoothNmeaClient(BluetoothAdapter.getDefaultAdapter(), mac,
                    bluetoothTargetLabel, UUID.fromString(prefs.getUuid()), callback);
        }
        return new NmeaClient(targetProvider, port, callback);
    }

    /**
     * 当前流水线是按什么配置建的。
     *
     * 用来拦住**重复的启动请求**：界面切页会重建视图，重建后的分段控件可能
     * 误触发一次启动；若这里不比对，就会把正在跑的连接拆掉重来，对端读到
     * EOF 后反复断开重连。
     */
    private String currentConfig() {
        if (prefs.getTransport() == Transport.BLUETOOTH) {
            return "BT:" + prefs.getReceiveDeviceMac() + ":" + prefs.getUuid();
        }
        return "WIFI:" + port + ":" + host;
    }

    /** 设备名只用于展示；取不到就退回 MAC。 */
    private String deviceLabel(String mac) {
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter != null) {
                return BluetoothLinkManager.displayName(adapter.getRemoteDevice(mac));
            }
        } catch (RuntimeException ignored) {
            // 权限或地址问题：退回 MAC 展示即可
        }
        return mac;
    }

    /**
     * 「连接设备」与通知里显示的对方。
     *
     * 蓝牙是**设备名**（用户选的那台发送端），WiFi 是「地址:端口」。
     * 不能直接拼 `host + ":" + port`——蓝牙模式下 host 是空的，会显示成
     * 没有意义的「:10110」。
     */
    private String targetLabel() {
        if (prefs.getTransport() == Transport.BLUETOOTH) {
            return bluetoothTargetLabel;
        }
        ReceiveLink current = client;
        String address = current == null ? host : current.currentTarget();
        if (address == null || address.isEmpty()) {
            address = host;
        }
        return address == null || address.isEmpty() ? "" : address + ":" + port;
    }

    /** 启动时的状态文案，按传输方式取词。 */
    private String initialStatus() {
        if (prefs.getTransport() == Transport.BLUETOOTH) {
            String mac = prefs.getReceiveDeviceMac();
            return mac.isEmpty() ? "请先在下方选择发送端设备" : "正在连接 " + deviceLabel(mac);
        }
        return host.isEmpty() ? "正在等待热点连接…" : "正在连接 " + host + ":" + port;
    }

    private final NmeaClient.TargetProvider targetProvider = new NmeaClient.TargetProvider() {
        @Override
        public List<String> targets() {
            List<String> targets = new ArrayList<>();
            // 1) 界面刚发现到的发送端：网段变了以后这个最准，
            //    不必等用户手点，也不依赖网关推断
            for (String discovered : state.discoveredHosts()) {
                if (discovered != null && !discovered.isEmpty() && !targets.contains(discovered)) {
                    targets.add(discovered);
                }
            }
            // 2) 上次连上的地址：网段没变时最快
            String remembered = prefs.getLastServerHost();
            if (remembered != null && !remembered.isEmpty() && !targets.contains(remembered)) {
                targets.add(remembered);
            }
            // 3) 当前 WiFi 网络的网关：接收端连在发送端热点上时，网关就是发送端。
            //    界面不在前台、发现停了的时候靠它兜底。
            String gateway = NetworkTargets.defaultGateway(ReceiveService.this);
            if (!gateway.isEmpty() && !targets.contains(gateway)) {
                targets.add(gateway);
            }
            return targets;
        }
    };

    private final ReceiveLink.Callback callback = new ReceiveLink.Callback() {
        @Override
        public void onConnectionChanged(boolean connected, String message) {
            state.setBt(connected ? TransmitterState.Bt.CONNECTED : TransmitterState.Bt.WAITING);
            state.setStatusText(message);
            if (connected) {
                String label = targetLabel();
                state.addPeer(new Peer(label, label, System.currentTimeMillis()));
                // 「在线时长」从本次连接建立起算
                if (stats.startedAt == 0) {
                    stats.startedAt = System.currentTimeMillis();
                }
            } else {
                state.setPeers(null);
                stats.startedAt = 0L;
                if (injector != null) {
                    // 断链后要忘掉上一个解算的身份：重连后发送端的序号可能
                    // 从头开始（新进程），旧身份留着会把新位置误判成重复
                    injector.resetPolicy();
                }
            }
            refreshNotification();
        }

        @Override
        public void onLocation(NmeaInput location) {
            // 前置条件在运行期间也可能变化——只在启动时查一次的话，
            // 跨过到期点的会话会一直跑下去
            if (!prerequisiteSatisfied(ReceiveService.this)) {
                LogBus.get().log(LogBus.Level.WARN, "前置条件不满足，停止接收");
                stopSelfSafely(false);
                return;
            }
            stats.sentences++;
            stats.bytes += location.wireBytes;
            updateReceiveRate(System.currentTimeMillis());
            if (injector != null) {
                injector.inject(location);
            }
            // 定位状态：每个解算更新一次
            TransmitterState.Fix fix = new TransmitterState.Fix();
            fix.valid = location.valid;
            fix.satellitesInView = location.satellitesInView();
            fix.satellitesUsed = location.satellitesUsed;
            fix.latitude = location.latitude;
            fix.longitude = location.longitude;
            fix.altitudeM = location.altitudeM;
            fix.speedMps = location.speedMps;
            fix.bearingDeg = location.bearingDeg;
            fix.accuracyM = location.accuracyM;
            // 数据来源与解算年龄：界面上要能看出「这份定位是哪来的、有多旧」
            fix.source = location.source == null ? "" : location.source.wire();
            fix.ageMillis = location.ageMillis;
            fix.fixAt = System.currentTimeMillis();
            state.setFix(fix);

            // 卫星明细只在带卫星的快照里更新：位置快照的卫星列表是空的，
            // 拿它去覆盖会把刚收到的卫星视图清掉
            if (location.withSatellites) {
                List<SatelliteRadarView.Sat> converted = new ArrayList<SatelliteRadarView.Sat>();
                for (NmeaInput.Sat sat : location.satellites()) {
                    converted.add(new SatelliteRadarView.Sat(sat.prn, sat.elevationDeg,
                            sat.azimuthDeg, sat.snr, sat.used, sat.talker));
                }
                state.setSatellites(converted);
            }
            refreshNotification();
        }
    };

    // ---------- 通知 ----------

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                getString(R.string.channel_receive_name), NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.channel_receive_desc));
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
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
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
        TransmitterState.Fix fix = state.fix();
        String title = state.bt() == TransmitterState.Bt.CONNECTED
                ? getString(R.string.receive_notif_receiving)
                : getString(R.string.receive_notif_waiting);
        StringBuilder text = new StringBuilder();
        text.append(client == null ? "" : client.currentTarget());
        if (fix != null && fix.valid) {
            text.append("　").append(Formatters.latitude(fix.latitude))
                    .append("　").append(Formatters.longitude(fix.longitude));
        }
        text.append("　").append(getString(R.string.receive_notif_stats,
                stats.sentences,
                Formatters.duration(stats.startedAt > 0
                        ? System.currentTimeMillis() - stats.startedAt : -1L)));

        Intent open = new Intent(this, MainActivity.class);
        PendingIntent content = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stopIntent = new Intent(this, ReceiveService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopAction = PendingIntent.getService(this, 3, stopIntent,
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

    /** 幂等：重复调用只真正执行一次，避免二次停止时把状态又写脏。 */
    /**
     * 停止服务。
     *
     * @param userRequested true 表示用户主动停止（按钮 / 通知）——此时才清掉
     *                      「希望运行」标记并取消看门狗；系统回收导致的销毁传
     *                      false，标记要留着，看门狗下次才敢把它拉回来
     */
    private synchronized void stopSelfSafely(boolean userRequested) {
        if (stopped) {
            return;
        }
        stopped = true;
        if (userRequested) {
            prefs.setServiceWanted(false);
            ServiceWatchdog.cancel(this);
        }
        running = false;
        runningConfig = "";
        stopExisting();
        state.resetForStop();
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        // 系统回收（不是用户停止）：不清「希望运行」标记，
        // 否则看门狗会以为用户停了，永远不会把它拉回来
        stopSelfSafely(false);
        if (instance == this) {
            instance = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
