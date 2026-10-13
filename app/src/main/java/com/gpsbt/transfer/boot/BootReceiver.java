package com.gpsbt.transfer.boot;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.gpsbt.transfer.link.Role;
import com.gpsbt.transfer.service.GpsOutputService;
import com.gpsbt.transfer.service.ReceiveService;
import com.gpsbt.transfer.state.LogBus;
import com.gpsbt.transfer.util.Prefs;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 开机自启（后台路径）。
 *
 * 仅当用户在「设置 → 开机自动开始传输」中打开开关后才会自动开始传输；
 * 开关关闭时开机什么都不做（保持不自动运行）。
 *
 * 除了标准的 {@link Intent#ACTION_BOOT_COMPLETED}，还监听
 * {@code QUICKBOOT_POWERON}：部分车机与"快速启动"设备投递的是这个 action，
 * 只监听 BOOT_COMPLETED 会漏掉它们。
 *
 * 注意：这条路径属于"后台启动"，Android 12+ 系统禁止后台启动的前台服务读取定位，
 * 因此拿不到定位时会提示用户一键开始。真正可靠的是界面启动路径 {@link BootAutoStart}。
 */
public class BootReceiver extends BroadcastReceiver {

    private static final Set<String> BOOT_ACTIONS = new HashSet<String>(Arrays.asList(
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON"));

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (action == null || !BOOT_ACTIONS.contains(action)) {
            return;
        }
        Prefs prefs = Prefs.get(context);
        if (!prefs.isAutoStartOnBoot()) {
            LogBus.get().log(LogBus.Level.INFO, "开机自动传输未开启，跳过");
            return;
        }

        // 按角色分流：接收角色要拉起 ReceiveService，而不是发送端
        boolean receive = prefs.getRole() == Role.RECEIVE;
        if (receive ? ReceiveService.isRunning() : GpsOutputService.isRunning()) {
            LogBus.get().log(LogBus.Level.INFO, "传输已在运行，跳过重复启动");
            return;
        }

        // 必须在 startForegroundService 之前检查：Android 14+ 缺定位权限就启动
        // location 型前台服务会抛 SecurityException 导致闪退
        String blocked = receive
                ? ReceiveService.prerequisiteFailure(context)
                : GpsOutputService.prerequisiteFailure(context);
        if (blocked != null) {
            BootNotifier.reportBlocked(context, blocked);
            return;
        }

        LogBus.get().log(LogBus.Level.INFO, "收到开机广播（" + action + "），启动自动"
                + (receive ? "接收" : "传输"));
        if (receive) {
            ReceiveService.start(context, prefs.getLastServerHost(), prefs.getLastServerPort());
        } else {
            GpsOutputService.startFromBoot(context, prefs.getMode(), prefs.getLastDeviceMac());
        }
    }
}
