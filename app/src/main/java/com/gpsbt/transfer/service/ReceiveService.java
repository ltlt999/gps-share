package com.gpsbt.transfer.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;

import com.gpsbt.transfer.MainActivity;
import com.gpsbt.transfer.R;
import com.gpsbt.transfer.link.Peer;
import com.gpsbt.transfer.nmea.NmeaInput;
import com.gpsbt.transfer.rx.MockLocationInjector;
import com.gpsbt.transfer.rx.NmeaClient;
import com.gpsbt.transfer.state.LogBus;
import com.gpsbt.transfer.state.TransmitterState;
import com.gpsbt.transfer.ui.view.SatelliteRadarView;
import com.gpsbt.transfer.util.Formatters;
import com.gpsbt.transfer.util.Prefs;

import java.util.ArrayList;
import java.util.List;

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

    private Prefs prefs;
    private TransmitterState state;
    private NmeaClient client;
    private MockLocationInjector injector;
    private String host;
    private int port;
    private long startedAt;
    /** 已接收的定位条数，供通知展示。发送端用 TransmitStats，接收端自己数。 */
    private volatile long received;

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

    public static void stop(Context context) {
        Intent intent = new Intent(context, ReceiveService.class);
        intent.setAction(ACTION_STOP);
        context.getApplicationContext().startService(intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = Prefs.get(this);
        state = TransmitterState.get();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || !ACTION_START.equals(intent.getAction())) {
            LogBus.get().log(LogBus.Level.INFO, "用户停止接收");
            stopSelfSafely();
            return START_NOT_STICKY;
        }
        host = intent.getStringExtra(EXTRA_HOST);
        port = intent.getIntExtra(EXTRA_PORT, prefs.getLastServerPort());
        if (host == null || host.trim().isEmpty()) {
            LogBus.get().log(LogBus.Level.ERROR, "未指定发送端地址");
            stopSelfSafely();
            return START_NOT_STICKY;
        }

        try {
            goForeground();
        } catch (SecurityException e) {
            LogBus.get().log(LogBus.Level.ERROR, "前台服务启动被拒绝：" + e.getMessage());
            stopSelf();
            return START_NOT_STICKY;
        }

        stopExisting();
        running = true;
        startedAt = System.currentTimeMillis();
        state.setPhase(TransmitterState.Phase.RUNNING);
        state.setStatusText("正在连接 " + host + ":" + port);

        injector = new MockLocationInjector(this);
        if (!injector.start()) {
            state.setStatusText(getString(R.string.receive_mock_denied));
        } else {
            state.setStatusText("已就绪，正在连接 " + host + ":" + port);
        }

        client = new NmeaClient(host, port, callback);
        client.start();
        refreshNotification();
        return START_NOT_STICKY;
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
    }

    private final NmeaClient.Callback callback = new NmeaClient.Callback() {
        @Override
        public void onConnectionChanged(boolean connected, String message) {
            state.setBt(connected ? TransmitterState.Bt.CONNECTED : TransmitterState.Bt.WAITING);
            state.setStatusText(message);
            if (connected) {
                state.addPeer(new Peer(host + ":" + port, host + ":" + port,
                        System.currentTimeMillis()));
            } else {
                state.setPeers(null);
            }
            refreshNotification();
        }

        @Override
        public void onLocation(NmeaInput location) {
            received++;
            if (injector != null) {
                injector.inject(location);
            }
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
            fix.fixAt = System.currentTimeMillis();
            state.setFix(fix);

            List<SatelliteRadarView.Sat> converted = new ArrayList<SatelliteRadarView.Sat>();
            for (NmeaInput.Sat sat : location.satellites()) {
                converted.add(new SatelliteRadarView.Sat(sat.prn, sat.elevationDeg,
                        sat.azimuthDeg, sat.snr, sat.used));
            }
            state.setSatellites(converted);
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
        text.append(host == null ? "" : host).append(":").append(port);
        if (fix != null && fix.valid) {
            text.append("　").append(Formatters.latitude(fix.latitude))
                    .append("　").append(Formatters.longitude(fix.longitude));
        }
        text.append("　").append(getString(R.string.receive_notif_stats,
                received,
                Formatters.duration(System.currentTimeMillis() - startedAt)));

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

    private synchronized void stopSelfSafely() {
        running = false;
        stopExisting();
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
