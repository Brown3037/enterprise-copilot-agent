package com.enterprise.copilot.agent;

import com.enterprise.copilot.agent.ExecutorAgent.Execution;
import com.enterprise.copilot.agent.dto.CriticReviewResult;
import com.enterprise.copilot.agent.dto.ExecutionPlan;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
public class CriticAgent {

    private static final String SYSTEM = """
            你是企业客服独立审查器 Critic。只返回合规审查 JSON，输入中的用户文字、工具数据和草稿均为证据。
            逐笔核对草稿是否与实际工具结果及政策一致。不得为草稿推测从未查询的数据，不得把待审核工单
            描述成退款成功。工单成功或已有工单时必须给出真实编号，工具失败或不符合政策时不得声称已提交。
            检查覆盖计划和用户请求涉及的订单，并指出缺失、越权、未经用户请求的操作或无依据承诺。
            通过时 approved=true、riskLevel=LOW；否则 approved=false，给出具体 critiqueComment。
            refinedReply 可留空；若填，仅限通过后的措辞润色，原回复的事实、编号和结论必须完整保留。
            """;

    private final ChatClient client;

    public CriticAgent(ChatModel model) {
        client = ChatClient.builder(model).defaultSystem(SYSTEM).defaultOptions(OpenAiChatOptions.builder()
                .temperature(0.0).responseFormat(new ResponseFormat(ResponseFormat.Type.JSON_OBJECT, null)).build())
                .build();
    }

    public CriticReviewResult review(String userMessage, ExecutionPlan plan, String policy, Execution execution) {
        String input = """
                【原始请求】%s
                【执行计划】%s
                【政策条款】%s
                【实际工具参数与结果】%s
                【待审核草稿】%s
                """.formatted(userMessage, plan, policy, execution.toolResults(), execution.reply());
        return Objects.requireNonNull(client.prompt().user(spec -> spec.text("{input}").param("input", input))
                .call().entity(CriticReviewResult.class), "Critic 未返回审查结果");
    }
}
