package com.enterprise.copilot.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.enterprise.copilot.entity.ChatAuditLog;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface ChatAuditLogMapper extends BaseMapper<ChatAuditLog> {

    @Select("""
            SELECT COUNT(*) AS total_requests,
                   COUNT(DISTINCT CAST(user_id AS BINARY), CAST(session_id AS BINARY)) AS total_sessions,
                   CAST(COALESCE(ROUND(AVG(latency_ms)), 0) AS SIGNED) AS avg_latency_ms,
                   CAST(COALESCE(SUM(CASE WHEN UPPER(TRIM(tools_invoked)) NOT IN ('', 'NONE')
                                         THEN 1 ELSE 0 END), 0) AS SIGNED) AS tool_triggered_requests
            FROM chat_audit_log
            """)
    MetricsTotals selectMetricsTotals();

    @Select("""
            SELECT TRIM(tool.tool_name) AS tool_name, COUNT(DISTINCT audit.id) AS requests
            FROM chat_audit_log AS audit
            CROSS JOIN JSON_TABLE(
                CONCAT('[', REPLACE(JSON_QUOTE(COALESCE(audit.tools_invoked, '')), ',', '","'), ']'),
                '$[*]' COLUMNS (tool_name VARCHAR(255) PATH '$')
            ) AS tool
            WHERE UPPER(TRIM(tool.tool_name)) NOT IN ('', 'NONE')
            GROUP BY TRIM(tool.tool_name)
            ORDER BY requests DESC, tool_name ASC
            LIMIT 10
            """)
    List<ToolFrequency> selectTopTools();

    record MetricsTotals(long totalRequests, long totalSessions, long avgLatencyMs, long toolTriggeredRequests) {
    }

    record ToolFrequency(String toolName, long requests) {
    }
}
