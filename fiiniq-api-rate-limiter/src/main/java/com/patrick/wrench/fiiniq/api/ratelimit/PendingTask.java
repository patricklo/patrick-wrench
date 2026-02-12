package com.patrick.wrench.fiiniq.api.ratelimit;

import java.util.concurrent.Callable;

/**
 * 队列中的待执行任务：一次 Finiiq API 调用的封装。
 * <p>
 * 包含请求 ID、待执行的 {@link Callable} 以及与之关联的 {@link FiiniqRequestRecord}，
 * 供限流器工作线程按序取出并执行，同时更新记录状态与时间戳。
 * </p>
 */
final class PendingTask {

    /** 请求 ID，用于取消与查询等待时间 */
    private final String requestId;
    /** 实际要执行的 Finiiq API 调用 */
    private final Callable<?> callable;
    /** 与该请求对应的记录，执行过程中会更新状态、开始时间、结束时间 */
    private final FiiniqRequestRecord record;

    PendingTask(String requestId, Callable<?> callable, FiiniqRequestRecord record) {
        this.requestId = requestId;
        this.callable = callable;
        this.record = record;
    }

    String getRequestId() { return requestId; }
    Callable<?> getCallable() { return callable; }
    FiiniqRequestRecord getRecord() { return record; }
}
