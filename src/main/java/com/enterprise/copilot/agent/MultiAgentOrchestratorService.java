package com.enterprise.copilot.agent;

import com.enterprise.copilot.agent.ExecutorAgent.Execution;
import com.enterprise.copilot.agent.ExecutorAgent.ToolResult;
import com.enterprise.copilot.agent.dto.CriticReviewResult;
import com.enterprise.copilot.agent.dto.ExecutionPlan;
import com.enterprise.copilot.agent.dto.MultiAgentResponse;
import com.enterprise.copilot.context.ToolTraceContext;
import com.enterprise.copilot.dto.ChatRequest;
import com.enterprise.copilot.memory.RedisChatMemory;
import com.enterprise.copilot.service.ChatRequestSupport;
import com.enterprise.copilot.service.CopilotChatService;
import com.enterprise.copilot.service.KnowledgeBaseService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.stream.Collectors;

import static com.enterprise.copilot.agent.dto.ExecutionPlan.CREATE_TICKET;
import static com.enterprise.copilot.agent.dto.ExecutionPlan.LIST_ORDERS;
import static com.enterprise.copilot.service.ChatRequestSupport.elapsedMillis;

@Service
@RequiredArgsConstructor
public class MultiAgentOrchestratorService {

    private static final int MAX_REFLECTION_ROUNDS = 1;

    private final PlannerAgent planner;
    private final ExecutorAgent executor;
    private final CriticAgent critic;
    private final CopilotChatService chat;
    private final KnowledgeBaseService knowledge;
    private final RedisChatMemory memory;
    private final ChatRequestSupport requests;

    public MultiAgentResponse processWithMultiAgent(ChatRequest request) {
        long startedAt = System.nanoTime();
        ToolTraceContext trace = new ToolTraceContext(request.getUserId());
        String reply = null;
        RuntimeException failure = null;
        try {
            requests.check(request);
            ExecutionPlan plan = planner.createPlan(request.getMessage());
            if (plan.intentType() == ExecutionPlan.IntentType.SIMPLE_QUERY) {
                reply = chat.generateReply(request, trace);
                return new MultiAgentResponse(plan, null, false, trace.peekTools(), elapsedMillis(startedAt), reply);
            }
            ReviewedExecution reviewed = runWorkflow(request, plan, trace);
            reply = reviewed.reply();
            return new MultiAgentResponse(plan, reviewed.review(), reviewed.reflected(),
                    trace.peekTools(), elapsedMillis(startedAt), reply);
        } catch (RuntimeException error) {
            failure = error;
            throw requests.modelFailure(error);
        } finally {
            requests.audit(request, reply, trace.finishAndGetTools(), elapsedMillis(startedAt), failure, "[Multi-Agent] ");
        }
    }

    private ReviewedExecution runWorkflow(ChatRequest request, ExecutionPlan plan, ToolTraceContext trace) {
        String userMessage = request.getMessage();
        String policy = plan.requireRagPolicy()
                ? knowledge.buildWorkflowRagContext(userMessage, request.getUserId(), plan.steps().contains(LIST_ORDERS))
                : "";
        var turn = memory.openTurn(RedisChatMemory.conversationId(request.getUserId(), request.getSessionId()));
        Execution execution = executor.executePlan(userMessage, plan, policy, turn.history(), trace);
        CriticReviewResult review = critic.review(userMessage, plan, policy, execution);
        int rounds = 0;
        while (!review.approved() && rounds < MAX_REFLECTION_ROUNDS) {
            execution = executor.revise(userMessage, policy, turn.history(), execution, review.critiqueComment());
            rounds++;
            review = critic.review(userMessage, plan, policy, execution);
        }

        String reply = approvedReply(execution, review);
        turn.complete(userMessage, reply);
        return new ReviewedExecution(review, rounds > 0, reply);
    }

    private static String approvedReply(Execution execution, CriticReviewResult review) {
        if (review.approved()) {
            return StringUtils.hasText(review.refinedReply()) ? review.refinedReply() : execution.reply();
        }
        String receipts = execution.toolResults().stream().filter(result -> CREATE_TICKET.equals(result.name()))
                .map(ToolResult::result).collect(Collectors.joining("\n"));
        String fallback = "本次回复未通过复核，请联系人工客服核对处理结果。";
        return receipts.isEmpty() ? fallback : fallback + "\n实际建单工具结果：\n" + receipts;
    }

    private record ReviewedExecution(CriticReviewResult review, boolean reflected, String reply) {
    }
}
