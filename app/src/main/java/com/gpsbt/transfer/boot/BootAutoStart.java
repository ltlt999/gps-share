package com.gpsbt.transfer.boot;

import android.content.Context;

import com.gpsbt.transfer.link.Role;
import com.gpsbt.transfer.service.GpsOutputService;
import com.gpsbt.transfer.service.ReceiveService;
import com.gpsbt.transfer.state.LogBus;
import com.gpsbt.transfer.util.Prefs;

/**
 * 界面冷启动时的开机自启兜底。
 *
 * 一些车机与深度定制的国产 ROM 不会向应用投递 {@code BOOT_COMPLETED}，
 * 而是直接把应用的 launcher 界面拉起来（现象就是"软件自己打开了但不传输"）。
 * 这种情况下广播永远不会来，只能由界面启动时补一次自动开始传输。
 *
 * 另一个附带好处：Android 12 及以上系统禁止"后台启动的前台服务"读取定位，
 * 而从正在显示的界面启动服务属于前台操作，定位可用——所以这条兜底路径
 * 反而比广播路径更可靠。
 */
public final class BootAutoStart {

    /** 每个进程最多补一次，避免界面被反复拉起时重复启动服务。 */
    private static volatile boolean attempted;

    private BootAutoStart() {
    }

    /**
     * 是否应当补一次自动开始传输。纯决策逻辑，便于单元测试。
     *
     * @param switchOn        「开机自动开始传输」开关是否打开
     * @param alreadyAttempted 本进程是否已经尝试过
     * @param serviceRunning   服务是否已在传输
     * @param prereqOk         启动前置条件（定位/蓝牙权限、蓝牙硬件、目标设备）是否满足
     */
    static boolean shouldAutoStart(boolean switchOn, boolean alreadyAttempted,
                                   boolean serviceRunning, boolean prereqOk) {
        if (!switchOn) {
            return false;
        }
        if (alreadyAttempted) {
            return false;
        }
        if (!prereqOk) {
            return false;
        }
        return !serviceRunning;
    }

    /**
     * @return true 表示本次触发了自动开始传输
     */
    public static boolean tryStart(Context context) {
        Prefs prefs = Prefs.get(context);
        // 开关关闭时不标记 attempted：用户随后可能在设置里把它打开
        if (!prefs.isAutoStartOnBoot()) {
            return false;
        }

        // 角色决定启动哪条流水线。之前这里写死发送端，于是选了「接收 GPS」
        // 的用户在开机自启后拉起来的是发送端——界面显示在跑，热点 GPS 却
        // 一直没连上，必须手动停一次再开才对。
        if (prefs.getRole() == Role.RECEIVE) {
            String receiveBlocked = ReceiveService.prerequisiteFailure(context);
            if (receiveBlocked != null) {
                BootNotifier.reportBlocked(context, receiveBlocked);
                return false;
            }
            if (attempted || ReceiveService.isRunning()) {
                return false;
            }
            attempted = true;
            LogBus.get().log(LogBus.Level.INFO, "界面冷启动，自动开始接收");
            ReceiveService.start(context, prefs.getLastServerHost(), prefs.getLastServerPort());
            return true;
        }

        // 前置条件不满足时绝不能启动服务：Android 14+ 缺定位权限就启动 location 型
        // 前台服务会抛 SecurityException 直接闪退。此时给出明确反馈而不是崩
        String blocked = GpsOutputService.prerequisiteFailure(context);
        if (blocked != null) {
            BootNotifier.reportBlocked(context, blocked);
            return false;
        }

        if (!shouldAutoStart(true, attempted, GpsOutputService.isRunning(), true)) {
            return false;
        }
        attempted = true;

        // 走界面启动通道：不做"拿不到定位就停止"的超时判断，
        // GPS 冷启动可能要几十秒到几分钟，误停会打断正常使用
        LogBus.get().log(LogBus.Level.INFO, "界面冷启动，自动开始传输");
        GpsOutputService.startFromUi(context, prefs.getMode(), prefs.getLastDeviceMac());
        return true;
    }
}
