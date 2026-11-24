package com.enterprise.copilot.agent.dto;

public record MultiAgentResponse(ExecutionPlan plan, CriticReviewResult review, boolean reflectionTriggered,
                                 String toolsInvoked, long totalLatencyMs, String finalReply) {
}
