package com.patrick.wrench.fiiniq.api.ratelimit;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 限流器统计信息的快照。
 * <p>
 * 包含：正在等待的请求数、已完成的请求数、当前每分钟许可数，以及最近若干条请求记录
 * （每条含 requestId、状态、提交时间、实际开始/结束时间等），用于监控与展示。
 * </p>
 */
public class FiiniqRateLimitStats {

    /** 当前队列中处于等待状态（未取消）的请求数量 */
    private final int waitingCount;
    /** 历史累计已完成的请求数量（含成功与失败） */
    private final int completedCount;
    /** 当前根据响应时间推算的每分钟允许调用次数 */
    private final double permitsPerMinute;
    /** 最近若干条请求记录（按提交时间倒序），用于展示每个请求的开始/结束时间等 */
    private final List<FiiniqRequestRecord> records;

    public FiiniqRateLimitStats(int waitingCount, int completedCount, double permitsPerMinute,
                                List<FiiniqRequestRecord> records) {
        this.waitingCount = waitingCount;
        this.completedCount = completedCount;
        this.permitsPerMinute = permitsPerMinute;
        this.records = records == null ? Collections.emptyList() : records;
    }

    /** 正在等待调用的请求数 */
    public int getWaitingCount() { return waitingCount; }
    /** 已完成的请求数 */
    public int getCompletedCount() { return completedCount; }
    /** 当前每分钟许可数（由响应时间动态推算） */
    public double getPermitsPerMinute() { return permitsPerMinute; }
    /** 最近请求记录列表，每条可查看开始时间、结束时间等 */
    public List<FiiniqRequestRecord> getRecords() { return records; }
}
