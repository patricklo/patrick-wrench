package com.patrick.wrench.fiiniq.api.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Finiiq API 限流器配置项。
 * <p>
 * 对应配置文件中的 {@code fiiniq.api.rate-limit.*}，用于控制响应时间采样数量、
 * 每分钟许可数上下限、内存中保留的请求记录数以及是否启用限流等。
 * </p>
 */
@ConfigurationProperties(prefix = "fiiniq.api.rate-limit")
public class FiiniqApiRateLimitProperties {

    /** 参与平均的最近响应时间样本数量，默认 50 */
    private int responseTimeSamples = 50;
    /** 每分钟许可数下限（算出的值小于此时取该值），默认 1 */
    private double minPermitsPerMinute = 1.0;
    /** 每分钟许可数上限（算出的值大于此时取该值），默认 120 */
    private double maxPermitsPerMinute = 120.0;
    /** 内存中保留的最大请求记录数，超过后会淘汰最早的一批，默认 10000 */
    private int maxRecords = 10_000;
    /** 是否启用限流器，默认 true；为 false 时自动配置不注册 Bean */
    private boolean enabled = true;

    public int getResponseTimeSamples() { return responseTimeSamples; }
    public void setResponseTimeSamples(int responseTimeSamples) { this.responseTimeSamples = responseTimeSamples; }
    public double getMinPermitsPerMinute() { return minPermitsPerMinute; }
    public void setMinPermitsPerMinute(double minPermitsPerMinute) { this.minPermitsPerMinute = minPermitsPerMinute; }
    public double getMaxPermitsPerMinute() { return maxPermitsPerMinute; }
    public void setMaxPermitsPerMinute(double maxPermitsPerMinute) { this.maxPermitsPerMinute = maxPermitsPerMinute; }
    public int getMaxRecords() { return maxRecords; }
    public void setMaxRecords(int maxRecords) { this.maxRecords = maxRecords; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}
