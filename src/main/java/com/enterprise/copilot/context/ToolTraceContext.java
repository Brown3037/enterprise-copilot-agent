package com.enterprise.copilot.context;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.util.StringUtils;

import java.util.LinkedHashSet;

public final class ToolTraceContext {

    public static final String CONTEXT_KEY = "copilotToolTrace";

    private final String userId;
    private final LinkedHashSet<String> toolNames = new LinkedHashSet<>();
    private boolean finished;

    public ToolTraceContext(String userId) {
        this.userId = userId;
    }

    public static String beginToolCall(ToolContext toolContext, String toolName) {
        if (toolContext == null || !(toolContext.getContext().get(CONTEXT_KEY) instanceof ToolTraceContext trace)) {
            return null;
        }
        return trace.record(toolName);
    }

    private synchronized String record(String toolName) {
        if (finished) {
            return null;
        }
        toolNames.add(toolName);
        return StringUtils.hasText(userId) ? userId : null;
    }

    public synchronized String finishAndGetTools() {
        finished = true;
        return toolNames.isEmpty() ? "NONE" : String.join(",", toolNames);
    }
}
