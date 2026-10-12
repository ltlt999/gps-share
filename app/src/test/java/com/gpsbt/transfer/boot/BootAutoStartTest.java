package com.gpsbt.transfer.boot;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 覆盖界面冷启动兜底的决策逻辑（对应车机上"界面被拉起但不传输"的问题）。 */
public class BootAutoStartTest {

    @Test
    public void startsWhenSwitchOnAndEverythingReady() {
        assertTrue(BootAutoStart.shouldAutoStart(true, false, false, true));
    }

    @Test
    public void doesNothingWhenSwitchOff() {
        assertFalse(BootAutoStart.shouldAutoStart(false, false, false, true));
        // 开关关闭时即使服务已在跑、前置条件不满足，也不应有任何动作
        assertFalse(BootAutoStart.shouldAutoStart(false, false, true, false));
    }

    @Test
    public void doesNotStartWhenPrerequisitesMissing() {
        // 未授权定位/通知权限时启动会闪退（Android 14+ 抛 SecurityException），必须拦住
        assertFalse(BootAutoStart.shouldAutoStart(true, false, false, false));
    }

    @Test
    public void onlyOncePerProcess() {
        assertTrue(BootAutoStart.shouldAutoStart(true, false, false, true));
        assertFalse(BootAutoStart.shouldAutoStart(true, true, false, true));
    }

    @Test
    public void doesNotDuplicateWhenServiceAlreadyRunning() {
        // 开机广播已经启动过服务时，界面兜底不应再启动一次
        assertFalse(BootAutoStart.shouldAutoStart(true, false, true, true));
        assertFalse(BootAutoStart.shouldAutoStart(true, true, true, true));
    }
}
