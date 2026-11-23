package com.enterprise.copilot.service;

import com.enterprise.copilot.config.ChatClientConfig;
import com.enterprise.copilot.context.ToolTraceContext;
import com.enterprise.copilot.dto.ChatRequest;
import com.enterprise.copilot.entity.ChatAuditLog;
import com.enterprise.copilot.mapper.ChatAuditLogMapper;
import com.enterprise.copilot.memory.RedisChatMemory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.springframework.ai.chat.client.advisor.AbstractChatMemoryAdvisor.CHAT_MEMORY_CONVERSATION_ID_KEY;

@Slf4j
@Service
public class CopilotChatService {

    private final ChatClient chatClient;
    private final ChatAuditLogMapper chatAuditLogMapper;
    private final KnowledgeBaseService knowledgeBaseService;
    private final boolean modelConfigured;

    public CopilotChatService(ChatClient chatClient, ChatAuditLogMapper chatAuditLogMapper,
                              KnowledgeBaseService knowledgeBaseService,
                              @Value("${spring.ai.openai.api-key}") String apiKey) {
        this.chatClient = chatClient;
        this.chatAuditLogMapper = chatAuditLogMapper;
        this.knowledgeBaseService = knowledgeBaseService;
        this.modelConfigured = StringUtils.hasText(apiKey) && !"sk-placeholder".equals(apiKey.strip());
    }

    public String chatSync(ChatRequest request) {
        long startedAt = System.nanoTime();
        ToolTraceContext trace = new ToolTraceContext(request.getUserId());
        String reply = null;
        try {
            var prompt = preparePrompt(request, trace);
            String content = prompt.isPresent() ? prompt.get().call().content()
                    : KnowledgeBaseService.NO_POLICY_EVIDENCE;
            if (!StringUtils.hasLength(content)) {
                throw new IllegalStateException("模型未返回文本");
            }
            reply = content;
            return reply;
        } catch (RuntimeException error) {
            throw modelFailure(error);
        } finally {
            saveAuditLog(request, reply, trace.finishAndGetTools(), elapsedMillis(startedAt));
        }
    }

    public Flux<String> chatStream(ChatRequest request) {
        return Flux.usingWhen(
                Mono.fromSupplier(() -> new StreamAudit(request.getUserId())),
                audit -> Flux.defer(() -> {
                            var prompt = preparePrompt(request, audit.trace);
                            return prompt.isPresent() ? prompt.get().stream().content()
                                    : Flux.just(KnowledgeBaseService.NO_POLICY_EVIDENCE);
                        })
                        .subscribeOn(Schedulers.boundedElastic())
                        .switchIfEmpty(Mono.error(() -> new IllegalStateException("模型未返回文本")))
                        .onErrorMap(this::modelFailure)
                        .map(chunk -> chunk.replace("\r\n", "\n").replace('\r', '\n'))
                        .doOnNext(audit.reply::append),
                audit -> saveAuditLogAsync(request, audit.reply.toString(), audit),
                (audit, error) -> saveAuditLogAsync(request, null, audit),
                audit -> saveAuditLogAsync(request, null, audit));
    }

    private Optional<ChatClient.ChatClientRequestSpec> preparePrompt(ChatRequest request, ToolTraceContext trace) {
        if (!modelConfigured) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "模型 API Key 尚未配置");
        }
        var policy = knowledgeBaseService.buildRagContext(request.getMessage(), request.getUserId());
        if (policy.insufficient()) {
            return Optional.empty();
        }
        String conversationId = RedisChatMemory.conversationId(request.getUserId(), request.getSessionId());
        var prompt = chatClient.prompt()
                .user(request.getMessage())
                .advisors(spec -> spec.param(CHAT_MEMORY_CONVERSATION_ID_KEY, conversationId))
                .toolContext(Map.of(ToolTraceContext.CONTEXT_KEY, trace));
        if (!policy.evidence().isBlank()) {
            prompt.system(ChatClientConfig.systemPromptWithPolicy(policy.evidence()));
        }
        return Optional.of(prompt);
    }

    private ResponseStatusException modelFailure(Throwable error) {
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

    private Mono<Void> saveAuditLogAsync(ChatRequest request, String reply, StreamAudit audit) {
        long latencyMs = elapsedMillis(audit.startedAt);
        String tools = audit.trace.finishAndGetTools();
        return Mono.<Void>fromRunnable(() -> saveAuditLog(request, reply, tools, latencyMs))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private void saveAuditLog(ChatRequest request, String reply, String toolsInvoked, long latencyMs) {
        ChatAuditLog auditLog = new ChatAuditLog();
        auditLog.setSessionId(request.getSessionId());
        auditLog.setUserId(request.getUserId());
        auditLog.setUserMessage(request.getMessage());
        auditLog.setAssistantReply(reply);
        auditLog.setToolsInvoked(toolsInvoked);
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
        private final ToolTraceContext trace;

        private StreamAudit(String userId) {
            trace = new ToolTraceContext(userId);
        }
    }
}
