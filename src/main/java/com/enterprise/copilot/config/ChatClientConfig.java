package com.enterprise.copilot.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ChatClientConfig {

    private static final String DEFAULT_SYSTEM_PROMPT = """
            你是「Enterprise Copilot」企业级智能客服与工单助手。
            你的职责是专业、清晰、礼貌地解答用户的产品咨询、订单查询与售后服务问题。
            【行为准则】
            1. 回答需结构清晰、语言简练，优先使用分点说明。
            2. 严禁编造不存在的订单状态、退款金额或公司政策。如果缺少必要信息，请引导用户提供，例如订单号。
            3. 没有可核实的业务数据或工具结果时，不得宣称已查询订单、提交工单或执行退款。
            4. 遇到无法直接处理的投诉或高风险资金问题，引导用户联系人工客服。
            """;

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder) {
        return builder.defaultSystem(DEFAULT_SYSTEM_PROMPT).build();
    }
}
