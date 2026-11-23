package com.enterprise.copilot.service;

import com.enterprise.copilot.dto.ChatRequest;
import com.enterprise.copilot.entity.ChatAuditLog;
import com.enterprise.copilot.mapper.ChatAuditLogMapper;
import com.enterprise.copilot.memory.RedisChatMemory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.concurrent.TimeUnit;

import static org.springframework.ai.chat.client.advisor.AbstractChatMemoryAdvisor.CHAT_MEMORY_CONVERSATION_ID_KEY;

@Slf4j
@Service
public class CopilotChatService {

    private final ChatClient chatClient;
    private final ChatAuditLogMapper chatAuditLogMapper;
    private final boolean modelConfigured;

    public CopilotChatService(ChatClient chatClient, ChatAuditLogMapper chatAuditLogMapper,
                              @Value("${spring.ai.openai.api-key}") String apiKey) {
        this.chatClient = chatClient;
        this.chatAuditLogMapper = chatAuditLogMapper;
        this.modelConfigured = StringUtils.hasText(apiKey) && !"sk-placeholder".equals(apiKey.strip());
    }

    public String chatSync(ChatRequest request) {
        long startedAt = System.nanoTime();
        String reply = null;
        try {
            String content = prompt(request).call().content();
            if (!StringUtils.hasLength(content)) {
                throw new IllegalStateException("模型未返回文本");
            }
            reply = content;
            return reply;
        } catch (RuntimeException error) {
            throw modelFailure(error);
        } finally {
            saveAuditLog(request, reply, elapsedMillis(startedAt));
        }
    }

    public Flux<String> chatStream(ChatRequest request) {
        return Flux.usingWhen(
                Mono.fromSupplier(StreamAudit::new),
                audit -> Flux.defer(() -> prompt(request).stream().content())
                        .switchIfEmpty(Mono.error(() -> new IllegalStateException("模型未返回文本")))
                        .onErrorMap(this::modelFailure)
                        .map(chunk -> chunk.replace("\r\n", "\n").replace('\r', '\n'))
                        .doOnNext(audit.reply::append),
                audit -> saveAuditLogAsync(request, audit.reply.toString(), audit.startedAt),
                (audit, error) -> saveAuditLogAsync(request, null, audit.startedAt),
                audit -> saveAuditLogAsync(request, null, audit.startedAt));
    }

    private ChatClient.ChatClientRequestSpec prompt(ChatRequest request) {
        if (!modelConfigured) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "模型 API Key 尚未配置");
        }
        String conversationId = RedisChatMemory.conversationId(request.getUserId(), request.getSessionId());
        return chatClient.prompt()
                .user(request.getMessage())
                .advisors(spec -> spec.param(CHAT_MEMORY_CONVERSATION_ID_KEY, conversationId));
    }

    private ResponseStatusException modelFailure(Throwable error) {
        if (error instanceof ResponseStatusException exception) {
            return exception;
        }
        if (error instanceof DataAccessException) {
            log.warn("读取或写入会话记忆失败 | 异常类型={}", error.getClass().getSimpleName());
            return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "会话记忆暂不可用，请稍后重试", error);
        }
        log.warn("模型调用失败 | 异常类型={}", error.getClass().getSimpleName());
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "模型服务暂不可用，请稍后重试", error);
    }

    private Mono<Void> saveAuditLogAsync(ChatRequest request, String reply, long startedAt) {
        long latencyMs = elapsedMillis(startedAt);
        return Mono.<Void>fromRunnable(() -> saveAuditLog(request, reply, latencyMs))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private void saveAuditLog(ChatRequest request, String reply, long latencyMs) {
        ChatAuditLog auditLog = new ChatAuditLog();
        auditLog.setSessionId(request.getSessionId());
        auditLog.setUserId(request.getUserId());
        auditLog.setUserMessage(request.getMessage());
        auditLog.setAssistantReply(reply);
        auditLog.setToolsInvoked("NONE");
        auditLog.setLatencyMs(latencyMs);
        try {
            chatAuditLogMapper.insert(auditLog);
        } catch (RuntimeException error) {
            log.error("对话审计写入失败", error);
        }
    }

    private static long elapsedMillis(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    private static final class StreamAudit {
        private final long startedAt = System.nanoTime();
        private final StringBuilder reply = new StringBuilder();
    }
}
