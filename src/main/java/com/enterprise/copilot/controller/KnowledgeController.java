package com.enterprise.copilot.controller;

import com.enterprise.copilot.common.Result;
import com.enterprise.copilot.service.KnowledgeBaseService;
import com.enterprise.copilot.service.KnowledgeBaseService.PolicyHit;
import com.enterprise.copilot.service.KnowledgeBaseService.ReloadResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Set;

@Slf4j
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {

    private static final Set<String> LOOPBACK = Set.of("127.0.0.1", "::1", "0:0:0:0:0:0:0:1");
    private final KnowledgeBaseService knowledge;
    private final String reloadToken;

    public KnowledgeController(KnowledgeBaseService knowledge,
                               @Value("${copilot.knowledge.reload-token}") String reloadToken) {
        this.knowledge = knowledge;
        this.reloadToken = reloadToken;
    }

    @GetMapping("/search")
    public Result<List<PolicyHit>> searchPolicy(@RequestParam @NotBlank @Size(max = 16000) String query) {
        return Result.success(knowledge.retrieveRelevantPolicies(query));
    }

    @PostMapping("/reload")
    public Result<ReloadResult> reload(HttpServletRequest request,
            @RequestHeader(name = "X-Knowledge-Reload-Token", defaultValue = "") String suppliedToken) {
        boolean authorized = reloadToken.isBlank() ? LOOPBACK.contains(request.getRemoteAddr())
                : MessageDigest.isEqual(reloadToken.getBytes(StandardCharsets.UTF_8),
                        suppliedToken.getBytes(StandardCharsets.UTF_8));
        if (!authorized) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "未授权刷新知识库");
        }
        try {
            return Result.success(knowledge.reloadKnowledgeBase());
        } catch (RuntimeException exception) {
            log.warn("售后知识库刷新失败", exception);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "知识库刷新失败，继续使用原有索引");
        }
    }
}
