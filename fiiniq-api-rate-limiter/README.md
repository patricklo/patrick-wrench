# fiiniq-api-rate-limiter

基于 Finiiq API 调用返回时间动态计算每分钟可调用次数，并支持请求 ID、排队/完成统计、剩余等待时间查询与按 ID 取消。

## 功能

1. **动态限流**：根据最近 API 响应时间计算每分钟允许的调用次数（`permitsPerMinute = 60 / avgResponseTimeSeconds`），并限制在配置的上下界内。
2. **请求追踪**：为每次调用生成唯一请求 ID，记录所有请求；可查看正在等待数、已完成数、每个请求的实际开始/结束时间；支持按请求 ID 查询剩余等待时间。
3. **取消请求**：支持按请求 ID 取消队列中尚未开始的请求，缩短后续请求等待时间。

## 使用

### 依赖

```xml
<dependency>
    <groupId>com.patrick.wrench</groupId>
    <artifactId>fiiniq-api-rate-limiter</artifactId>
    <version>3.0.2</version>
</dependency>
```

### 配置（可选）

```yaml
fiiniq:
  api:
    rate-limit:
      enabled: true
      response-time-samples: 50   # 参与平均的最近响应次数
      min-permits-per-minute: 1
      max-permits-per-minute: 120
      max-records: 10000          # 内存中保留的最大请求记录数
```

### 代码示例

```java
@Autowired
private FiiniqApiRateLimiter fiiniqApiRateLimiter;

// 提交一次 Finiiq API 调用，得到 requestId
String requestId = fiiniqApiRateLimiter.submit(() -> {
    return yourFiniiqClient.call(...);
});

// 查询剩余等待时间（秒）
double waitSec = fiiniqApiRateLimiter.getRemainingWaitTimeSeconds(requestId);

// 查看统计：等待数、完成数、每分钟许可数、最近请求记录
FiiniqRateLimitStats stats = fiiniqApiRateLimiter.getStats();
int waiting = stats.getWaitingCount();
int completed = stats.getCompletedCount();
List<FiiniqRequestRecord> records = stats.getRecords();
// 每条 record 含 requestId, status, submitTime, startTime, endTime, getDurationMs()

// 按 requestId 取消队列中的请求
fiiniqApiRateLimiter.cancel(requestId);
```

## 说明

- 实际执行为单线程顺序执行，每次调用结束后根据当前平均响应时间决定下一次调用的间隔，从而在“每分钟调用次数”上逼近动态计算出的限流值。
- 无历史响应数据时，按 1 秒/次估算，再根据 `minPermitsPerMinute` / `maxPermitsPerMinute` 限制范围。
