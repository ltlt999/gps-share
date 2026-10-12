package com.gpsbt.transfer.state;

/** 发送统计快照。字段均可由后台线程写、UI 线程读。 */
public final class TransmitStats {

    public volatile long sentences;
    public volatile long frames;
    public volatile long bytes;
    public volatile long lastFrameAt;
    public volatile long startedAt;
    public volatile double rateHz;

    public void reset() {
        sentences = 0;
        frames = 0;
        bytes = 0;
        lastFrameAt = 0;
        startedAt = 0;
        rateHz = 0;
    }
}
