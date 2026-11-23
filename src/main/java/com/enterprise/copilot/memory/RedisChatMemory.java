package com.enterprise.copilot.memory;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

@Slf4j
@Component
@RequiredArgsConstructor
public class RedisChatMemory implements ChatMemory {

    private static final String TTL_SECONDS = Long.toString(Duration.ofHours(24).toSeconds());
    static final int COMPRESS_THRESHOLD = 10;
    private static final int RECENT_WINDOW_SIZE = 6;
    private static final int MAX_UPDATE_ATTEMPTS = 16;
    private static final int MAX_SUMMARY_ENTRIES = 8;
    private static final int MAX_BUSINESS_REFERENCES = 16;
    private static final int MAX_SNIPPET_LENGTH = 80;
    private static final Pattern BUSINESS_REFERENCE = Pattern.compile("\\b(?:ORD|TKT)-[A-Z0-9-]{1,28}\\b",
            Pattern.CASE_INSENSITIVE);

    // Both keys share a Redis Cluster slot. A clear changes the generation and invalidates in-flight writes.
    private static final RedisScript<Long> CLEAR = RedisScript.of("""
            redis.call('SET', KEYS[2], ARGV[1], 'EX', ARGV[2])
            return redis.call('DEL', KEYS[1])
            """, Long.class);

