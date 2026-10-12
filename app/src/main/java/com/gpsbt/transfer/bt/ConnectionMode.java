package com.gpsbt.transfer.bt;

/** 蓝牙连接发起方。顺序必须与 `state.TransmitterState.Mode` 一致。 */
public enum ConnectionMode {
    /** 手机注册 SPP 服务，等待对端连接。 */
    SERVER,
    /** 手机主动连接已配对设备。 */
    CLIENT
}
