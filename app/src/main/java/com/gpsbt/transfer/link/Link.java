package com.gpsbt.transfer.link;

import java.io.IOException;

/**
 * 一条把 NMEA 字节流送出去的传输通道。
 *
 * 蓝牙（SPP，单对端）与 WiFi（TCP 服务端，多对端）都实现本接口，
 * 发送侧（TransmitEngine / GpsOutputService）只依赖接口，不关心底层是什么。
 *
 * 线程约定：目前回调线程由实现方决定，调用方**不能假定回调在主线程**。
 * 蓝牙实现的 onState / onPeerLeft 经 Handler 投递到主线程，而 onPeerJoined
 * 由 accept / connect 工作线程直接调用（沿用 v1.4.6 行为）。P2 引入多对端时
 * 需把这条收敛成硬性要求，否则每个回调点都要重新审一遍线程安全。
 */
public interface Link {

    /** 通道状态。顺序必须与 `state.TransmitterState.Bt` 一致。 */
    enum State { IDLE, WAITING, CONNECTING, CONNECTED, FAILED, DISCONNECTED }

    interface Listener {

        void onState(State state, String message);

        /** 一个接收端接入。 */
        void onPeerJoined(Peer peer);

        /** 一个接收端离开；reason 为人类可读的原因。 */
        void onPeerLeft(Peer peer, String reason);
    }

    /** 启动通道：监听（服务端）或发起连接（客户端）。 */
    void start();

    /** 停止通道并释放资源。可重复调用。 */
    void stop();

    /** 把字节广播给当前所有接收端；未就绪时抛 IOException。 */
    void write(byte[] data) throws IOException;

    /** 是否至少有一个接收端在线。 */
    boolean isConnected();

    /** 断开当前所有接收端，通道本身继续监听 / 可重连。 */
    void resetConnection();
}
