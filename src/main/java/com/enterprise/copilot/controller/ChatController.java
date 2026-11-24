package com.enterprise.copilot.controller;

import com.enterprise.copilot.agent.MultiAgentOrchestratorService;
import com.enterprise.copilot.agent.dto.MultiAgentResponse;
import com.enterprise.copilot.common.Result;
import com.enterprise.copilot.dto.ChatRequest;
import com.enterprise.copilot.memory.RedisChatMemory;
import com.enterprise.copilot.service.CopilotChatService;
import com.enterprise.copilot.service.LangGraphBridgeService;
import com.enterprise.copilot.service.LangGraphBridgeService.WorkflowResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final CopilotChatService copilotChatService;
    private final RedisChatMemory memory;
    private final MultiAgentOrchestratorService multiAgentOrchestratorService;
    private final LangGraphBridgeService langGraphBridgeService;

    @PostMapping
    public Result<String> chat(@Valid @RequestBody ChatRequest request) {
        return Result.success(copilotChatService.chatSync(request));
    }

    @PostMapping("/multi-agent")
    public Result<MultiAgentResponse> multiAgentChat(@Valid @RequestBody ChatRequest request) {
        return Result.success(multiAgentOrchestratorService.processWithMultiAgent(request));
    }

    @PostMapping("/langgraph")
    public Result<WorkflowResponse> langGraphChat(@Valid @RequestBody ChatRequest request) {
        return Result.success(langGraphBridgeService.delegateToLangGraph(request));
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> streamChat(@Valid @RequestBody ChatRequest request) {
        // MVC writes `data:` directly. Pad each line so SSE parsing preserves model whitespace.
        return copilotChatService.chatStream(request)
                .map(chunk -> " " + chunk.replace("\n", "\n "));
    }

    @DeleteMapping("/memory/{sessionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clearMemory(@PathVariable @NotBlank @Size(max = 64) String sessionId,
                            @RequestParam @NotBlank @Size(max = 32) String userId) {
        memory.clear(RedisChatMemory.conversationId(userId, sessionId));
    }
}
