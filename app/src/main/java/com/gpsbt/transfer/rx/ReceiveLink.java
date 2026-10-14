package com.gpsbt.transfer.rx;

import com.gpsbt.transfer.nmea.NmeaInput;

/**
 * 接收端的一条取流通道：连上发送端，把收到的 NMEA 解析成定位。
 *
 * 两个实现：{@link NmeaClient}（WiFi / TCP）与 {@link BluetoothNmeaClient}
 * （蓝牙 SPP）。接收端按传输方式选一个，其余逻辑（解析、注入、界面状态）
 * 两者完全共用。
 */
public interface ReceiveLink {

    /** 回调一律在主线程。 */
    interface Callback {

        /** 连上 / 断开；connected 为 false 时 message 说明原因。 */
        void onConnectionChanged(boolean connected, String message);

        /** 解析出一条定位。 */
        void onLocation(NmeaInput location);
    }

    void start();

    /** 幂等。 */
    void stop();

    /** 当前正在尝试或已连上的目标（地址或设备名），供界面展示。 */
    String currentTarget();
}
