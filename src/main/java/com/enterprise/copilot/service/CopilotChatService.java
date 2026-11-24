package com.enterprise.copilot.service;

import com.enterprise.copilot.config.ChatClientConfig;
import com.enterprise.copilot.context.ToolTraceContext;
import com.enterprise.copilot.dto.ChatRequest;
import com.enterprise.copilot.memory.RedisChatMemory;
import com.enterprise.copilot.security.RateLimitExceededException;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;
import java.util.Optional;

import static com.enterprise.copilot.service.ChatRequestSupport.elapsedMillis;
import static org.springframework.ai.chat.client.advisor.AbstractChatMemoryAdvisor.CHAT_MEMORY_CONVERSATION_ID_KEY;

@Service
@RequiredArgsConstructor
public class CopilotChatService {

    private final ChatClient chatClient;
    private final KnowledgeBaseService knowledgeBaseService;
    private final ChatRequestSupport requests;

    public String chatSync(ChatRequest request) {
        long startedAt = System.nanoTime();
        ToolTraceContext trace = new ToolTraceContext(request.getUserId());
        String reply = null;
        RuntimeException failure = null;
        try {
            requests.check(request);
            reply = generateReply(request, trace);
            return reply;
        } catch (RuntimeException error) {
            failure = error;
            throw requests.modelFailure(error);
        } finally {
            requests.audit(request, reply, trace.finishAndGetTools(), elapsedMillis(startedAt), failure, false);
        }
    }

    public String generateReply(ChatRequest request, ToolTraceContext trace) {
        var prompt = preparePrompt(request, trace);
        String content = prompt.isPresent() ? prompt.get().call().content() : KnowledgeBaseService.NO_POLICY_EVIDENCE;
        if (!StringUtils.hasLength(content)) {
            throw new IllegalStateException("模型未返回文本");
        }
        return content;
    }

    public Flux<String> chatStream(ChatRequest request) {
        return Flux.usingWhen(
                Mono.fromSupplier(() -> new StreamAudit(request.getUserId())),
                audit -> Flux.defer(() -> {
                            requests.check(request);
                            var prompt = preparePrompt(request, audit.trace);
                            return prompt.isPresent() ? prompt.get().stream().content()
                                    : Flux.just(KnowledgeBaseService.NO_POLICY_EVIDENCE);
                        })
                        .subscribeOn(Schedulers.boundedElastic())
                        .switchIfEmpty(Mono.error(() -> new IllegalStateException("模型未返回文本")))
                        .onErrorMap(requests::modelFailure)
                        .map(chunk -> chunk.replace("\r\n", "\n").replace('\r', '\n'))
                        .doOnNext(audit.reply::append),
                audit -> saveAuditLogAsync(request, audit.reply.toString(), audit, null),
                (audit, error) -> saveAuditLogAsync(request, null, audit, error),
                audit -> saveAuditLogAsync(request, null, audit, null));
    }

    private Optional<ChatClient.ChatClientRequestSpec> preparePrompt(ChatRequest request, ToolTraceContext trace) {
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

    private Mono<Void> saveAuditLogAsync(ChatRequest request, String reply, StreamAudit audit, Throwable failure) {
        long latencyMs = elapsedMillis(audit.startedAt);
        String tools = audit.trace.finishAndGetTools();
        if (failure instanceof RateLimitExceededException) {
            return Mono.empty();
        }
        return Mono.<Void>fromRunnable(() -> requests.audit(request, reply, tools, latencyMs, failure, false))
                .subscribeOn(Schedulers.boundedElastic());
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
