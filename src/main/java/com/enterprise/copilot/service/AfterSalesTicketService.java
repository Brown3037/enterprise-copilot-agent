package com.enterprise.copilot.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.copilot.entity.BizOrder;
import com.enterprise.copilot.entity.ServiceTicket;
import com.enterprise.copilot.mapper.BizOrderMapper;
import com.enterprise.copilot.mapper.ServiceTicketMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;

@Service
@RequiredArgsConstructor
public class AfterSalesTicketService {

    private static final ZoneId BUSINESS_TIME_ZONE = ZoneId.of("Asia/Shanghai");
    private static final int TICKET_NUMBER_ATTEMPTS = 8;

    private final BizOrderMapper bizOrderMapper;
    private final ServiceTicketMapper serviceTicketMapper;
    private final KnowledgeBaseService knowledgeBaseService;

    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public String createTicket(String userId, String orderNo, String ticketType, String reason) {
        // Serialize submissions for the same order across instances before reading open tickets.
        BizOrder order = bizOrderMapper.selectOne(Wrappers.<BizOrder>lambdaQuery()
                .eq(BizOrder::getOrderNo, orderNo)
                .last("FOR UPDATE"));
        if (order == null || !order.getUserId().equals(userId)) {
            return "创建工单失败：未找到该订单，或当前用户无权操作该订单。";
        }
        if ("REFUND".equals(ticketType) && "REFUNDED".equals(order.getStatus())) {
            return "创建工单失败：该订单已退款，无需重复申请。";
        }

        ServiceTicket existing = serviceTicketMapper.selectOne(Wrappers.<ServiceTicket>lambdaQuery()
                .eq(ServiceTicket::getOrderNo, order.getOrderNo())
                .eq(ServiceTicket::getTicketType, ticketType)
                .eq(ServiceTicket::getStatus, "OPEN")
                .orderByAsc(ServiceTicket::getId)
                .last("LIMIT 1"));
        if (existing != null) {
            if (!userId.equals(existing.getUserId())) {
                return "创建工单失败：该订单有一条待核对工单，请联系人工客服。";
            }
            return "已有处理中的同类型工单，工单号：%s | 当前状态：待审核(OPEN)".formatted(existing.getTicketNo());
        }

        var restriction = knowledgeBaseService.ticketRestriction(order, ticketType);
        if (restriction.isPresent()) {
            return "创建工单失败：" + restriction.get();
        }

        ServiceTicket ticket = new ServiceTicket();
        ticket.setOrderNo(order.getOrderNo());
        ticket.setUserId(userId);
        ticket.setTicketType(ticketType);
        ticket.setReason(reason);
        ticket.setStatus("OPEN");
        insertWithUniqueNumber(ticket);
        return "售后工单已创建，工单号：%s | 关联订单：%s | 工单类型：%s | 当前状态：待审核(OPEN)".formatted(
                ticket.getTicketNo(), order.getOrderNo(), ticketType);
    }

    private void insertWithUniqueNumber(ServiceTicket ticket) {
        String prefix = "TKT-" + LocalDate.now(BUSINESS_TIME_ZONE).format(DateTimeFormatter.BASIC_ISO_DATE) + "-";
        for (int attempt = 1; attempt <= TICKET_NUMBER_ATTEMPTS; attempt++) {
            ticket.setTicketNo(prefix + ThreadLocalRandom.current().nextInt(1000, 10000));
            try {
                serviceTicketMapper.insert(ticket);
                return;
            } catch (DuplicateKeyException collision) {
                if (attempt == TICKET_NUMBER_ATTEMPTS) {
                    throw collision;
                }
            }
        }
    }
}
