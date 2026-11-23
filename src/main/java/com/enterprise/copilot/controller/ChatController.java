package com.enterprise.copilot.controller;

import com.enterprise.copilot.common.Result;
import com.enterprise.copilot.dto.ChatRequest;
import com.enterprise.copilot.service.CopilotChatService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final CopilotChatService copilotChatService;

    @PostMapping
    public Result<String> chat(@Valid @RequestBody ChatRequest request) {
        return Result.success(copilotChatService.chatSync(request));
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> streamChat(@Valid @RequestBody ChatRequest request) {
        // MVC writes `data:` directly. Pad each line so SSE parsing preserves model whitespace.
        return copilotChatService.chatStream(request)
                .map(chunk -> " " + chunk.replace("\n", "\n "));
    }
}
