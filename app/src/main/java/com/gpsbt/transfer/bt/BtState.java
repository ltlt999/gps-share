package com.gpsbt.transfer.bt;

/** 蓝牙链路状态。顺序必须与 `state.TransmitterState.Bt` 一致。 */
public enum BtState {
    IDLE, WAITING, CONNECTING, CONNECTED, FAILED, DISCONNECTED
}
