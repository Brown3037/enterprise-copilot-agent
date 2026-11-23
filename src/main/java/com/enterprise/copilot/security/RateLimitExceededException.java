package com.enterprise.copilot.security;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class RateLimitExceededException extends ResponseStatusException {

    private final HttpHeaders headers;

    public RateLimitExceededException(long retryAfterSeconds) {
        super(HttpStatus.TOO_MANY_REQUESTS, "请求过于频繁，请稍后再试。");
        HttpHeaders retryHeaders = new HttpHeaders();
        retryHeaders.set(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
        this.headers = HttpHeaders.readOnlyHttpHeaders(retryHeaders);
    }

    @Override
    public HttpHeaders getHeaders() {
        return headers;
    }
}
