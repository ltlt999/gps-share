package com.gpsbt.transfer.state;

/** 发送统计快照。字段均可由后台线程写、UI 线程读。 */
public final class TransmitStats {

    public volatile long sentences;
    public volatile long frames;
    public volatile long bytes;
    public volatile long lastFrameAt;
    public volatile long startedAt;
    public volatile double rateHz;
    /**
     * 实际到达的定位速率（次/秒）。
     *
     * <p>与请求频率分开：滑块控制的是向硬件请求定位的间隔，GPS 芯片能给多少
     * 是另一回事（绝大多数手机是 1 Hz）。
     */
    public volatile double fixRateHz;

    public void reset() {
        sentences = 0;
        frames = 0;
        bytes = 0;
        lastFrameAt = 0;
        startedAt = 0;
        rateHz = 0;
        fixRateHz = 0;
    }
}
