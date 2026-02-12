package com.patrick.wrench.fiiniq.api.ratelimit.config;

import com.patrick.wrench.fiiniq.api.ratelimit.FiiniqApiRateLimitProperties;
import com.patrick.wrench.fiiniq.api.ratelimit.FiiniqApiRateLimiter;
import com.patrick.wrench.fiiniq.api.ratelimit.ResponseTimeTracker;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Finiiq API 限流器自动配置。
 * <p>
 * 当 {@code fiiniq.api.rate-limit.enabled} 为 true（或未配置）时，注册
 * {@link ResponseTimeTracker} 与 {@link FiiniqApiRateLimiter}；
 * 应用关闭时通过 destroyMethod 调用限流器的 shutdown，停止工作线程。
 * </p>
 */
@Configuration
@EnableConfigurationProperties(FiiniqApiRateLimitProperties.class)
@ConditionalOnProperty(name = "fiiniq.api.rate-limit.enabled", havingValue = "true", matchIfMissing = true)
public class FiiniqApiRateLimiterAutoConfig {

    /** 响应时间追踪器：根据最近调用耗时推算每分钟许可数 */
    @Bean
    public ResponseTimeTracker responseTimeTracker(FiiniqApiRateLimitProperties props) {
        return new ResponseTimeTracker(
                props.getResponseTimeSamples(),
                props.getMinPermitsPerMinute(),
                props.getMaxPermitsPerMinute()
        );
    }

    /** 限流器主 Bean；容器销毁时调用 shutdown 停止调度线程 */
    @Bean(destroyMethod = "shutdown")
    public FiiniqApiRateLimiter fiiniqApiRateLimiter(ResponseTimeTracker responseTimeTracker,
                                                     FiiniqApiRateLimitProperties props) {
        return new FiiniqApiRateLimiter(responseTimeTracker, props.getMaxRecords());
    }
}
