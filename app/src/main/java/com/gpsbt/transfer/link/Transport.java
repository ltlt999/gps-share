package com.gpsbt.transfer.link;

/** 发送端的传输方式。用户偏好，持久化在 `util.Prefs`。 */
public enum Transport {
    /** 蓝牙 SPP（单对端，服务端 / 客户端两种模式）。 */
    BLUETOOTH,
    /** WiFi / TCP 服务端（多对端，只在热点上监听）。 */
    WIFI
}
