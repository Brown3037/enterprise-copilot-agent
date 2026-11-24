package com.enterprise.copilot.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
public class LangGraphInternalAuth {

    private final String token;

    public LangGraphInternalAuth(@Value("${copilot.langgraph.token}") String token) {
        this.token = token;
    }

    public String authorization() {
        if (token.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "LangGraph 服务间凭据尚未配置");
        }
        return "Bearer " + token;
    }

    public void validate(String authorization) {
        byte[] expected = authorization().getBytes(StandardCharsets.UTF_8);
        if (authorization == null || !MessageDigest.isEqual(expected, authorization.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "LangGraph 服务间凭据无效");
        }
    }
}
