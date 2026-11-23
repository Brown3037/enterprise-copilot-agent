package com.enterprise.copilot.tools;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.copilot.context.ToolTraceContext;
import com.enterprise.copilot.entity.BizOrder;
import com.enterprise.copilot.entity.ServiceTicket;
import com.enterprise.copilot.mapper.BizOrderMapper;
import com.enterprise.copilot.mapper.ServiceTicketMapper;
import com.enterprise.copilot.service.AfterSalesTicketService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class CustomerServiceTools {

    private static final String MISSING_USER = "缺少有效的当前用户身份，无法处理请求。";
    private static final Set<String> TICKET_TYPES = Set.of("REFUND", "EXCHANGE", "COMPLAINT");

    private final BizOrderMapper bizOrderMapper;
    private final ServiceTicketMapper serviceTicketMapper;
    private final AfterSalesTicketService afterSalesTicketService;

    @Tool(description = "查询当前用户名下指定订单的商品、实付金额、订单状态和下单时间")
    public String queryOrderByNo(@ToolParam(description = "订单编号，例如 ORD-2026001") String orderNo,
                                 ToolContext context) {
        String userId = ToolTraceContext.beginToolCall(context, "queryOrderByNo");
        if (userId == null) {
            return MISSING_USER;
        }
        String number = boundedText(orderNo, 32);
        if (number == null) {
            return "请提供有效订单编号（最多 32 个字符）。";
        }
        BizOrder order = bizOrderMapper.selectOne(
                Wrappers.<BizOrder>lambdaQuery().eq(BizOrder::getOrderNo, number));
        if (order == null || !userId.equals(order.getUserId())) {
            return "未找到该订单，或当前用户无权查询该订单。";
        }
        return "订单号：%s | 商品：%s | 实付金额：¥%s | 当前状态：%s | 下单时间：%s".formatted(
                order.getOrderNo(), order.getProductName(), order.getAmount(),
                orderStatus(order.getStatus()), order.getCreateTime());
    }

    @Tool(description = "按下单时间倒序查询当前用户的历史订单")
    public String listUserOrders(ToolContext context) {
        String userId = ToolTraceContext.beginToolCall(context, "listUserOrders");
        if (userId == null) {
            return MISSING_USER;
        }
        List<BizOrder> orders = bizOrderMapper.selectList(Wrappers.<BizOrder>lambdaQuery()
                        .eq(BizOrder::getUserId, userId)
                        .orderByDesc(BizOrder::getCreateTime, BizOrder::getId))
                .stream().filter(order -> userId.equals(order.getUserId())).toList();
        if (orders.isEmpty()) {
            return "当前用户暂无订单。";
        }
        return orders.stream()
                .map(order -> "- 订单号：%s | 商品：%s | 实付金额：¥%s | 状态：%s".formatted(
                        order.getOrderNo(), order.getProductName(), order.getAmount(), orderStatus(order.getStatus())))
                .collect(Collectors.joining("\n", "共 " + orders.size() + " 笔订单：\n", ""));
    }

    @Tool(description = "根据工单编号查询当前用户名下的售后工单和处理状态")
    public String queryTicketByNo(@ToolParam(description = "工单编号，例如 TKT-20261123-1234") String ticketNo,
                                  ToolContext context) {
        String userId = ToolTraceContext.beginToolCall(context, "queryTicketByNo");
        if (userId == null) {
            return MISSING_USER;
        }
        String number = boundedText(ticketNo, 32);
        if (number == null) {
            return "请提供有效工单编号（最多 32 个字符）。";
        }
        ServiceTicket ticket = serviceTicketMapper.selectOne(
                Wrappers.<ServiceTicket>lambdaQuery().eq(ServiceTicket::getTicketNo, number));
        if (ticket == null || !userId.equals(ticket.getUserId())) {
            return "未找到该工单，或当前用户无权查询该工单。";
        }
        return "工单号：%s | 关联订单：%s | 类型：%s | 状态：%s | 创建时间：%s".formatted(
                ticket.getTicketNo(), ticket.getOrderNo(), ticket.getTicketType(), ticket.getStatus(),
                ticket.getCreateTime());
    }

    @Tool(description = "按当前用户明确提出的申请创建售后工单，仅登记待审核，不执行实际退款")
    public String createAfterSalesTicket(
            @ToolParam(description = "关联订单编号，例如 ORD-2026001") String orderNo,
            @ToolParam(description = "工单类型，仅支持 REFUND 退款、EXCHANGE 换货或 COMPLAINT 投诉") String ticketType,
            @ToolParam(description = "用户明确提供的申请原因，最多 255 个字符") String reason,
            ToolContext context) {
        String userId = ToolTraceContext.beginToolCall(context, "createAfterSalesTicket");
        if (userId == null) {
            return MISSING_USER;
        }
        String number = boundedText(orderNo, 32);
        if (number == null) {
            return "创建工单失败：请提供有效订单编号（最多 32 个字符）。";
        }
        String type = boundedText(ticketType, 20);
        String normalizedType = type == null ? null : type.toUpperCase(Locale.ROOT);
        if (normalizedType == null || !TICKET_TYPES.contains(normalizedType)) {
            return "创建工单失败：工单类型仅支持 REFUND、EXCHANGE 或 COMPLAINT。";
        }
        String detail = boundedText(reason, 255);
        if (detail == null) {
            return "创建工单失败：请提供具体原因（最多 255 个字符）。";
        }
        return afterSalesTicketService.createTicket(userId, number, normalizedType, detail);
    }

    private static String boundedText(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String text = value.strip();
        return text.isEmpty() || text.codePointCount(0, text.length()) > maxLength ? null : text;
    }

    private static String orderStatus(String status) {
        return switch (status) {
            case "PENDING_SHIP" -> "待发货 (PENDING_SHIP)";
            case "SHIPPED" -> "已发货 (SHIPPED)";
            case "COMPLETED" -> "已完成 (COMPLETED)";
            case "REFUNDED" -> "已退款 (REFUNDED)";
            default -> status;
        };
    }
}
