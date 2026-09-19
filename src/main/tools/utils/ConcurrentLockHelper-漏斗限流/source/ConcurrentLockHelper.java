package com.jst.aftersale.address.helper;

import com.jst.aftersale.address.config.AfterSaleReturnAddressConfiguration;
import com.jst.aftersale.address.constant.AddressConstant;
import com.jst.aftersale.address.exception.FrequencyLimitedException;
import com.jst.aftersale.common.constant.Constant;
import com.jst.aftersale.common.exception.LockNotAcquiredException;
import com.jst.base.exception.BaseException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * @description: 并发锁帮助类
 * @program: dis-aftersale-service
 * @author: 疆戟
 * @create: 2024-12-30 15:38
 */
@Component
@Slf4j
public final class ConcurrentLockHelper {

    private static final List<Object> FUNNEL_STATE_HAS_KEYS = Arrays.asList(
            FunnelState.CAPACITY_KEY, FunnelState.LEAK_RATE_KEY,
            FunnelState.LEFT_CAPACITY_KEY, FunnelState.NEXT_LEAK_TIME_KEY);

    private final StringRedisTemplate stringRedisTemplate;
    private final RedisTemplate<String, Object> lockRedisTemplate;
    private final AfterSaleReturnAddressConfiguration afterSaleReturnAddressConfiguration;

