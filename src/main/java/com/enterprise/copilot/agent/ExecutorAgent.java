package com.enterprise.copilot.agent;

import com.enterprise.copilot.agent.dto.ExecutionPlan;
import com.enterprise.copilot.config.ChatClientConfig;
import com.enterprise.copilot.context.ToolTraceContext;
import com.enterprise.copilot.tools.CustomerServiceTools;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.enterprise.copilot.agent.dto.ExecutionPlan.CREATE_TICKET;

@Component
public class ExecutorAgent {

    private static final int MAX_TOOL_CALLS = 32;
    private static final String SYSTEM = """

            你现在是业务执行器 Executor。计划限定本轮可用能力及先后顺序，不增加用户授权。
            只能按原始请求执行。仅有资格咨询时不建单。明确申请且原因已给出时，才能为符合政策的
            订单使用建单工具。同一个建单步骤可以逐笔处理适用订单，每笔须核对政策规则与真实状态。
            缺少申请原因时请用户补充。回复逐笔说明订单号、可核实的商品、适用条款及结果，保留实际工单号。
            """;
    private static final String REFLECTION = """

            这是修订回复的反思轮。依据原始请求、实际工具结果和审查意见修改草稿。
            没有执行新的操作。保留已发生的业务结果和编号，标出未完成事项，不能补造数据或承诺。
            """;

    private final ChatClient client;
    private final List<ToolCallback> businessTools;

    public ExecutorAgent(ChatModel model, CustomerServiceTools tools, ObjectMapper objectMapper) {
        client = ChatClient.builder(model).build();
        businessTools = ChatClientConfig.customerServiceCallbacks(tools, objectMapper);
    }

    public Execution executePlan(String userMessage, ExecutionPlan plan, String policy, List<Message> history,
                                 ToolTraceContext trace) {
        var run = new WorkflowTools(plan, StringUtils.hasText(policy));
        String input = "【原始请求】\n" + userMessage + "\n【有序计划】\n" + plan.steps();
        var prompt = prompt(policy, SYSTEM, history, input);
        if (!run.callbacks.isEmpty()) {
            prompt.tools(run.callbacks).toolContext(Map.of(ToolTraceContext.CONTEXT_KEY, trace));
        }
        return new Execution(requiredReply(prompt), run.results);
    }

    public Execution revise(String userMessage, String policy, List<Message> history, Execution execution,
                             String feedback) {
        String input = """
                【原始请求】%s
                【实际工具参数与结果】%s
                【原草稿】%s
                【审查意见】%s
                """.formatted(userMessage, execution.toolResults(), execution.reply(), feedback);
        return new Execution(requiredReply(prompt(policy, REFLECTION, history, input)), execution.toolResults());
    }

    private ChatClient.ChatClientRequestSpec prompt(String policy, String instructions, List<Message> history,
                                                    String input) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(ChatClientConfig.systemPromptWithPolicy(
                StringUtils.hasText(policy) ? policy : "本轮没有匹配的政策条款。") + instructions));
        messages.addAll(history);
        messages.add(new UserMessage(input));
        return client.prompt(new Prompt(messages));
    }

    private static String requiredReply(ChatClient.ChatClientRequestSpec prompt) {
        String reply = prompt.call().content();
        if (!StringUtils.hasText(reply)) {
            throw new IllegalStateException("Executor 未返回回复");
        }
        return reply;
    }

    public record Execution(String reply, List<ToolResult> toolResults) {
        public Execution {
            toolResults = List.copyOf(toolResults);
        }
    }

    public record ToolResult(String name, String arguments, String result) {
    }

    private final class WorkflowTools {
        private final List<ToolCallback> callbacks;
        private final List<ToolResult> results = new ArrayList<>();
        private int currentStep = -1;
        private int attempts;

        private WorkflowTools(ExecutionPlan plan, boolean hasPolicy) {
            var selected = plan.steps().stream()
                    .filter(step -> hasPolicy || !CREATE_TICKET.equals(step))
                    .flatMap(step -> businessTools.stream().filter(tool -> step.equals(tool.getName())))
                    .toList();
            var ordered = new ArrayList<ToolCallback>();
            for (ToolCallback tool : selected) {
                ordered.add(new OrderedTool(tool, ordered.size()));
            }
            callbacks = List.copyOf(ordered);
        }

        private synchronized String call(ToolCallback tool, int step, String arguments, ToolContext context) {
            if (++attempts > MAX_TOOL_CALLS) {
                throw new ToolExecutionException(tool.getToolDefinition(), new IllegalStateException("本轮业务工具调用已达上限"));
            }
            if (step < currentStep || step > currentStep + 1) {
                return "本次工具未执行，请按计划依次完成前置步骤。";
            }
            String result = tool.call(arguments, context);
            currentStep = step;
            results.add(new ToolResult(tool.getName(), arguments, result));
            return result;
        }

        private final class OrderedTool implements ToolCallback {
            private final ToolCallback delegate;
            private final int step;

            private OrderedTool(ToolCallback delegate, int step) {
                this.delegate = delegate;
                this.step = step;
            }

            @Override
            public ToolDefinition getToolDefinition() {
                return delegate.getToolDefinition();
            }

            @Override
            public ToolMetadata getToolMetadata() {
                return delegate.getToolMetadata();
            }

            @Override
            public String call(String arguments) {
                return call(arguments, null);
            }

            @Override
            public String call(String arguments, ToolContext context) {
                return WorkflowTools.this.call(delegate, step, arguments, context);
            }
        }
    }
}
