package com.enterprise.copilot.agent.dto;

import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Set;

public record ExecutionPlan(IntentType intentType, String goalSummary, Boolean requireRagPolicy, List<String> steps) {

    public static final String LIST_ORDERS = "listUserOrders";
    public static final String QUERY_ORDER = "queryOrderByNo";
    public static final String CHECK_POLICY = "checkPolicy";
    public static final String CREATE_TICKET = "createAfterSalesTicket";
    private static final Set<String> ALLOWED_STEPS = Set.of(
            LIST_ORDERS, QUERY_ORDER, CHECK_POLICY, CREATE_TICKET, "queryTicketByNo", "composeReply");

    public enum IntentType { SIMPLE_QUERY, MULTI_STEP_WORKFLOW }

    public ExecutionPlan {
        if (intentType == null || !StringUtils.hasText(goalSummary) || goalSummary.length() > 500
                || requireRagPolicy == null || steps == null || steps.size() > ALLOWED_STEPS.size()
                || steps.stream().anyMatch(step -> step == null || !ALLOWED_STEPS.contains(step))
                || steps.stream().distinct().count() != steps.size()) {
            throw new IllegalArgumentException("无效的执行计划");
        }
        steps = List.copyOf(steps);
        if (intentType == IntentType.MULTI_STEP_WORKFLOW && (steps.size() < 2
                || (steps.contains(CHECK_POLICY) && !requireRagPolicy))) {
            throw new IllegalArgumentException("多步计划缺少必要步骤或政策标记");
        }
        int write = steps.indexOf(CREATE_TICKET);
        if (intentType == IntentType.MULTI_STEP_WORKFLOW && write >= 0) {
            int policy = steps.indexOf(CHECK_POLICY);
            boolean readsOrderFirst = steps.subList(0, Math.max(policy, 0)).stream()
                    .anyMatch(step -> LIST_ORDERS.equals(step) || QUERY_ORDER.equals(step));
            if (policy < 0 || policy >= write || !readsOrderFirst) {
                throw new IllegalArgumentException("建单计划必须先查订单并核对售后政策");
            }
        }
    }
}
