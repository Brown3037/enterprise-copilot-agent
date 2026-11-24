package com.enterprise.copilot.agent.dto;

import org.springframework.util.StringUtils;

public record CriticReviewResult(Boolean approved, RiskLevel riskLevel, String critiqueComment, String refinedReply) {

    public enum RiskLevel { LOW, MEDIUM, HIGH }

    public CriticReviewResult {
        if (approved == null || riskLevel == null || (approved && riskLevel != RiskLevel.LOW)
                || (!approved && !StringUtils.hasText(critiqueComment))) {
            throw new IllegalArgumentException("无效的审查结果");
        }
    }
}
