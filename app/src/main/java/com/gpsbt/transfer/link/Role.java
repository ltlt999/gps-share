package com.gpsbt.transfer.link;

/** 本机在这套系统里扮演的角色。用户偏好，持久化在 `util.Prefs`。 */
public enum Role {
    /** 共享本机 GPS：蓝牙 / WiFi 两种传输方式。 */
    SHARE,
    /** 接收别处共享来的 GPS：连上发送端并注入系统定位。 */
    RECEIVE
}
