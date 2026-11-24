package com.enterprise.copilot.agent;

import com.enterprise.copilot.agent.dto.ExecutionPlan;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
public class PlannerAgent {

    private static final String SYSTEM = """
            你是企业客服任务规划器 Planner。仅返回执行计划 JSON，不能直接回答、查询或建单。
            intentType 只取 SIMPLE_QUERY 或 MULTI_STEP_WORKFLOW。闲聊、单一政策问答或将目标明确的
            单步查询交给现有客服链路，使用 SIMPLE_QUERY。涉及先查订单再判断、批量处理或依次办理多个
            子任务时，选择 MULTI_STEP_WORKFLOW。goalSummary 用简短文字概括真实目标，不添加用户未请求的动作。
            涉及售后政策判断时 requireRagPolicy 为 true。
            steps 是按顺序排列、不重复的步骤 ID，只能从 listUserOrders、queryOrderByNo、queryTicketByNo、
            checkPolicy、createAfterSalesTicket、composeReply 中选择。闲聊可用 ["composeReply"]。
            只有用户明确要求提交申请时才能加入 createAfterSalesTicket，多步建单必须先查订单，再核对政策。
            """;

    private final ChatClient client;

    public PlannerAgent(ChatModel model) {
        client = ChatClient.builder(model).defaultSystem(SYSTEM).defaultOptions(OpenAiChatOptions.builder()
                .temperature(0.0).responseFormat(new ResponseFormat(ResponseFormat.Type.JSON_OBJECT, null)).build())
                .build();
    }

    public ExecutionPlan createPlan(String userMessage) {
        return Objects.requireNonNull(client.prompt()
                .user(spec -> spec.text("用户请求：\n{request}").param("request", userMessage))
                .call().entity(ExecutionPlan.class), "Planner 未返回执行计划");
    }
}
