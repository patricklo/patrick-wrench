package com.patrick.wrench.fiiniq.api.ratelimit;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 根据最近 Finiiq API 的响应时间推算「每分钟允许的调用次数」。
 * <p>
 * 公式：permitsPerMinute = 60 / 平均响应时间（秒），再在配置的 [minPermitsPerMinute, maxPermitsPerMinute] 范围内截断。
 * 仅保留最近 {@code maxSamples} 次调用的耗时参与平均，避免历史数据影响过大。
 * </p>
 */
public class ResponseTimeTracker {

    /** 参与平均的最近响应时间样本数量，超过后剔除最旧的 */
    private final int maxSamples;
    /** 每分钟许可数下限，防止算出的值过小 */
    private final double minPermitsPerMinute;
    /** 每分钟许可数上限，防止算出的值过大 */
    private final double maxPermitsPerMinute;

    /** 最近若干次 API 调用的耗时（毫秒），FIFO */
    private final ConcurrentLinkedQueue<Long> responseTimesMs = new ConcurrentLinkedQueue<>();
    /** 当前队列中所有耗时的总和（毫秒），用于快速计算平均值 */
    private final AtomicLong sumMs = new AtomicLong(0);

    /**
     * 构造响应时间追踪器。
     *
     * @param maxSamples            参与平均的最大样本数
     * @param minPermitsPerMinute   每分钟许可数下限
     * @param maxPermitsPerMinute   每分钟许可数上限
     */
    public ResponseTimeTracker(int maxSamples, double minPermitsPerMinute, double maxPermitsPerMinute) {
        this.maxSamples = maxSamples;
        this.minPermitsPerMinute = minPermitsPerMinute;
        this.maxPermitsPerMinute = maxPermitsPerMinute;
    }

    /**
     * 记录一次 API 调用的耗时。
     *
     * @param durationMs 本次调用耗时（毫秒）
     */
    public void recordResponseTimeMs(long durationMs) {
        responseTimesMs.offer(durationMs);
        sumMs.addAndGet(durationMs);
        while (responseTimesMs.size() > maxSamples) {
            Long removed = responseTimesMs.poll();
            if (removed != null) sumMs.addAndGet(-removed);
        }
    }

    /**
     * 当前参与计算的样本数量。
     */
    public int getSampleCount() {
        return responseTimesMs.size();
    }

    /**
     * 平均响应时间（秒）。
     *
     * @return 若无样本则返回 1.0（避免除零），否则返回 sumMs / 样本数 / 1000
     */
    public double getAverageResponseTimeSeconds() {
        int n = responseTimesMs.size();
        if (n == 0) return 1.0;
        return (sumMs.get() / 1000.0) / n;
    }

    /**
     * 根据平均响应时间推导的「每分钟许可数」。
     * 计算 60 / 平均响应时间（秒），并限制在 [minPermitsPerMinute, maxPermitsPerMinute] 内。
     */
    public double getPermitsPerMinute() {
        double avgSec = getAverageResponseTimeSeconds();
        double raw = 60.0 / avgSec;
        if (raw < minPermitsPerMinute) return minPermitsPerMinute;
        if (raw > maxPermitsPerMinute) return maxPermitsPerMinute;
        return raw;
    }

    /**
     * 两次调用开始时间之间应间隔的秒数（用于限流调度）。
     * 即 60 / getPermitsPerMinute()。
     */
    public double getIntervalBetweenCallsSeconds() {
        return 60.0 / getPermitsPerMinute();
    }
}
