package com.enterprise.copilot.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.copilot.entity.BizOrder;
import com.enterprise.copilot.mapper.BizOrderMapper;
import com.enterprise.copilot.service.PolicyCorpus.ProductFamily;
import com.enterprise.copilot.service.PolicyCorpus.Rule;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class KnowledgeBaseService {

    public static final String NO_POLICY_EVIDENCE = "当前知识库没有找到足够的政策依据。请补充商品类型、订单号或具体售后问题，也可以联系人工客服核实。";
    private static final double CONFIDENCE_THRESHOLD = 0.65;
    private static final int VECTOR_TOP_K = 5;
    private static final int RESULT_LIMIT = 2;
    private static final Pattern ORDER_NUMBER = Pattern.compile("\\bORD-[A-Z0-9][A-Z0-9-]{0,27}\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern QUANTITY = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(天|日|年|小时|GB|%)");
    private static final List<List<String>> ACTIONS = List.of(
            List.of("退款", "退货", "退掉", "退费", "退订", "REFUND"),
            List.of("换货", "换新", "更换", "EXCHANGE"),
            List.of("投诉", "人工评估", "COMPLAINT"));
    private static final List<List<String>> STATES = List.of(
            List.of("待发货", "未发货", "还没发货", "没发货", "未开通", "还没开通", "尚未开通", "PENDING_SHIP"),
            List.of("已发货", "已开通", "已经发货", "开通后", "SHIPPED"),
            List.of("已完成", "已经完成", "完成了", "施工完成", "完工", "COMPLETED"),
            List.of("签收", "未激活", "外包装"),
            List.of("质保", "故障", "换新"));

    private final PolicyCorpus corpus;
    private final BizOrderMapper orders;

    public List<PolicyHit> retrieveRelevantPolicies(String question) {
        return retrieve(question, Set.of());
    }

    public PolicyContext buildRagContext(String question, String userId) {
        boolean needsPolicy = requiresPolicy(question);
        if (!needsPolicy) {
            return new PolicyContext(false, "");
        }
        List<BizOrder> ownedOrders = findOwnedOrders(question, userId);
        Set<ProductFamily> orderFamilies = ownedOrders.stream()
                .map(order -> ProductFamily.forProduct(order.getProductName()))
                .filter(family -> family != ProductFamily.OTHER)
                .collect(Collectors.toSet());
        String orderFacts = ownedOrders.stream()
                .map(order -> order.getProductName() + " " + order.getStatus())
                .collect(Collectors.joining(" "));
        String retrievalQuery = ORDER_NUMBER.matcher(question).replaceAll("订单") + " " + orderFacts;
        List<PolicyHit> evidence = retrieve(retrievalQuery, orderFamilies);
        if (evidence.isEmpty()) {
            return new PolicyContext(true, "");
        }
        String formatted = evidence.stream()
                .map(hit -> citation(hit.metadata()) + " " + hit.content())
                .collect(Collectors.joining("\n\n"));
        return new PolicyContext(true, formatted);
    }

    public Optional<String> ticketRestriction(BizOrder order, String ticketType) {
        ProductFamily family = ProductFamily.forProduct(order.getProductName());
        Optional<Rule> matching = corpus.snapshot().rules().stream()
                .filter(rule -> rule.family() == family && ticketType.equals(rule.ticketType()))
                .filter(rule -> rule.orderStates().contains("ANY") || rule.orderStates().contains(order.getStatus()))
                .max(Comparator.comparing(Rule::decision));
        if (matching.isEmpty()) {
            return Optional.of("当前政策缺少适用的自助建单条款，请联系人工客服确认。");
        }
        Rule rule = matching.get();
        return switch (rule.decision()) {
            case ALLOW -> Optional.empty();
            case REVIEW -> Optional.of("申请条件需人工核验。依据 " + citation(rule.document().getMetadata())
                    + " " + rule.document().getText());
            case DENY -> Optional.of("当前政策不支持该自助申请。依据 " + citation(rule.document().getMetadata())
                    + " " + rule.document().getText());
        };
    }

    public ReloadResult reloadKnowledgeBase() {
        PolicyCorpus.Snapshot snapshot = corpus.reload();
        return new ReloadResult(snapshot.revision(), snapshot.documents().size());
    }

    private List<PolicyHit> retrieve(String question, Set<ProductFamily> orderFamilies) {
        String query = normalize(question);
        Features queryFeatures = Features.from(query);
        if (queryFeatures.bigrams().isEmpty()) {
            return List.of();
        }
        PolicyCorpus.Snapshot snapshot = corpus.snapshot();
        Set<ProductFamily> families = orderFamilies.isEmpty() ? queryFeatures.families() : orderFamilies;
        List<Document> vectorHits = snapshot.store().similaritySearch(SearchRequest.builder().query(query)
                .topK(VECTOR_TOP_K).similarityThreshold(0.0).build());
        Map<String, Double> similarity = new HashMap<>();
        if (vectorHits != null) {
            for (Document document : vectorHits) {
                if (document.getScore() != null) {
                    similarity.put(document.getId(), document.getScore());
                }
            }
        }

        return snapshot.documents().stream()
                .filter(doc -> families.isEmpty() || families.contains(product(doc)))
                .map(doc -> new RankedPolicy(doc, combinedScore(queryFeatures, families, doc,
                        similarity.getOrDefault(doc.getId(), 0.0))))
                .filter(hit -> hit.score() >= CONFIDENCE_THRESHOLD)
                .sorted(Comparator.comparingDouble(RankedPolicy::score).reversed()
                        .thenComparing(hit -> hit.document().getId()))
                .limit(RESULT_LIMIT)
                .map(hit -> new PolicyHit(hit.document().getText(), Math.min(1.0, hit.score()),
                        hit.document().getMetadata()))
                .toList();
    }

    private static double combinedScore(Features query, Set<ProductFamily> families, Document doc, double cosine) {
        Features candidate = Features.from(normalize(doc.getText()));
        double score = 0.60 * cosine + 0.55 * coverage(query.bigrams(), candidate.bigrams());
        if (families.contains(product(doc))) {
            score += 0.42;
        }
        if (overlaps(query.actions(), candidate.actions())) {
            score += 0.20;
        }
        if (overlaps(query.states(), candidate.states())) {
            score += 0.17;
        }
        if (overlaps(query.quantities(), candidate.quantities())) {
            score += 0.18;
        }
        return Math.max(cosine, score);
    }

    private List<BizOrder> findOwnedOrders(String question, String userId) {
        List<String> numbers = ORDER_NUMBER.matcher(question).results()
                .map(match -> match.group().toUpperCase(Locale.ROOT)).distinct().limit(5).toList();
        if (numbers.isEmpty()) {
            return List.of();
        }
        return orders.selectList(Wrappers.<BizOrder>lambdaQuery()
                        .select(BizOrder::getUserId, BizOrder::getProductName, BizOrder::getStatus)
                        .eq(BizOrder::getUserId, userId)
                        .in(BizOrder::getOrderNo, numbers))
                .stream().filter(order -> userId.equals(order.getUserId())).toList();
    }

    private static boolean requiresPolicy(String question) {
        String text = normalize(question);
        if (text.contains("工单") && (text.contains("查询") || text.contains("状态") || text.contains("进度"))
                && !(text.contains("创建") || text.contains("申请") || text.contains("提交"))) {
            return false;
        }
        return !groups(text, ACTIONS).isEmpty()
                || List.of("政策", "质保", "保修", "售后", "无理由").stream().anyMatch(text::contains);
    }

    private static ProductFamily product(Document document) {
        return ProductFamily.valueOf(document.getMetadata().get("product").toString());
    }

    private static String citation(Map<String, Object> metadata) {
        return "[" + metadata.get("source") + " · " + metadata.get("section") + " · " + metadata.get("clause") + "]";
    }

    private static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKC).toUpperCase(Locale.ROOT);
    }

    private static Set<Integer> groups(String text, List<List<String>> alternatives) {
        Set<Integer> hits = new HashSet<>();
        for (int i = 0; i < alternatives.size(); i++) {
            if (alternatives.get(i).stream().anyMatch(text::contains)) {
                hits.add(i);
            }
        }
        return hits;
    }

    private static boolean overlaps(Set<?> first, Set<?> second) {
        return first.stream().anyMatch(second::contains);
    }

    private static double coverage(Set<Integer> query, Set<Integer> candidate) {
        return query.stream().filter(candidate::contains).count() / (double) query.size();
    }

    private record Features(Set<ProductFamily> families, Set<Integer> actions, Set<Integer> states,
                            Set<String> quantities, Set<Integer> bigrams) {
        static Features from(String text) {
            Set<ProductFamily> families = new HashSet<>();
            for (ProductFamily family : ProductFamily.values()) {
                if (family.matches(text)) {
                    families.add(family);
                }
            }
            Set<String> quantities = QUANTITY.matcher(text).results()
                    .map(match -> match.group(1) + match.group(2).replace("日", "天"))
                    .collect(Collectors.toSet());
            int[] letters = text.codePoints().filter(Character::isLetterOrDigit).toArray();
            Set<Integer> bigrams = new HashSet<>();
            for (int i = 1; i < letters.length; i++) {
                bigrams.add(letters[i - 1] * 1_000_003 + letters[i]);
            }
            return new Features(families, groups(text, ACTIONS), groups(text, STATES), quantities, bigrams);
        }
    }

    private record RankedPolicy(Document document, double score) {
    }

    public record PolicyHit(String content, double score, Map<String, Object> metadata) {
    }

    public record PolicyContext(boolean needsPolicy, String evidence) {
        public boolean insufficient() {
            return needsPolicy && evidence.isBlank();
        }
    }

    public record ReloadResult(String revision, int chunks) {
    }
}
