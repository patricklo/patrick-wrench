package com.patrick.wrench.fiiniq.api.ratelimit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ResponseTimeTracker 单元测试：验证根据响应时间推算每分钟许可数。
 */
@DisplayName("ResponseTimeTracker 单元测试")
class ResponseTimeTrackerTest {

    @Test
    @DisplayName("无样本时平均响应时间 1 秒，permitsPerMinute = 60/1 = 60")
    void noSamplesUsesDefault() {
        ResponseTimeTracker t = new ResponseTimeTracker(10, 1.0, 120.0);
        assertThat(t.getAverageResponseTimeSeconds()).isEqualTo(1.0);
        assertThat(t.getPermitsPerMinute()).isEqualTo(60.0);
        assertThat(t.getIntervalBetweenCallsSeconds()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("记录 1 秒响应后 permitsPerMinute = 60")
    void oneSecondResponseGives60PerMinute() {
        ResponseTimeTracker t = new ResponseTimeTracker(10, 1.0, 120.0);
        t.recordResponseTimeMs(1000);
        assertThat(t.getAverageResponseTimeSeconds()).isEqualTo(1.0);
        assertThat(t.getPermitsPerMinute()).isEqualTo(60.0);
    }

    @Test
    @DisplayName("记录 3 秒响应后 permitsPerMinute = 20，且被下限截断")
    void threeSecondResponseGives20PerMinute() {
        ResponseTimeTracker t = new ResponseTimeTracker(10, 1.0, 120.0);
        for (int i = 0; i < 5; i++) {
            t.recordResponseTimeMs(3000);
        }
        assertThat(t.getAverageResponseTimeSeconds()).isEqualTo(3.0);
        assertThat(t.getPermitsPerMinute()).isEqualTo(20.0);
        assertThat(t.getIntervalBetweenCallsSeconds()).isEqualTo(3.0);
    }

    @Test
    @DisplayName("样本数超过 maxSamples 时只保留最近一批")
    void evictsOldSamples() {
        ResponseTimeTracker t = new ResponseTimeTracker(3, 1.0, 120.0);
        t.recordResponseTimeMs(10000);
        t.recordResponseTimeMs(10000);
        t.recordResponseTimeMs(10000);
        assertThat(t.getSampleCount()).isEqualTo(3);
        assertThat(t.getAverageResponseTimeSeconds()).isEqualTo(10.0);
        t.recordResponseTimeMs(1000);
        assertThat(t.getSampleCount()).isEqualTo(3);
        t.recordResponseTimeMs(1000);
        t.recordResponseTimeMs(1000);
        assertThat(t.getSampleCount()).isLessThanOrEqualTo(3);
        double avg = t.getAverageResponseTimeSeconds();
        assertThat(avg).isLessThan(10.0);
    }
}
