package com.enterprise.copilot.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.enterprise.copilot.common.Result;
import com.enterprise.copilot.entity.BizOrder;
import com.enterprise.copilot.mapper.BizOrderMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/health")
@RequiredArgsConstructor
public class HealthController {
    private static final List<String> SAMPLE_ORDER_NUMBERS = List.of("ORD-2026001", "ORD-2026002", "ORD-2026003");
    private static final String REDIS_PING_KEY = "copilot:health:ping";

    private final BizOrderMapper bizOrderMapper;
    private final StringRedisTemplate redisTemplate;

    @GetMapping("/check")
    public Result<Map<String, Object>> checkInfrastructure() {
        long orderCount = bizOrderMapper.selectCount(null);
        List<BizOrder> sampleOrders = bizOrderMapper.selectPage(
                new Page<>(1, SAMPLE_ORDER_NUMBERS.size(), false),
                Wrappers.<BizOrder>lambdaQuery()
                        .in(BizOrder::getOrderNo, SAMPLE_ORDER_NUMBERS)
                        .orderByAsc(BizOrder::getOrderNo)).getRecords();

        redisTemplate.opsForValue().set(REDIS_PING_KEY, "OK", Duration.ofMinutes(5));
        String redisStatus = redisTemplate.opsForValue().get(REDIS_PING_KEY);
        if (!"OK".equals(redisStatus)) {
            throw new IllegalStateException("Redis 健康检查读回的值不匹配");
        }

        return Result.success(Map.of(
                "service", "UP",
                "mysqlOrderCount", orderCount,
                "sampleOrders", sampleOrders,
                "redisStatus", redisStatus));
    }
}
