package com.gpsbt.transfer.boot;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;

import com.gpsbt.transfer.MainActivity;
import com.gpsbt.transfer.R;
import com.gpsbt.transfer.state.LogBus;
import com.gpsbt.transfer.state.TransmitterState;

/** 开机自启未启动时的提示通知。 */
public final class BootNotifier {

    public static final int NOTIFICATION_ID = 2001;
    private static final String CHANNEL_ID = "boot_notice";

    private BootNotifier() {
    }

    /**
     * 自动传输没能开始：写日志、更新界面状态文字、发通知说明原因。
     * 三处都写是因为通知在 Android 13+ 可能被用户关掉，界面状态永远是可见的。
     */
    public static void reportBlocked(Context context, String reason) {
        LogBus.get().log(LogBus.Level.ERROR, "自动传输未启动：" + reason);
        TransmitterState.get().setStatusText(reason + "，无法开始传输");
        notifyBlocked(context, reason);
    }

    /** 发一条「自动传输未启动」通知，说明原因。 */
    public static void notifyBlocked(Context context, String reason) {
        NotificationManager manager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        createChannel(context, manager);
        String body = context.getString(R.string.boot_blocked_body, reason);
        Intent open = new Intent(context, MainActivity.class);
        PendingIntent content = PendingIntent.getActivity(context, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_satellite)
                .setContentTitle(context.getString(R.string.boot_blocked_title))
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .setContentIntent(content)
                .build();
        manager.notify(NOTIFICATION_ID, notification);
    }

    private static void createChannel(Context context, NotificationManager manager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                context.getString(R.string.boot_channel_name), NotificationManager.IMPORTANCE_LOW);
        manager.createNotificationChannel(channel);
    }
}
