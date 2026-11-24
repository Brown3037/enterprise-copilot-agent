package com.enterprise.copilot.service;

import com.enterprise.copilot.dto.ChatRequest;
import com.enterprise.copilot.entity.ChatAuditLog;
import com.enterprise.copilot.mapper.ChatAuditLogMapper;
import com.enterprise.copilot.security.PromptGuardrailService;
import com.enterprise.copilot.security.PromptGuardrailService.RejectedPromptException;
import com.enterprise.copilot.security.RateLimitExceededException;
import com.enterprise.copilot.security.RedisRateLimiter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class ChatRequestSupport {

    private final ChatAuditLogMapper auditLogs;
    private final RedisRateLimiter rateLimiter;
    private final PromptGuardrailService guardrail;
    private final boolean modelConfigured;

    public ChatRequestSupport(ChatAuditLogMapper auditLogs, RedisRateLimiter rateLimiter,
                              PromptGuardrailService guardrail,
                              @Value("${spring.ai.openai.api-key}") String apiKey) {
        this.auditLogs = auditLogs;
        this.rateLimiter = rateLimiter;
        this.guardrail = guardrail;
        this.modelConfigured = StringUtils.hasText(apiKey) && !"sk-placeholder".equals(apiKey.strip());
    }

    public void check(ChatRequest request) {
        rateLimiter.acquire(request.getUserId());
        guardrail.validateInput(request.getMessage());
        if (!modelConfigured) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "模型 API Key 尚未配置");
        }
    }

    public ResponseStatusException modelFailure(Throwable error) {
        if (error instanceof ResponseStatusException exception) {
            return exception;
        }
        if (error instanceof ToolExecutionException) {
            log.error("业务工具执行失败", error);
            return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "业务工具暂不可用，请稍后重试", error);
        }
        if (error instanceof DataAccessException) {
            log.warn("读取或写入会话记忆失败 | 异常类型={}", error.getClass().getSimpleName());
            return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "会话记忆暂不可用，请稍后重试", error);
        }
        log.warn("模型调用失败 | 异常类型={}", error.getClass().getSimpleName());
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "模型服务暂不可用，请稍后重试", error);
    }

    public void audit(ChatRequest request, String reply, String toolsInvoked, long latencyMs,
                      Throwable failure, boolean multiAgent) {
        if (failure instanceof RateLimitExceededException) {
            return;
        }
        ChatAuditLog auditLog = new ChatAuditLog();
        auditLog.setSessionId(request.getSessionId());
        auditLog.setUserId(request.getUserId());
        auditLog.setUserMessage(failure instanceof RejectedPromptException blocked
                ? blocked.auditMarker() : (multiAgent ? "[Multi-Agent] " : "") + request.getMessage());
        auditLog.setAssistantReply(reply);
        auditLog.setToolsInvoked(toolsInvoked);
        auditLog.setLatencyMs(latencyMs);
        try {
            auditLogs.insert(auditLog);
        } catch (RuntimeException error) {
            log.error("对话审计写入失败", error);
        }
    }

    public static long elapsedMillis(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }
}
