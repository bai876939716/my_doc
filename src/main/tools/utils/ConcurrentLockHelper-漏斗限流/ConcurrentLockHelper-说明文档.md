# ConcurrentLockHelper 并发锁与漏斗限流工具类

## 1. 概述

`ConcurrentLockHelper` 是 `dis-aftersale-service` 中的并发控制工具类，提供**分布式锁**和**漏斗算法限流**两大核心能力。基于 Redis 实现，支持分布式场景下的互斥访问控制和请求频率限制。

**所属模块：** `com.jst.aftersale.address.helper`

**核心能力：**

| 能力 | 方法 | 说明 |
|------|------|------|
| 简单分布式锁 | `tryLock` | SETNX + 自动释放，执行期间互斥 |
| 自旋分布式锁 | `tryLockWithSpin` | 指数退避重试获取锁 |
| 漏斗限流判断 | `isAllowed` | 基于漏斗算法判断请求是否放行 |
| 限流+重试执行 | `executeWithRateLimitAndRetry` | 限流 + 频率异常自动重试 |
| 频率锁 | `applyRedisFrequencyLock` | 基于漏斗限流的互斥执行 |

---

## 2. 漏斗算法原理

### 2.1 算法模型

漏斗算法（Funnel/Leaky Bucket）将请求比作水流注入漏斗：

```
        ┌─────────────┐
        │  请求流入    │  ← 不定速率
        └──────┬──────┘
               ▼
        ┌─────────────┐
        │             │  ← capacity（漏斗容量）
        │   漏  斗    │
        │             │
        └──────┬──────┘
               ▼
        ┌─────────────┐
        │  匀速漏出    │  ← leakRatePerSecond（漏出速率）
        └─────────────┘
```

- **capacity**：漏斗最大容量（令牌上限）
- **leakRatePerSecond**：每秒漏出（补充）的令牌数
- **leftCapacity**：当前剩余令牌数
- **nextLeakTime**：上次计算令牌补充的时间戳

### 2.2 工作流程

1. 请求到达 → 从 Redis 读取漏斗状态
2. 首次请求 → 初始化漏斗（满容量），直接放行
3. 非首次 → 根据时间差计算应补充的令牌数
4. 判断剩余令牌 > 0 → 消耗一个令牌，放行
5. 剩余令牌 ≤ 0 → 拒绝请求

### 2.3 令牌补充公式

```
tokensToAdd = (timePassedMillis × leakRate) / 1000
newLeftCapacity = min(capacity, leftCapacity + tokensToAdd)
```

**示例：** 漏出速率 10/s，距上次 500ms，应补充 5 个令牌。

---

## 3. 方法详解

### 3.1 isAllowed — 漏斗限流判断

```java
public boolean isAllowed(String key, int capacity, int leakRatePerSecond, int expireSeconds)
```

| 参数 | 说明 |
|------|------|
| key | Redis 限流 Key（标识限流维度） |
| capacity | 漏斗容量（最大令牌数） |
| leakRatePerSecond | 每秒漏出速率 |
| expireSeconds | Redis Key 过期时间（秒） |
| **返回** | `true` 放行 / `false` 拒绝 |

**存储结构：** Redis Hash

```
key → {
    "capacity": 10,
    "leak_rate": 5,
    "left_capacity": 3,
    "next_leak_time": 1700000000000
}
```

### 3.2 tryLock — 简单分布式锁

```java
public <T> T tryLock(String key, Supplier<T> supplier, String... noticeMsg)
public <T> T tryLock(String key, boolean isNeedLock, long timeout, TimeUnit unit, Supplier<T> supplier, String... noticeMsg)
```

- 使用 `SETNX` 获取锁，执行完毕 `finally` 释放
- 获取失败抛出 `BaseException`
- 默认锁超时 5 分钟

### 3.3 tryLockWithSpin — 自旋分布式锁

```java
public <T> T tryLockWithSpin(String lockKey, long expireMillis, int maxRetries, Supplier<T> supplier)
```

- 获取失败后指数退避重试：`sleep(2^retryCount × spinTime)`
- 超过最大重试次数抛出 `LockNotAcquiredException`

### 3.4 executeWithRateLimitAndRetry — 限流+重试执行

```java
public <T> T executeWithRateLimitAndRetry(
    String rateLimitKey, int capacity, int leakRatePerSecond,
    int expireSeconds, int maxRetryCount, int waitTimeMillis,
    Supplier<T> supplier)
```

- 先通过 `isAllowed` 限流检查
- 限流器满 → 等待 `waitTimeMillis` 后重试
- 执行中遇到频率限制异常 → 线性退避重试（1s, 2s, 3s...）
- 超过 `maxRetryCount` 抛出 `FrequencyLimitedException`

---

## 4. 使用示例

### 4.1 接口限流

```java
// 每秒最多 5 次请求，容量 10，Key 过期 60 秒
if (concurrentLockHelper.isAllowed("dis::rate::createAddress:" + coId, 10, 5, 60)) {
    // 执行业务逻辑
} else {
    throw new BaseException("操作过于频繁，请稍后重试");
}
```

### 4.2 互斥操作

```java
// 同一商家同一时间只能有一个操作
concurrentLockHelper.tryLock("dis::lock::shop:" + coId, () -> {
    return shopService.bindAddress(param);
}, "其他用户正在操作，请稍后重试");
```

### 4.3 限流+自动重试调用外部接口

```java
// 调用 ERP 接口，限流 1次/秒，最多重试 5 次
concurrentLockHelper.executeWithRateLimitAndRetry(
    "dis::rate::erp:" + coId,
    1,      // capacity
    1,      // leakRatePerSecond
    60,     // expireSeconds
    5,      // maxRetryCount
    1000,   // waitTimeMillis
    () -> erpClient.createAddress(param)
);
```

---

## 5. 设计要点

| 要点 | 说明 |
|------|------|
| 客户端计算令牌 | 减少 Redis 交互，由客户端根据时间差计算补充量 |
| Redis Pipeline | `saveFunnelState` 使用管道批量写入 4 个 Hash 字段 + 设置过期 |
| 容忍少量超限 | 非原子操作，并发场景下可能少量超限，适用于非精确限流 |
| 自动过期清理 | 通过 `expireSeconds` 自动清理不活跃的限流状态 |
| 指数退避 | 自旋锁使用 `2^n × baseTime` 退避，避免惊群效应 |

---

## 6. 注意事项

1. **非原子性**：`isAllowed` 的读取-计算-写入不是原子操作，高并发下可能少量超限。适用于"容忍少量超限"的场景（如 ERP 接口调用频率控制）
2. **锁粒度**：Key 设计需要合理，粒度太粗影响并发，太细失去限流意义
3. **过期时间**：`expireSeconds` 应大于业务执行时间，避免状态丢失后重新初始化导致限流失效
4. **线程中断**：自旋等待中正确处理 `InterruptedException`，恢复中断标志

---

## 7. 源码

源码位于 [source/](./source/) 目录下。

**原始代码路径：** `dis-aftersale-service/src/main/java/com/jst/aftersale/address/helper/ConcurrentLockHelper.java`
