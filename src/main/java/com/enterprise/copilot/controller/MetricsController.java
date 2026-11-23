package com.enterprise.copilot.controller;

import com.enterprise.copilot.common.Result;
import com.enterprise.copilot.mapper.ChatAuditLogMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/api/metrics")
@RequiredArgsConstructor
public class MetricsController {

    private final ChatAuditLogMapper auditLogs;

    @GetMapping("/summary")
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Result<MetricsSummary> getObservabilitySummary() {
        var totals = auditLogs.selectMetricsTotals();
        var distribution = new LinkedHashMap<String, Long>();
        auditLogs.selectTopTools().forEach(tool -> distribution.put(tool.toolName(), tool.requests()));
        double rate = totals.totalRequests() == 0 ? 0.0
                : 100.0 * totals.toolTriggeredRequests() / totals.totalRequests();
        return Result.success(new MetricsSummary(totals.totalRequests(), totals.totalSessions(), totals.avgLatencyMs(),
                totals.toolTriggeredRequests(), String.format(Locale.ROOT, "%.1f%%", rate), distribution));
    }

    public record MetricsSummary(long totalRequests, long totalSessions, long avgLatencyMs,
                                 long toolTriggeredRequests, String toolInvocationRate,
                                 Map<String, Long> toolDistribution) {
    }
}
