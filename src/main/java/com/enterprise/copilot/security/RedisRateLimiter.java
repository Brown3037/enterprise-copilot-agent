package com.enterprise.copilot.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class RedisRateLimiter {

    private static final String KEY_PREFIX = "copilot:ratelimit:user:";
    private static final int WINDOW_CAPACITY = 15;
    private static final Duration WINDOW = Duration.ofMinutes(1);

    // Redis's clock and the unique members make one shared rolling window across replicas.
    private static final RedisScript<Long> ACQUIRE = RedisScript.of("""
            local time = redis.call('TIME')
            local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
            local capacity = tonumber(ARGV[1])
            local window = tonumber(ARGV[2])
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now - window)
            if redis.call('ZCARD', KEYS[1]) >= capacity then
                local first = redis.call('ZRANGE', KEYS[1], 0, 0, 'WITHSCORES')
                return math.max(1, math.ceil((tonumber(first[2]) + window - now) / 1000))
            end
            redis.call('ZADD', KEYS[1], now, ARGV[3])
            redis.call('PEXPIRE', KEYS[1], window)
            return 0
            """, Long.class);

    private final StringRedisTemplate redisTemplate;

    public void acquire(String userId) {
        Long retryAfter;
        try {
            retryAfter = redisTemplate.execute(ACQUIRE, List.of(KEY_PREFIX + userId),
                    Integer.toString(WINDOW_CAPACITY), Long.toString(WINDOW.toMillis()), UUID.randomUUID().toString());
        } catch (DataAccessException exception) {
            log.warn("Redis 限流检查失败 | 异常类型={}", exception.getClass().getSimpleName());
            throw unavailable();
        }
        if (retryAfter == null || retryAfter < 0L) {
            throw unavailable();
        }
        if (retryAfter > 0L) {
            throw new RateLimitExceededException(retryAfter);
        }
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "请求频率校验暂不可用，请稍后再试。");
    }
}
