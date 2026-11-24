package com.enterprise.copilot.controller;

import com.enterprise.copilot.common.Result;
import com.enterprise.copilot.entity.BizOrder;
import com.enterprise.copilot.security.LangGraphInternalAuth;
import com.enterprise.copilot.service.AfterSalesTicketService;
import com.enterprise.copilot.service.AfterSalesTicketService.TicketResult;
import com.enterprise.copilot.service.KnowledgeBaseService;
import com.enterprise.copilot.tools.CustomerServiceTools;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/internal")
@RequiredArgsConstructor
public class InternalBizController {

    private static final List<String> TICKET_TYPES = List.of("REFUND", "EXCHANGE", "COMPLAINT");

    private final LangGraphInternalAuth auth;
    private final CustomerServiceTools customerTools;
    private final KnowledgeBaseService knowledge;
    private final AfterSalesTicketService tickets;

    @GetMapping("/orders")
    public Result<List<WorkflowOrder>> listOrders(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestParam @NotBlank @Size(max = 32) String userId) {
        auth.validate(authorization);
        return Result.success(customerTools.listOwnedOrders(userId).stream().map(this::workflowOrder).toList());
    }

    @PostMapping("/tickets")
    public Result<TicketResult> createTicket(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @Valid @RequestBody InternalTicketRequest request) {
        auth.validate(authorization);
        return Result.success(tickets.createWorkflowTicket(
                request.userId(), request.orderNo(), request.ticketType(), request.reason().strip()));
    }

    private WorkflowOrder workflowOrder(BizOrder order) {
        String manual = knowledge.workflowManualReview(order).orElse(null);
        List<String> allowed = manual == null ? TICKET_TYPES.stream()
                .filter(type -> knowledge.ticketRestriction(order, type).isEmpty()).toList() : List.of();
        return new WorkflowOrder(order.getOrderNo(), order.getProductName(), order.getAmount(), order.getStatus(),
                allowed, manual);
    }

    public record InternalTicketRequest(
            @NotBlank @Size(max = 32) String userId,
            @NotBlank @Pattern(regexp = "ORD-[A-Z0-9][A-Z0-9-]{0,27}") String orderNo,
            @NotBlank @Pattern(regexp = "REFUND|EXCHANGE|COMPLAINT") String ticketType,
            @NotBlank @Size(max = 255) String reason) {
    }

    public record WorkflowOrder(String orderNo, String productName, BigDecimal amount, String status,
                                List<String> eligibleTicketTypes, String manualReviewReason) {
    }
}
