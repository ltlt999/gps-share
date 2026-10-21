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

        /**
         * 线上累计字节数与语句条数。
         *
         * <p><b>与定位快照分开上报。</b>发送端没有新定位时只发保活帧
         * （{@code $GPSSHARE} + {@code $GPZDA}），解析器一个快照都不发布 ——
         * 计数跟着快照走的话，「已接收数据量」会整个冻住，用户会误判成假连接。
         * 计数跟着字节流走，与有没有定位无关。
         *
         * <p>两个数都是<b>本次客户端生命周期</b>的累计值，断线重连不清零 ——
         * 发送端那边的计数可不会归零，两端口径必须一样。
         */
        void onProgress(long bytes, long sentences);
    }

    void start();

    /** 幂等。 */
    void stop();

    /** 当前正在尝试或已连上的目标（地址或设备名），供界面展示。 */
    String currentTarget();
}
