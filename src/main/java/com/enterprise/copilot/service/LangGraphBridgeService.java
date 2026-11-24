package com.enterprise.copilot.service;

import com.enterprise.copilot.dto.ChatRequest;
import com.enterprise.copilot.security.LangGraphInternalAuth;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static com.enterprise.copilot.service.ChatRequestSupport.elapsedMillis;

@Service
public class LangGraphBridgeService {

    private final ChatRequestSupport requests;
    private final KnowledgeBaseService knowledge;
    private final LangGraphInternalAuth auth;
    private final RestClient client;

    @Autowired
    public LangGraphBridgeService(ChatRequestSupport requests, KnowledgeBaseService knowledge, LangGraphInternalAuth auth,
                                  @Value("${copilot.langgraph.base-url}") String baseUrl,
                                  @Value("${copilot.langgraph.read-timeout}") Duration readTimeout) {
        this(requests, knowledge, auth, workflowClient(baseUrl, readTimeout));
    }

    LangGraphBridgeService(ChatRequestSupport requests, KnowledgeBaseService knowledge,
                           LangGraphInternalAuth auth, RestClient client) {
        this.requests = requests;
        this.knowledge = knowledge;
        this.auth = auth;
        this.client = client;
    }

    public WorkflowResponse delegateToLangGraph(ChatRequest request) {
        long startedAt = System.nanoTime();
        WorkflowResponse response = null;
        RuntimeException failure = null;
        boolean delegated = false;
        try {
            requests.checkInput(request);
            String authorization = auth.authorization();
            String policies = knowledge.buildWorkflowRagContext(request.getMessage(), request.getUserId(), true);
            delegated = true;
            response = invoke(request, authorization, policies);
            return response;
        } catch (RuntimeException error) {
            failure = error;
            throw error;
        } finally {
            String trace = response != null ? "LangGraph:" + response.routeTaken()
                    : delegated ? "LangGraph:ERROR" : "NONE";
            requests.audit(request, response == null ? null : response.finalReply(), trace,
                    elapsedMillis(startedAt), failure, "[LangGraph] ");
        }
    }

    private WorkflowResponse invoke(ChatRequest request, String authorization, String policies) {
        try {
            WorkflowResponse response = client.post().uri("/workflow/execute")
                    .header(HttpHeaders.AUTHORIZATION, authorization)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("session_id", request.getSessionId(), "user_id", request.getUserId(),
                            "message", request.getMessage(), "rag_policy", policies))
                    .retrieve().body(WorkflowResponse.class);
            if (response == null || response.routeTaken() == null || response.nodeTrace() == null
                    || response.nodeTrace().isEmpty() || !StringUtils.hasText(response.finalReply())) {
                throw new RestClientException("Invalid workflow response");
            }
            return response;
        } catch (RestClientResponseException error) {
            if (error.getStatusCode().value() == 503) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "LangGraph 工作流暂不可用", error);
            }
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "LangGraph 工作流执行失败", error);
        } catch (RestClientException error) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "LangGraph 工作流无法连接或响应无效", error);
        }
    }

    private static RestClient workflowClient(String baseUrl, Duration readTimeout) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(readTimeout);
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    public enum Route { AUTO_TICKET, HUMAN_ESCALATION, INFO_ONLY }

    public record WorkflowResponse(@JsonProperty("route_taken") Route routeTaken,
                                   @JsonProperty("node_trace") List<String> nodeTrace,
                                   @JsonProperty("final_reply") String finalReply,
                                   @JsonProperty("human_review") HumanReview humanReview) {
    }

    public record HumanReview(@JsonProperty("user_id") String userId, @JsonProperty("order_no") String orderNo,
                              String reason, String status) {
    }
}
