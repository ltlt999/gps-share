package com.gpsbt.transfer.util;

import java.util.ArrayDeque;

/**
 * 定位到达速率的测量窗口。
 *
 * <p>设置里的滑块控制的是<b>请求</b>频率，GPS 芯片能给多少是另一回事 ——
 * 绝大多数手机是 1 Hz 出解。不把这两件事分开显示，用户会以为滑块拉到
 * 10 Hz 就能拿到 10 Hz 的定位。
 *
 * <p>用最近若干次定位到达的时刻算速率，而不是「当前时刻 − 上次到达」：
 * 后者在两次定位之间从 0 跳到 1 秒再跳回来，数字一直在跳。
 */
public final class FixRateMeter {

    /** 窗口里最多保留多少次定位。 */
    private static final int MAX_SAMPLES = 32;

    private final ArrayDeque<Long> samples = new ArrayDeque<>();

    /**
     * 记一次定位到达。
     *
     * <p>同一时刻的重复投递只算一次 —— 部分 ROM 会把同一次解算反复推给应用，
     * 算进去的话速率会翻好几倍。
     */
    public synchronized void onFix(long fixAtMillis) {
        Long last = samples.peekLast();
        if (last != null && last.longValue() == fixAtMillis) {
            return;
        }
        samples.addLast(Long.valueOf(fixAtMillis));
        while (samples.size() > MAX_SAMPLES) {
            samples.removeFirst();
        }
    }

    /**
     * 当前估算的速率（次/秒）。
     *
     * @return 样本不足两次时返回 0 —— 一次定位推不出速率
     */
    public synchronized double rateHz() {
        if (samples.size() < 2) {
            return 0d;
        }
        long first = samples.peekFirst().longValue();
        long last = samples.peekLast().longValue();
        long span = last - first;
        if (span <= 0L) {
            return 0d;
        }
        return (samples.size() - 1) * 1000.0d / span;
    }

    /** 清空窗口（重新开始传输时）。 */
    public synchronized void reset() {
        samples.clear();
    }
}