    public ConcurrentLockHelper(StringRedisTemplate stringRedisTemplate,
                                RedisTemplate<String, Object> lockRedisTemplate,
                                AfterSaleReturnAddressConfiguration afterSaleReturnAddressConfiguration) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.lockRedisTemplate = lockRedisTemplate;
        this.afterSaleReturnAddressConfiguration = afterSaleReturnAddressConfiguration;
    }

    public <T> T tryLock(String key, Supplier<T> supplier, String... noticeMsg) {
        return tryLock(key, true, supplier, noticeMsg);
    }

    public <T> T tryLock(String key, boolean isNeedLock, Supplier<T> supplier, String... noticeMsg) {
        return tryLock(key, isNeedLock, Constant.FIVE, TimeUnit.MINUTES, supplier, noticeMsg);
    }

    public <T> T tryLock(String key, boolean isNeedLock, long timeout, TimeUnit unit, Supplier<T> supplier, String... noticeMsg) {
        if (!isNeedLock) {
            return supplier.get();
        }
        if (BooleanUtils.isTrue(stringRedisTemplate.opsForValue().setIfAbsent(key, Constant.ONE_STR, timeout, unit))) {
            try {
                return supplier.get();
            } finally {
                stringRedisTemplate.delete(key);
            }
        }
        if (noticeMsg.length > 0) {
            throw new BaseException(noticeMsg[0]);
        }
        throw new BaseException("其他用户正在操作，请稍后刷新重试!");
    }

    public <T> T tryLockWithSpin(String lockKey, long expireMillis, int maxRetries, Supplier<T> supplier) throws LockNotAcquiredException {
        int retryCount = 0;
        while (retryCount < maxRetries) {
            if (BooleanUtils.isTrue(stringRedisTemplate.opsForValue()
                    .setIfAbsent(lockKey, Constant.ONE_STR, expireMillis, TimeUnit.MILLISECONDS))) {
                try {
                    return supplier.get();
                } catch (Exception e) {
                    log.error("执行方法异常", e);
                    throw e;
                } finally {
                    stringRedisTemplate.delete(lockKey);
                }
            }
            try {
                Integer concurrentLockSpinTime = afterSaleReturnAddressConfiguration.getConcurrentLockSpinTime();
                if (Objects.isNull(concurrentLockSpinTime) || concurrentLockSpinTime <= 0) {
                    concurrentLockSpinTime = Constant.ONE_HUNDRED;
                }
                Thread.sleep((long) (Math.pow(Constant.TWO, retryCount) * concurrentLockSpinTime));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            retryCount++;
        }
        throw new LockNotAcquiredException("其他用户正在操作，请稍后刷新重试!");
    }

    /**
     * 判断是否允许请求（漏斗算法限流）
     */
    public boolean isAllowed(String key, int capacity, int leakRatePerSecond, int expireSeconds) {
        AtomicReference<FunnelState> stateRef = new AtomicReference<>(getFunnelState(key));
        long now = System.currentTimeMillis();
        FunnelState state = stateRef.get();
        if (Objects.isNull(state)) {
            state = FunnelState.build(capacity, leakRatePerSecond, capacity, now);
            saveFunnelState(key, state, expireSeconds);
            return true;
        }

        long timePassedMillis = now - state.getNextLeakTime();
        if (timePassedMillis > Constant.ZERO) {
            int tokensToAdd = (int) ((timePassedMillis * state.getLeakRate()) / 1000L);
            int newLeftCapacity = Math.min(state.getCapacity(), state.getLeftCapacity() + tokensToAdd);
            state.setLeftCapacity(newLeftCapacity);
            state.setNextLeakTime(now);
            saveFunnelState(key, state, expireSeconds);
        }

        if (state.getLeftCapacity() <= Constant.ZERO) {
            return false;
        }

        state.setLeftCapacity(state.getLeftCapacity() - Constant.ONE);
        saveFunnelState(key, state, expireSeconds);
        return true;
    }

    private FunnelState getFunnelState(String key) {
        List<Object> objects = lockRedisTemplate.opsForHash().multiGet(key, FUNNEL_STATE_HAS_KEYS);
        if (CollectionUtils.isEmpty(objects) || objects.size() != FUNNEL_STATE_HAS_KEYS.size()) {
            return null;
        }
        Map<String, Object> entries = new HashMap<>(Constant.DEFAULT_MAP_SIZE);
        for (int i = Constant.ZERO, size = FUNNEL_STATE_HAS_KEYS.size(); i < size; i++) {
            Object o = objects.get(i);
            if (Objects.isNull(o)) {
                continue;
            }
            entries.put((String) FUNNEL_STATE_HAS_KEYS.get(i), objects.get(i));
        }
        if (CollectionUtils.isEmpty(objects)) {
            return null;
        }
        Integer capacity = (Integer) entries.get(FunnelState.CAPACITY_KEY);
        Integer leakRate = (Integer) entries.get(FunnelState.LEAK_RATE_KEY);
        Integer leftCapacity = (Integer) entries.get(FunnelState.LEFT_CAPACITY_KEY);
        Long nextLeakTime = (Long) entries.get(FunnelState.NEXT_LEAK_TIME_KEY);
        return FunnelState.build(capacity, leakRate, leftCapacity, nextLeakTime);
    }

    private void saveFunnelState(String key, FunnelState funnelState, int expireSeconds) {
        lockRedisTemplate.executePipelined((RedisCallback<Void>) connection -> {
            lockRedisTemplate.opsForHash().put(key, FunnelState.CAPACITY_KEY, funnelState.getCapacity());
            lockRedisTemplate.opsForHash().put(key, FunnelState.LEAK_RATE_KEY, funnelState.getLeakRate());
            lockRedisTemplate.opsForHash().put(key, FunnelState.LEFT_CAPACITY_KEY, funnelState.getLeftCapacity());
            lockRedisTemplate.opsForHash().put(key, FunnelState.NEXT_LEAK_TIME_KEY, funnelState.getNextLeakTime());
            lockRedisTemplate.expire(key, expireSeconds, TimeUnit.SECONDS);
            return null;
        });
    }

    public <T> T executeWithRateLimitAndRetry(String rateLimitKey, int capacity, int leakRatePerSecond,
                                               int expireSeconds, int maxRetryCount, int waitTimeMillis,
                                               Supplier<T> supplier) {
        int retryCount = Constant.ZERO;
        while (retryCount < maxRetryCount) {
            try {
                if (!isAllowed(rateLimitKey, capacity, leakRatePerSecond, expireSeconds)) {
                    try {
                        log.info("executeWithRateLimitAndRetry-key{} 访问频率限制，等待后重试，第{}次", rateLimitKey, retryCount);
                        Thread.sleep(waitTimeMillis);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new BaseException("限流等待被中断", e);
                    }
                    continue;
                }
                return supplier.get();
            } catch (BaseException e) {
                if (isFrequencyLimitedException(e)) {
                    retryCount++;
                    if (retryCount >= maxRetryCount) {
                        throw new FrequencyLimitedException(e.getMessage());
                    }
                    long waitTime = (long) retryCount * Constant.ONE_THOUSAND;
                    try {
                        Thread.sleep(waitTime);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new BaseException("重试被中断");
                    }
                } else {
                    throw e;
                }
            }
        }
        throw new BaseException("executeWithRateLimitAndRetry 调用失败，超过最大重试次数");
    }

    private boolean isFrequencyLimitedException(BaseException e) {
        if (e == null || StringUtils.isBlank(e.getMessage())) {
            return false;
        }
        return StringUtils.isNotBlank(e.getCode()) && e.getCode().equals(AddressConstant.DOUFrequencyLimitedCODE)
                && StringUtils.isNotBlank(e.getMessage()) && e.getMessage().contains(AddressConstant.DOUFrequencyLimitedMSG1);
    }

    @Data
    public static class FunnelState {
        private static final String CAPACITY_KEY = "capacity";
        private static final String LEAK_RATE_KEY = "leak_rate";
        private static final String LEFT_CAPACITY_KEY = "left_capacity";
        private static final String NEXT_LEAK_TIME_KEY = "next_leak_time";

        private int capacity;
        private int leakRate;
        private int leftCapacity;
        private long nextLeakTime;

        public static FunnelState build(Integer capacity, Integer leakRate, Integer leftCapacity, Long nextLeakTime) {
            if (Objects.isNull(capacity) || Objects.isNull(leakRate) || Objects.isNull(leftCapacity) || Objects.isNull(nextLeakTime)) {
                return null;
            }
            FunnelState state = new FunnelState();
            state.capacity = capacity;
            state.leakRate = leakRate;
            state.leftCapacity = leftCapacity;
            state.nextLeakTime = nextLeakTime;
            return state;
        }
    }
}
