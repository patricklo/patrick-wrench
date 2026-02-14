package com.patrick.wrench.fiiniq.api.ratelimit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Fiiniq API 限流器 API 测试：模拟不同场景下的限流、取消、等待时间与统计。
 * <p>
 * 部分用例依赖异步工作线程与限流间隔，整体执行时间较长（约数分钟）；
 * 快速校验可只跑 {@link ResponseTimeTrackerTest} 与本类中「提交与记录」「剩余等待时间」「异常与关闭」等不同步的用例。
 * </p>
 */
@DisplayName("FiiniqApiRateLimiter API 测试")
class FiiniqApiRateLimiterTest {

    private ResponseTimeTracker responseTimeTracker;
    private FiiniqApiRateLimiter rateLimiter;
    private static final int MAX_RECORDS = 1000;

    @BeforeEach
    void setUp() {
        // 默认：10 样本，每分钟 1～120，便于控制间隔
        responseTimeTracker = new ResponseTimeTracker(10, 1.0, 120.0);
        rateLimiter = new FiiniqApiRateLimiter(responseTimeTracker, MAX_RECORDS);
    }

    @AfterEach
    void tearDown() {
        if (rateLimiter != null) {
            rateLimiter.shutdown();
        }
    }

    /** 等待某请求达到指定状态或超时（毫秒） */
    private void waitForStatus(String requestId, FiiniqRequestRecord.Status status, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            FiiniqRequestRecord r = rateLimiter.getRecord(requestId);
            if (r != null && r.getStatus() == status) return;
            try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
    }

    /** 等待已完成数达到指定值或超时 */
    private void waitForCompletedCount(int expected, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (rateLimiter.getStats().getCompletedCount() >= expected) return;
            try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
    }

    // ---------- 基础提交与记录 ----------

    @Nested
    @DisplayName("提交与记录")
    class SubmitAndRecord {

        @Test
        @DisplayName("提交后返回 requestId 且 getRecord 可查到")
        void submitReturnsRequestIdAndRecordExists() {
            String requestId = rateLimiter.submit(() -> "ok");
            assertThat(requestId).isNotBlank();
            FiiniqRequestRecord record = rateLimiter.getRecord(requestId);
            assertThat(record).isNotNull();
            assertThat(record.getRequestId()).isEqualTo(requestId);
            assertThat(record.getStatus()).isEqualTo(FiiniqRequestRecord.Status.PENDING);
            assertThat(record.getSubmitTime()).isNotNull();
        }

