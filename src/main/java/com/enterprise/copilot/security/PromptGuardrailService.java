package com.enterprise.copilot.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Slf4j
@Component
public class PromptGuardrailService {

    private static final int MAX_CODE_POINTS = 2000;
    private static final List<Rule> RULES = List.of(
            rule("OVERRIDE_INSTRUCTIONS",
                    "\\b(?:ignore|disregard|forget|override)\\b.{0,80}\\b(?:previous|prior|above|all)\\b"
                            + ".{0,80}\\b(?:instructions?|prompt|rules?)\\b"
                            + "|(?:忽略|无视|忘记|覆盖).{0,30}(?:以上|上面|之前|先前|所有).{0,30}(?:指令|规则|设定)"),
            rule("SYSTEM_PROMPT_DISCLOSURE",
                    "(?:输出|显示|展示|透露|泄露|复述|给我).{0,36}(?:system\\s*prompt|系统提示词|系统指令|内部设定)"
                            + "|\\b(?:print|show|output|reveal|expose|repeat|give)\\b.{0,60}"
                            + "\\b(?:system\\s+prompt|system\\s+instructions?)\\b"),
            rule("CROSS_ACCOUNT_ACCESS",
                    "(?:查询|查看|读取|导出|修改).{0,30}(?:所有用户|全部用户|所有账号|全部账号|其他账号|他人订单|全站订单)"
                            + "|\\b(?:query|list|export|modify|read)\\b.{0,60}\\b(?:all\\s+users|other\\s+accounts)\\b"),
            rule("SQL_CONTROL_TEXT", "\\b(?:drop\\s+table|union\\s+select|truncate\\s+table)\\b"));

    public void validateInput(String message) {
        if (message.codePointCount(0, message.length()) > MAX_CODE_POINTS) {
            reject("MESSAGE_LENGTH", "单次输入超过 2000 个字符，请精简后重试。");
        }
        String normalized = Normalizer.normalize(message, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .replaceAll("\\p{Cf}", "").replaceAll("(?U)\\s+", " ");
        for (Rule rule : RULES) {
            if (rule.pattern().matcher(normalized).find()) {
                reject(rule.id(), "输入触发安全校验，请直接描述需要处理的业务。");
            }
        }
    }

    private static void reject(String ruleId, String message) {
        log.warn("对话输入已拦截 | rule={}", ruleId);
        throw new RejectedPromptException(ruleId, message);
    }

    private static Rule rule(String id, String expression) {
        return new Rule(id, Pattern.compile(expression));
    }

    private record Rule(String id, Pattern pattern) {
    }

    public static final class RejectedPromptException extends ResponseStatusException {
        private final String ruleId;

        private RejectedPromptException(String ruleId, String message) {
            super(HttpStatus.FORBIDDEN, message);
            this.ruleId = ruleId;
        }

        public String auditMarker() {
            return "[GUARDRAIL_BLOCKED:" + ruleId + "]";
        }
    }
}