    // Compare the snapshot before replacing it, so concurrent appends never overwrite one another.
    private static final RedisScript<Long> REPLACE = RedisScript.of("""
            if (redis.call('GET', KEYS[2]) or '') ~= ARGV[1] then
                return -1
            end
            local previous = redis.call('LRANGE', KEYS[1], 0, -1)
            local previousSize = tonumber(ARGV[3])
            if #previous ~= previousSize then
                return 0
            end
            for index = 1, previousSize do
                if previous[index] ~= ARGV[3 + index] then
                    return 0
                end
            end
            redis.call('RPUSH', KEYS[1], unpack(ARGV, 4 + previousSize))
            redis.call('LTRIM', KEYS[1], previousSize, -1)
            redis.call('EXPIRE', KEYS[1], ARGV[2])
            redis.call('EXPIRE', KEYS[2], ARGV[2])
            return 1
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public static String conversationId(String userId, String sessionId) {
        return URLEncoder.encode(userId, StandardCharsets.UTF_8)
                + ":" + URLEncoder.encode(sessionId, StandardCharsets.UTF_8);
    }

    @Override
    public void add(String conversationId, List<Message> messages) {
        add(conversationId, messages, generation(conversationId));
    }

    @Override
    public List<Message> get(String conversationId, int lastN) {
        if (lastN <= 0) {
            return List.of();
        }
        return decode(read(conversationId, -lastN)).stream()
                .map(StoredMessage::toMessage)
                .toList();
    }

    @Override
    public void clear(String conversationId) {
        redisTemplate.execute(CLEAR, keys(conversationId), UUID.randomUUID().toString(), TTL_SECONDS);
    }

    String generation(String conversationId) {
        return Objects.toString(redisTemplate.opsForValue().get(generationKey(conversationId)), "");
    }

    void add(String conversationId, List<Message> messages, String generation) {
        List<StoredMessage> newMessages = messages.stream()
                .filter(message -> StringUtils.hasLength(message.getText()))
                .filter(message -> message.getMessageType() != MessageType.TOOL)
                .map(message -> new StoredMessage(message.getMessageType(), message.getText(), null))
                .toList();
        if (newMessages.isEmpty()) {
            return;
        }

        for (int attempt = 0; attempt < MAX_UPDATE_ATTEMPTS; attempt++) {
            List<String> snapshot = read(conversationId, 0);
            List<StoredMessage> history = new ArrayList<>(decode(snapshot));
            history.addAll(newMessages);
            List<String> arguments = new ArrayList<>();
            arguments.add(generation);
            arguments.add(TTL_SECONDS);
            arguments.add(Integer.toString(snapshot.size()));
            arguments.addAll(snapshot);
            compact(history).stream().map(this::encode).forEach(arguments::add);

            Long result = redisTemplate.execute(REPLACE, keys(conversationId), arguments.toArray());
            if (result != null && result != 0L) {
                // A reset won the race when the script returned -1.
                return;
            }
        }
        throw new OptimisticLockingFailureException("会话记忆并发更新失败");
    }

    private List<StoredMessage> compact(List<StoredMessage> history) {
        int limit = history.getFirst().summary() == null ? COMPRESS_THRESHOLD : RECENT_WINDOW_SIZE + 1;
        if (history.size() <= limit) {
            return history;
        }

        int recentStart = history.size() - RECENT_WINDOW_SIZE;
        LinkedHashSet<String> references = new LinkedHashSet<>();
        LinkedHashSet<String> excerpts = new LinkedHashSet<>();
        for (StoredMessage message : history.subList(0, recentStart)) {
            if (message.summary() != null) {
                references.addAll(message.summary().references());
                excerpts.addAll(message.summary().excerpts());
            } else {
                BUSINESS_REFERENCE.matcher(message.content()).results()
                        .forEach(match -> references.addLast(match.group()));
                String excerpt = snippet(message.content());
                if (!excerpt.isEmpty()) {
                    excerpts.addLast(message.role().name() + ": " + excerpt);
                }
            }
        }
        Summary summary = new Summary(tail(references, MAX_BUSINESS_REFERENCES), tail(excerpts, MAX_SUMMARY_ENTRIES));
        List<StoredMessage> compacted = new ArrayList<>();
        compacted.add(new StoredMessage(MessageType.SYSTEM, summary.text(), summary));
        compacted.addAll(history.subList(recentStart, history.size()));
        return compacted;
    }

    private static String snippet(String text) {
        String line = text.replaceAll("[\\r\\n\\t]+", " ").strip();
        if (line.codePointCount(0, line.length()) <= MAX_SNIPPET_LENGTH) {
            return line;
        }
        return line.substring(0, line.offsetByCodePoints(0, MAX_SNIPPET_LENGTH)) + "…";
    }

    private static List<String> tail(LinkedHashSet<String> items, int maximum) {
        List<String> ordered = new ArrayList<>(items);
        return List.copyOf(ordered.subList(Math.max(0, ordered.size() - maximum), ordered.size()));
    }

    private List<String> read(String conversationId, long start) {
        List<String> values = redisTemplate.opsForList().range(historyKey(conversationId), start, -1);
        return values == null ? List.of() : values;
    }

    private List<StoredMessage> decode(List<String> values) {
        List<StoredMessage> messages = new ArrayList<>(values.size());
        for (String value : values) {
            try {
                StoredMessage message = objectMapper.readValue(value, StoredMessage.class);
                if (message != null && message.role() != null && message.role() != MessageType.TOOL
                        && StringUtils.hasLength(message.content())) {
                    messages.add(message);
                }
            } catch (JsonProcessingException exception) {
                log.warn("跳过无法解析的 Redis 对话记忆");
            }
        }
        return messages;
    }

    private String encode(StoredMessage message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法序列化对话记忆", exception);
        }
    }

    private static List<String> keys(String conversationId) {
        return List.of(historyKey(conversationId), generationKey(conversationId));
    }

    private static String historyKey(String conversationId) {
        return "copilot:chat:memory:{" + conversationId + "}";
    }

    private static String generationKey(String conversationId) {
        return "copilot:chat:memory:{" + conversationId + "}:generation";
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record StoredMessage(MessageType role, String content, Summary summary) {
        Message toMessage() {
            return switch (role) {
                case USER -> new UserMessage(content);
                case ASSISTANT -> new AssistantMessage(content);
                case SYSTEM -> new SystemMessage(content);
                case TOOL -> throw new IllegalStateException("工具消息不属于文本记忆");
            };
        }
    }

    private record Summary(List<String> references, List<String> excerpts) {
        String text() {
            return "【早期历史对话摘要】\n提及的订单或工单号：" + String.join("、", references)
                    + "\n" + String.join("\n", excerpts);
        }
    }
}