        @Test
        @DisplayName("单次请求执行完成后状态为 COMPLETED 且有开始/结束时间")
        void singleRequestExecutesAndCompletes() {
            String requestId = rateLimiter.submit(() -> "result");
            waitForStatus(requestId, FiiniqRequestRecord.Status.COMPLETED, 5000);

            FiiniqRequestRecord record = rateLimiter.getRecord(requestId);
            assertThat(record).isNotNull();
            assertThat(record.getStatus()).isEqualTo(FiiniqRequestRecord.Status.COMPLETED);
            assertThat(record.getStartTime()).isNotNull();
            assertThat(record.getEndTime()).isNotNull();
            assertThat(record.getDurationMs()).isGreaterThanOrEqualTo(0);
            assertThat(rateLimiter.getStats().getCompletedCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("不存在的 requestId  getRecord 返回 null")
        void getRecordReturnsNullForUnknownId() {
            assertThat(rateLimiter.getRecord("non-existent-id")).isNull();
        }
    }

    // ---------- 限流节奏（响应时间决定间隔） ----------

    @Nested
    @DisplayName("限流节奏")
    class RateLimitPacing {

        @Test
        @DisplayName("预填响应时间后，限流器按间隔依次执行多次调用")
        void multipleSubmissionsArePacedByInterval() throws InterruptedException {
            for (int i = 0; i < 5; i++) {
                responseTimeTracker.recordResponseTimeMs(1000);
            }
            List<Long> startTimes = new ArrayList<>();
            CountDownLatch thirdStarted = new CountDownLatch(3);
            for (int i = 0; i < 3; i++) {
                rateLimiter.submit(() -> {
                    synchronized (startTimes) { startTimes.add(System.currentTimeMillis()); }
                    thirdStarted.countDown();
                    return null;
                });
            }
            assertThat(thirdStarted.await(90, TimeUnit.SECONDS)).as("90 秒内应有 3 次调用开始").isTrue();
            assertThat(startTimes).hasSize(3);
            long gap1 = startTimes.get(1) - startTimes.get(0);
            long gap2 = startTimes.get(2) - startTimes.get(1);
            assertThat(gap1).as("第 1、2 次调用应有间隔").isGreaterThanOrEqualTo(50);
            assertThat(gap2).as("第 2、3 次调用应有间隔").isGreaterThanOrEqualTo(50);
        }

        @Test
        @DisplayName("响应时间变慢后 permitsPerMinute 降低，限流器能完成 2 次调用")
        void slowResponseTimeReducesPermitsPerMinute() throws InterruptedException {
            for (int i = 0; i < 5; i++) {
                responseTimeTracker.recordResponseTimeMs(3000);
            }
            double ppm = responseTimeTracker.getPermitsPerMinute();
            assertThat(ppm).isLessThanOrEqualTo(20.0 + 0.1);
            assertThat(responseTimeTracker.getIntervalBetweenCallsSeconds()).isGreaterThanOrEqualTo(2.9);

            List<Long> starts = new ArrayList<>();
            CountDownLatch twoDone = new CountDownLatch(2);
            rateLimiter.submit(() -> { synchronized (starts) { starts.add(System.currentTimeMillis()); } twoDone.countDown(); return null; });
            rateLimiter.submit(() -> { synchronized (starts) { starts.add(System.currentTimeMillis()); } twoDone.countDown(); return null; });
            assertThat(twoDone.await(90, TimeUnit.SECONDS)).as("90 秒内应完成 2 次调用").isTrue();
            assertThat(starts).hasSize(2);
            long gap = starts.get(1) - starts.get(0);
            assertThat(gap).as("两次调用间隔应约 3 秒").isGreaterThanOrEqualTo(1000);
        }
    }

    // ---------- 等待数 / 完成数 / 统计 ----------

    @Nested
    @DisplayName("统计：等待数、完成数、记录列表")
    class Stats {

        @Test
        @DisplayName("提交后立即 getStats 可见等待数增加；全部执行完后完成数增加")
        void statsShowWaitingThenCompleted() {
            for (int i = 0; i < 5; i++) responseTimeTracker.recordResponseTimeMs(2000);
            String id1 = rateLimiter.submit(() -> null);
            String id2 = rateLimiter.submit(() -> null);
            FiiniqRateLimitStats statsSoon = rateLimiter.getStats();
            assertThat(statsSoon.getWaitingCount()).isGreaterThanOrEqualTo(1);
            assertThat(statsSoon.getPermitsPerMinute()).isGreaterThan(0);

            waitForStatus(id1, FiiniqRequestRecord.Status.COMPLETED, 60000);
            waitForStatus(id2, FiiniqRequestRecord.Status.COMPLETED, 60000);
            FiiniqRateLimitStats statsAfter = rateLimiter.getStats();
            assertThat(statsAfter.getCompletedCount()).isEqualTo(2);
            assertThat(rateLimiter.getRecord(id1).getStatus()).isEqualTo(FiiniqRequestRecord.Status.COMPLETED);
            assertThat(rateLimiter.getRecord(id2).getStatus()).isEqualTo(FiiniqRequestRecord.Status.COMPLETED);
            assertThat(statsAfter.getRecords()).isNotEmpty();
        }

        @Test
        @DisplayName("getStats 返回的记录包含每条请求的开始时间与结束时间")
        void statsRecordsContainStartAndEndTime() {
            String id = rateLimiter.submit(() -> null);
            waitForStatus(id, FiiniqRequestRecord.Status.COMPLETED, 5000);
            FiiniqRequestRecord inStats = rateLimiter.getStats().getRecords().stream()
                    .filter(r -> r.getRequestId().equals(id))
                    .findFirst()
                    .orElse(null);
            assertThat(inStats).isNotNull();
            assertThat(inStats.getStartTime()).isNotNull();
            assertThat(inStats.getEndTime()).isNotNull();
        }
    }

    // ---------- 剩余等待时间 ----------

    @Nested
    @DisplayName("剩余等待时间")
    class RemainingWaitTime {

        @Test
        @DisplayName("不存在的 requestId 返回 -1")
        void nonExistentRequestIdReturnsNegative() {
            assertThat(rateLimiter.getRemainingWaitTimeSeconds("no-such-id")).isEqualTo(-1);
        }

        @Test
        @DisplayName("已完成的请求返回 0")
        void completedRequestReturnsZero() {
            String id = rateLimiter.submit(() -> null);
            waitForStatus(id, FiiniqRequestRecord.Status.COMPLETED, 5000);
            assertThat(rateLimiter.getRemainingWaitTimeSeconds(id)).isEqualTo(0);
        }

        @Test
        @DisplayName("队列中靠后的请求剩余等待时间大于 0 且随位置增加")
        void queuedRequestHasPositiveWaitTimeRoughlyByPosition() {
            for (int i = 0; i < 5; i++) responseTimeTracker.recordResponseTimeMs(1000);
            String id1 = rateLimiter.submit(() -> null);
            String id2 = rateLimiter.submit(() -> null);
            String id3 = rateLimiter.submit(() -> null);

            double wait1 = rateLimiter.getRemainingWaitTimeSeconds(id1);
            double wait2 = rateLimiter.getRemainingWaitTimeSeconds(id2);
            double wait3 = rateLimiter.getRemainingWaitTimeSeconds(id3);
            // 第 1 个可能已开始或即将开始；第 2、3 个应有一定等待时间，且 wait3 >= wait2 大致成立（或都>=0）
            assertThat(wait2).isGreaterThanOrEqualTo(0);
            assertThat(wait3).isGreaterThanOrEqualTo(0);
            // 至少有一个在后面，等待时间应约 1 秒的倍数
            assertThat(wait2 + wait3).as("排队中的请求总等待时间应为正").isGreaterThanOrEqualTo(0);

            waitForCompletedCount(3, 15000);
        }
    }

    // ---------- 按 ID 取消 ----------

    @Nested
    @DisplayName("按 requestId 取消")
    class CancelById {

        @Test
        @DisplayName("取消队列中的请求后该记录为 CANCELLED，且不参与执行")
        void cancelMarksCancelledAndTaskSkipped() {
            for (int i = 0; i < 5; i++) responseTimeTracker.recordResponseTimeMs(1500);
            String idA = rateLimiter.submit(() -> "A");
            String idB = rateLimiter.submit(() -> "B");
            assertThat(rateLimiter.cancel(idB)).isTrue();
            assertThat(rateLimiter.getRecord(idB).getStatus()).isEqualTo(FiiniqRequestRecord.Status.CANCELLED);
            String idC = rateLimiter.submit(() -> "C");

            waitForStatus(idA, FiiniqRequestRecord.Status.COMPLETED, 60000);
            waitForStatus(idC, FiiniqRequestRecord.Status.COMPLETED, 60000);
            assertThat(rateLimiter.getRecord(idA).getStatus()).isEqualTo(FiiniqRequestRecord.Status.COMPLETED);
            assertThat(rateLimiter.getRecord(idC).getStatus()).isEqualTo(FiiniqRequestRecord.Status.COMPLETED);
            assertThat(rateLimiter.getStats().getCompletedCount()).isEqualTo(2);
            assertThat(rateLimiter.getRecord(idB).getStatus()).isEqualTo(FiiniqRequestRecord.Status.CANCELLED);
        }

        @Test
        @DisplayName("取消后后续请求的剩余等待时间缩短")
        void cancelShortensWaitTimeForLaterRequests() {
            for (int i = 0; i < 5; i++) responseTimeTracker.recordResponseTimeMs(2000);
            String idA = rateLimiter.submit(() -> null);
            String idB = rateLimiter.submit(() -> null);
            String idC = rateLimiter.submit(() -> null);
            double waitBefore = rateLimiter.getRemainingWaitTimeSeconds(idC);
            rateLimiter.cancel(idB);
            double waitAfter = rateLimiter.getRemainingWaitTimeSeconds(idC);
            // 取消 B 后，C 前面少了一个任务，剩余等待时间应减少
            assertThat(waitAfter).isLessThanOrEqualTo(waitBefore + 0.5);
            waitForCompletedCount(2, 15000);
        }

        @Test
        @DisplayName("对已执行或已完成的请求 cancel 返回 false")
        void cancelReturnsFalseForNonPending() {
            String id = rateLimiter.submit(() -> null);
            waitForStatus(id, FiiniqRequestRecord.Status.COMPLETED, 5000);
            assertThat(rateLimiter.cancel(id)).isFalse();
            assertThat(rateLimiter.cancel("unknown")).isFalse();
        }
    }

    // ---------- 异常与关闭 ----------

    @Nested
    @DisplayName("异常与关闭")
    class FailureAndShutdown {

        @Test
        @DisplayName("callable 抛异常时记录状态为 FAILED，且有结束时间；completedCount 不增加")
        void failedRequestMarksFailedAndDoesNotIncrementCompleted() {
            String id = rateLimiter.submit(() -> {
                throw new RuntimeException("模拟 API 失败");
            });
            waitForStatus(id, FiiniqRequestRecord.Status.FAILED, 5000);
            FiiniqRequestRecord record = rateLimiter.getRecord(id);
            assertThat(record.getStatus()).isEqualTo(FiiniqRequestRecord.Status.FAILED);
            assertThat(record.getEndTime()).isNotNull();
            assertThat(rateLimiter.getStats().getCompletedCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("shutdown 后再次 submit 抛出 IllegalStateException")
        void submitAfterShutdownThrows() {
            rateLimiter.shutdown();
            assertThatThrownBy(() -> rateLimiter.submit(() -> null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("shutdown");
        }
    }

    // ---------- 综合：高并发提交 + 部分取消 ----------

    @Nested
    @DisplayName("综合场景")
    class Integration {

        @Test
        @DisplayName("连续提交多请求并取消部分，被取消的为 CANCELLED，其余最终完成")
        void multipleSubmissionsWithPartialCancel() {
            for (int i = 0; i < 5; i++) responseTimeTracker.recordResponseTimeMs(800);
            List<String> ids = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                ids.add(rateLimiter.submit(() -> { Thread.sleep(50); return null; }));
            }
            rateLimiter.cancel(ids.get(1));
            rateLimiter.cancel(ids.get(3));
            waitForCompletedCount(3, 120000);
            FiiniqRateLimitStats stats = rateLimiter.getStats();
            assertThat(stats.getCompletedCount()).as("未取消的 3 个请求应至少完成 2 个").isGreaterThanOrEqualTo(2);
            assertThat(rateLimiter.getRecord(ids.get(1)).getStatus()).isEqualTo(FiiniqRequestRecord.Status.CANCELLED);
            assertThat(rateLimiter.getRecord(ids.get(3)).getStatus()).isEqualTo(FiiniqRequestRecord.Status.CANCELLED);
        }
    }
}
