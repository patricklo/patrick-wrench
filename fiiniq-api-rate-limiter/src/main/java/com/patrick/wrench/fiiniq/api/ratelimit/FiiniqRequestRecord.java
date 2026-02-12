package com.patrick.wrench.fiiniq.api.ratelimit;

import java.time.Instant;

/**
 * 单次 Finiiq API 请求的记录。
 * <p>
 * 包含请求 ID、状态以及提交时间、实际调用开始时间、结束时间，用于追踪与统计。
 * </p>
 */
public class FiiniqRequestRecord {

    /**
     * 请求状态枚举。
     */
    public enum Status {
        /** 在队列中等待，尚未开始调用 */
        PENDING,
        /** 正在调用 Finiiq API */
        RUNNING,
        /** 调用成功结束 */
        COMPLETED,
        /** 已被取消（在队列中或取消时尚未开始） */
        CANCELLED,
        /** 执行过程中抛出异常 */
        FAILED
    }

    /** 唯一请求 ID（如 UUID） */
    private final String requestId;
    /** 当前状态，多线程下使用 volatile 保证可见性 */
    private volatile Status status;
    /** 提交到限流器的时间 */
    private final Instant submitTime;
    /** 实际开始调用 Finiiq API 的时间，未开始时为 null */
    private volatile Instant startTime;
    /** 实际结束调用 Finiiq API 的时间，未结束时为 null */
    private volatile Instant endTime;

    /**
     * 创建一条请求记录，初始状态为 PENDING。
     *
     * @param requestId 请求 ID
     * @param submitTime 提交时间
     */
    public FiiniqRequestRecord(String requestId, Instant submitTime) {
        this.requestId = requestId;
        this.status = Status.PENDING;
        this.submitTime = submitTime;
        this.startTime = null;
        this.endTime = null;
    }

    public String getRequestId() { return requestId; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public Instant getSubmitTime() { return submitTime; }
    public Instant getStartTime() { return startTime; }
    public void setStartTime(Instant startTime) { this.startTime = startTime; }
    public Instant getEndTime() { return endTime; }
    public void setEndTime(Instant endTime) { this.endTime = endTime; }

    /**
     * 本次实际 API 调用的耗时（毫秒）。
     *
     * @return 若尚未有开始/结束时间则返回 null，否则返回 endTime - startTime 的毫秒数
     */
    public Long getDurationMs() {
        if (startTime == null || endTime == null) return null;
        return endTime.toEpochMilli() - startTime.toEpochMilli();
    }
}
