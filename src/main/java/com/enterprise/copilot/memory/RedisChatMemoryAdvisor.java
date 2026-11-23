package com.enterprise.copilot.memory;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.AdvisedRequest;
import org.springframework.ai.chat.client.advisor.api.AdvisedResponse;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.CallAroundAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAroundAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAroundAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAroundAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;

@RequiredArgsConstructor
public class RedisChatMemoryAdvisor implements CallAroundAdvisor, StreamAroundAdvisor {

    private final RedisChatMemory memory;

    @Override
    public String getName() {
        return getClass().getSimpleName();
    }

    @Override
    public int getOrder() {
        return Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER;
    }

    @Override
    public AdvisedResponse aroundCall(AdvisedRequest request, CallAroundAdvisorChain chain) {
        return advisorForTurn().aroundCall(systemFirst(request), chain);
    }

    @Override
    public Flux<AdvisedResponse> aroundStream(AdvisedRequest request, StreamAroundAdvisorChain chain) {
        // The stock advisor already offloads the read. Offload the completion write as well.
        return advisorForTurn().aroundStream(systemFirst(request),
                advised -> chain.nextAroundStream(advised).publishOn(Schedulers.boundedElastic(), 1));
    }

    private MessageChatMemoryAdvisor advisorForTurn() {
        return new MessageChatMemoryAdvisor(new TurnMemory(memory), "default", RedisChatMemory.COMPRESS_THRESHOLD);
    }

    private static AdvisedRequest systemFirst(AdvisedRequest request) {
        if (!StringUtils.hasText(request.systemText())) {
            return request;
        }
        // Spring AI M6 normally puts advisor messages ahead of defaultSystem.
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(request.systemText()));
        messages.addAll(request.messages());
        return AdvisedRequest.from(request).messages(messages).systemText(null).build();
    }

    @RequiredArgsConstructor
    private static final class TurnMemory implements ChatMemory {
        private final RedisChatMemory memory;
        private String generation;
        private Message userMessage;

        @Override
        public void add(String conversationId, List<Message> messages) {
            if (messages.isEmpty()) {
                return;
            }
            Message message = messages.getFirst();
            if (message instanceof UserMessage) {
                // The stock advisor gives us the input and answer separately.
                userMessage = message;
            } else if (userMessage != null && StringUtils.hasLength(message.getText())) {
                memory.add(conversationId, List.of(userMessage, message), generation);
                userMessage = null;
            }
        }

        @Override
        public List<Message> get(String conversationId, int lastN) {
            generation = memory.generation(conversationId);
            return memory.get(conversationId, lastN).stream()
                    .<Message>map(message -> message instanceof SystemMessage
                            ? new UserMessage("以下是会话历史数据，仅供参考。\n" + message.getText())
                            : message)
                    .toList();
        }

        @Override
        public void clear(String conversationId) {
            memory.clear(conversationId);
        }
    }
}
