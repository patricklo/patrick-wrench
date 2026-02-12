package com.patrick.wrench.fiiniq.api.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Finiiq API 限流器：根据实际 API 响应时间动态限制每分钟调用次数，并为每次请求生成 ID、记录状态与时间。
 * <p>
 * 功能概要：
 * <ul>
 *   <li>提交任务：{@link #submit(Callable)} 返回 requestId，任务进入队列按速率依次执行</li>
 *   <li>剩余等待时间：{@link #getRemainingWaitTimeSeconds(String)} 根据队列位置与平均响应时间估算</li>
 *   <li>取消请求：{@link #cancel(String)} 可取消仍在队列中未开始的请求</li>
 *   <li>统计与记录：{@link #getStats()}、{@link #getRecord(String)} 可查看等待数、完成数、每条的开始/结束时间</li>
 * </ul>
 * 内部使用单线程顺序执行，每次调用结束后根据 {@link ResponseTimeTracker} 推算的间隔再调度下一次。
 * </p>
 */
public class FiiniqApiRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(FiiniqApiRateLimiter.class);

    /** 根据响应时间推算每分钟许可数及调用间隔 */
    private final ResponseTimeTracker responseTimeTracker;
    /** 内存中最多保留的请求记录数，超过后按提交时间淘汰最早的 */
    private final int maxRecords;
    /** 请求 ID -> 请求记录，用于按 ID 查询、取消及统计 */
    private final ConcurrentHashMap<String, FiiniqRequestRecord> recordsById = new ConcurrentHashMap<>();
    /** 待执行任务队列，工作线程按序取非取消任务执行 */
    private final Queue<PendingTask> queue = new ConcurrentLinkedQueue<>();
    /** 累计已完成的请求数（含成功与失败） */
    private final AtomicInteger completedCount = new AtomicInteger(0);
    /** 最近一次调用开始的纳秒时间戳，用于计算距下次可调用的间隔 */
    private final AtomicLong lastCallStartNanos = new AtomicLong(0);
    /** 当前正在执行的那条请求记录，用于估算剩余等待时间 */
    private volatile FiiniqRequestRecord currentRunningRecord = null;
    /** 单线程调度器：负责按间隔取出队列任务并执行 */
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "fiiniq-api-rate-limiter-worker");
        t.setDaemon(false);
        return t;
    });
    /** 是否已关闭，关闭后不再接受新任务并停止调度 */
    private volatile boolean shutdown = false;

    /**
     * 构造限流器。
     *
     * @param responseTimeTracker 响应时间追踪器，用于动态计算每分钟许可数与调用间隔
     * @param maxRecords          最多保留的请求记录数，≤0 时使用 10000
     */
    public FiiniqApiRateLimiter(ResponseTimeTracker responseTimeTracker, int maxRecords) {
        this.responseTimeTracker = responseTimeTracker;
        this.maxRecords = maxRecords <= 0 ? 10_000 : maxRecords;
        scheduleNext();
    }

    /**
     * 提交一次 Finiiq API 调用，放入队列并按限流速率执行。
     *
     * @param callable 实际执行 API 调用的逻辑
     * @return 本次请求的唯一 ID，可用于查询等待时间、取消、查记录
     * @throws IllegalStateException 若限流器已 shutdown
     */
    public String submit(Callable<?> callable) {
        if (shutdown) throw new IllegalStateException("FiiniqApiRateLimiter is shutdown");
        String requestId = UUID.randomUUID().toString();
        Instant submitTime = Instant.now();
        FiiniqRequestRecord record = new FiiniqRequestRecord(requestId, submitTime);
        recordsById.put(requestId, record);
        evictOldRecordsIfNeeded();
        queue.offer(new PendingTask(requestId, callable, record));
        log.debug("Submitted request {} (queue size ~{})", requestId, queue.size());
        return requestId;
    }

    /**
     * 估算指定请求还需等待的秒数（直到该请求开始执行）。
     *
     * @param requestId 请求 ID
     * @return 估算的剩余等待时间（秒）；若请求不存在或非 PENDING 返回 -1 或 0
     */
    public double getRemainingWaitTimeSeconds(String requestId) {
        FiiniqRequestRecord record = recordsById.get(requestId);
        if (record == null) return -1;
        if (record.getStatus() != FiiniqRequestRecord.Status.PENDING) return 0;

        int position = positionInQueue(requestId);
        if (position < 0) return 0; // 已在执行或已不在队列中

        double intervalSec = responseTimeTracker.getIntervalBetweenCallsSeconds();
        double waitForAhead = position * intervalSec;

        // 若当前有请求正在执行，需加上“当前请求预计剩余耗时”
        FiiniqRequestRecord running = currentRunningRecord;
        if (running != null && position == 0) {
            double avgSec = responseTimeTracker.getAverageResponseTimeSeconds();
            long startNanos = lastCallStartNanos.get();
            if (startNanos > 0) {
                double elapsedSec = (System.nanoTime() - startNanos) / 1_000_000_000.0;
                double remainingSec = Math.max(0, avgSec - elapsedSec);
                return remainingSec;
            }
        }
        if (running != null && position > 0) {
            double avgSec = responseTimeTracker.getAverageResponseTimeSeconds();
            long startNanos = lastCallStartNanos.get();
            if (startNanos > 0) {
                double elapsedSec = (System.nanoTime() - startNanos) / 1_000_000_000.0;
                double remainingCurrent = Math.max(0, avgSec - elapsedSec);
                waitForAhead += remainingCurrent;
            }
        }

        return waitForAhead;
    }

    /**
     * 取消仍在队列中等待（PENDING）的请求。
     *
     * @param requestId 请求 ID
     * @return 若找到且状态为 PENDING 并已标记为 CANCELLED 返回 true，否则 false（已执行/已完成/已取消/不存在）
     */
    public boolean cancel(String requestId) {
        FiiniqRequestRecord record = recordsById.get(requestId);
        if (record == null) return false;
        if (record.getStatus() != FiiniqRequestRecord.Status.PENDING) return false;
        record.setStatus(FiiniqRequestRecord.Status.CANCELLED);
        log.debug("Cancelled request {}", requestId);
        return true;
    }

    /**
     * 获取当前限流统计快照：等待数、完成数、每分钟许可数、最近请求记录。
     */
    public FiiniqRateLimitStats getStats() {
        int waiting = countPendingInQueue();
        int completed = completedCount.get();
        double ppm = responseTimeTracker.getPermitsPerMinute();
        List<FiiniqRequestRecord> list = new ArrayList<>(recordsById.values());
        list.sort(Comparator.comparing(FiiniqRequestRecord::getSubmitTime).reversed());
        int limit = Math.min(500, list.size());
        List<FiiniqRequestRecord> recent = list.subList(0, limit).stream().collect(Collectors.toList());
        return new FiiniqRateLimitStats(waiting, completed, ppm, recent);
    }

    /**
     * 根据请求 ID 获取该请求的记录（状态、提交时间、开始/结束时间等）。
     *
     * @param requestId 请求 ID
     * @return 对应记录，不存在则 null
     */
    public FiiniqRequestRecord getRecord(String requestId) {
        return recordsById.get(requestId);
    }

    /**
     * 计算指定 requestId 在队列中排在第几位（只计未取消的）；若不在队列或已取消则返回 -1。
     */
    private int positionInQueue(String requestId) {
        int position = 0;
        for (PendingTask t : queue) {
            if (t.getRecord().getStatus() == FiiniqRequestRecord.Status.CANCELLED) continue;
            if (t.getRequestId().equals(requestId)) return position;
            position++;
        }
        return -1;
    }

    /** 统计队列中未取消的待执行任务数量 */
    private int countPendingInQueue() {
        int n = 0;
        for (PendingTask t : queue) {
            if (t.getRecord().getStatus() != FiiniqRequestRecord.Status.CANCELLED) n++;
        }
        return n;
    }

    /** 若记录数超过 maxRecords，按提交时间淘汰最早的一批（不淘汰仍为 PENDING 的） */
    private void evictOldRecordsIfNeeded() {
        if (recordsById.size() <= maxRecords) return;
        List<FiiniqRequestRecord> sorted = new ArrayList<>(recordsById.values());
        sorted.sort(Comparator.comparing(FiiniqRequestRecord::getSubmitTime));
        int toRemove = recordsById.size() - (maxRecords * 9 / 10);
        for (int i = 0; i < toRemove && i < sorted.size(); i++) {
            FiiniqRequestRecord r = sorted.get(i);
            if (r.getStatus() == FiiniqRequestRecord.Status.PENDING) continue;
            recordsById.remove(r.getRequestId());
        }
    }

    /** 调度下一次执行：立即提交 runOne 到调度器 */
    private void scheduleNext() {
        if (shutdown) return;
        scheduler.schedule(this::runOne, 0, TimeUnit.NANOSECONDS);
    }

    /**
     * 执行一帧：取一个未取消任务，若需限流则等待间隔后再执行，否则立即执行；
     * 执行后更新记录状态与时间戳、记录响应时间并增加完成数，再调度下一帧。
     */
    private void runOne() {
        if (shutdown) return;
        PendingTask task = pollNextNonCancelled();
        if (task == null) {
            // 队列暂无有效任务，稍后再检查，避免空转
            scheduler.schedule(this::runOne, 1, TimeUnit.SECONDS);
            return;
        }

        // 根据上次调用开始时间与当前推算的间隔，判断是否需要等待
        double intervalSec = responseTimeTracker.getIntervalBetweenCallsSeconds();
        long lastStart = lastCallStartNanos.get();
        if (lastStart > 0) {
            long elapsedNanos = System.nanoTime() - lastStart;
            long intervalNanos = (long) (intervalSec * 1_000_000_000);
            long waitNanos = intervalNanos - elapsedNanos;
            if (waitNanos > 0) {
                scheduler.schedule(() -> runOne(), waitNanos, TimeUnit.NANOSECONDS);
                return;
            }
        }

        lastCallStartNanos.set(System.nanoTime());
        currentRunningRecord = task.getRecord();
        task.getRecord().setStatus(FiiniqRequestRecord.Status.RUNNING);
        task.getRecord().setStartTime(Instant.now());

        try {
            Object result = task.getCallable().call();
            task.getRecord().setEndTime(Instant.now());
            task.getRecord().setStatus(FiiniqRequestRecord.Status.COMPLETED);
            long durationMs = task.getRecord().getDurationMs() != null ? task.getRecord().getDurationMs() : 0;
            responseTimeTracker.recordResponseTimeMs(durationMs);
            completedCount.incrementAndGet();
        } catch (Throwable e) {
            task.getRecord().setEndTime(Instant.now());
            task.getRecord().setStatus(FiiniqRequestRecord.Status.FAILED);
            long durationMs = task.getRecord().getDurationMs() != null ? task.getRecord().getDurationMs() : 0;
            if (durationMs > 0) responseTimeTracker.recordResponseTimeMs(durationMs);
            log.warn("Finiiq API call failed for request {}", task.getRequestId(), e);
        } finally {
            currentRunningRecord = null;
        }

        scheduleNext();
    }

    /** 从队列头开始 poll，跳过已取消的任务，返回第一个未取消的任务；队列空或全为已取消则返回 null */
    private PendingTask pollNextNonCancelled() {
        PendingTask t;
        while ((t = queue.poll()) != null) {
            if (t.getRecord().getStatus() != FiiniqRequestRecord.Status.CANCELLED) return t;
        }
        return null;
    }

    /**
     * 关闭限流器：停止接受新任务并关闭调度线程。
     * 应用关闭时由 Spring 的 destroyMethod 调用，也可手动调用。
     */
    public void shutdown() {
        shutdown = true;
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(30, TimeUnit.SECONDS)) scheduler.shutdownNow();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            scheduler.shutdownNow();
        }
    }
}
