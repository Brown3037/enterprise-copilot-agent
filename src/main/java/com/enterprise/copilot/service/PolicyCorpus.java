package com.enterprise.copilot.service;

import com.enterprise.copilot.config.RagConfig.KnowledgeEmbedding;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Slf4j
@Component
class PolicyCorpus {

    private static final Pattern CLAUSE_ID = Pattern.compile("^([A-Z][A-Z0-9]*(?:-[A-Z0-9]+)+)\\s+");
    private static final Pattern RULE = Pattern.compile("(?m)^\\h*<!--\\h*rule:\\h*(.*?)\\h*-->\\h*$");
    private static final Set<String> TICKET_TYPES = Set.of("REFUND", "EXCHANGE", "COMPLAINT");
    private static final Set<String> ORDER_STATES = Set.of("ANY", "PENDING_SHIP", "SHIPPED", "COMPLETED", "REFUNDED");

    private final Resource policy;
    private final Path cacheDirectory;
    private final TokenTextSplitter splitter;
    private final KnowledgeEmbedding embedding;
    private volatile Snapshot current;

    PolicyCorpus(@Value("${copilot.knowledge.policy}") Resource policy,
                 @Value("${copilot.knowledge.cache-directory}") Path cacheDirectory,
                 TokenTextSplitter splitter, KnowledgeEmbedding embedding) {
        this.policy = policy;
        this.cacheDirectory = cacheDirectory;
        this.splitter = splitter;
        this.embedding = embedding;
    }

    @PostConstruct
    void initialize() {
        reload();
    }

    Snapshot snapshot() {
        return current;
    }

    synchronized Snapshot reload() {
        byte[] content;
        try (var stream = policy.getInputStream()) {
            content = stream.readAllBytes();
        } catch (IOException exception) {
            throw new IllegalStateException("无法读取售后政策文档", exception);
        }

        String revision = revision(content);
        if (current != null && current.revision().equals(revision)) {
            return current;
        }
        String source = policy.getFilename() == null ? "after_sales_policy.md" : policy.getFilename();
        List<Document> documents = readDocuments(content, source);
        List<Rule> rules = readRules(new String(content, StandardCharsets.UTF_8), documents);
        VectorStore store = loadStore(documents, revision);
        current = new Snapshot(revision, documents, rules, store);
        log.info("售后知识库已加载 | 片段={} | 执行规则={}", documents.size(), rules.size());
        return current;
    }

    private List<Document> readDocuments(byte[] content, String source) {
        var config = MarkdownDocumentReaderConfig.builder()
                .withHorizontalRuleCreateDocument(true).withIncludeBlockquote(true).build();
        var reader = new MarkdownDocumentReader(new ByteArrayResource(content), config);
        List<Document> chunks = new ArrayList<>();
        int index = 0;
        for (Document document : reader.get()) {
            if (document.getText().isBlank() || "code_block".equals(document.getMetadata().get("category"))) {
                continue;
            }
            String heading = document.getMetadata().getOrDefault("title", "补充条款").toString();
            var match = CLAUSE_ID.matcher(heading);
            boolean labelled = match.find();
            String clauseId = labelled ? match.group(1) : "POLICY-" + ++index;
            String title = labelled ? heading.substring(match.end()) : heading;
            ProductFamily family = ProductFamily.forClause(clauseId, title);
            Map<String, Object> metadata = Map.of("source", source, "clause", clauseId, "title", title,
                    "section", family.section, "product", family.name());
            Document clause = new Document(title + "\n" + document.getText().strip(), metadata);
            for (Document chunk : splitter.apply(List.of(clause))) {
                chunks.add(Document.builder().id(clauseId + "-" + chunks.size())
                        .text(chunk.getText()).metadata(metadata).build());
            }
        }
        if (chunks.isEmpty()) {
            throw new IllegalStateException("售后政策文档没有可索引条款");
        }
        return List.copyOf(chunks);
    }

    private static List<Rule> readRules(String markdown, List<Document> documents) {
        Map<String, Document> clauses = new HashMap<>();
        for (Document doc : documents) {
            clauses.putIfAbsent(doc.getMetadata().get("clause").toString(), doc);
        }
        List<Rule> rules = new ArrayList<>();
        var matcher = RULE.matcher(markdown);
        while (matcher.find()) {
            String[] fields = matcher.group(1).strip().split("\\s+");
            if (fields.length != 4 || !clauses.containsKey(fields[0]) || !TICKET_TYPES.contains(fields[2])) {
                throw new IllegalStateException("售后政策包含无效的建单规则");
            }
            Set<String> states = Set.of(fields[1].split(","));
            Document clause = clauses.get(fields[0]);
            ProductFamily family = ProductFamily.valueOf(clause.getMetadata().get("product").toString());
            if (family == ProductFamily.OTHER || !ORDER_STATES.containsAll(states)) {
                throw new IllegalStateException("售后政策建单规则的商品类别或状态无效");
            }
            rules.add(new Rule(family, states, fields[2], Decision.valueOf(fields[3]), clause));
        }
        return List.copyOf(rules);
    }

    private VectorStore loadStore(List<Document> documents, String revision) {
        SimpleVectorStore store = SimpleVectorStore.builder(embedding.model()).build();
        Path indexFile = cacheDirectory.resolve("after-sales-" + revision + ".json");
        if (Files.isRegularFile(indexFile)) {
            try {
                store.load(indexFile.toFile());
                log.info("已读取售后知识库 JSON 索引");
                return store;
            } catch (RuntimeException exception) {
                log.warn("售后知识库缓存无法读取，将重新索引");
            }
        }
        store.add(documents);
        saveStore(store, indexFile);
        return store;
    }

    private void saveStore(SimpleVectorStore store, Path indexFile) {
        try {
            Files.createDirectories(cacheDirectory);
            Path temporary = Files.createTempFile(cacheDirectory, "after-sales-", ".tmp");
            try {
                store.save(temporary.toFile());
                try {
                    Files.move(temporary, indexFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException exception) {
                    Files.move(temporary, indexFile, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException | RuntimeException exception) {
            log.warn("无法保存售后知识库 JSON 索引，继续使用已建好的内存索引", exception);
        }
    }

    private String revision(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(("policy-index-v1|" + embedding.fingerprint()).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    enum Decision {
        ALLOW, REVIEW, DENY
    }

    enum ProductFamily {
        CLOUD("一、云服务器与软件订阅类产品", "云服务器", "云主机", "软件订阅"),
        HARDWARE("二、智能硬件设备类产品", "硬件", "开发套件"),
        DEDICATED("三、企业专线与定制化网络服务", "专线", "宽带", "定制化网络"),
        OTHER("补充条款");

        private final String section;
        private final List<String> terms;

        ProductFamily(String section, String... terms) {
            this.section = section;
            this.terms = List.of(terms);
        }

        boolean matches(String text) {
            return terms.stream().anyMatch(text::contains);
        }

        static ProductFamily forClause(String id, String title) {
            for (ProductFamily family : values()) {
                if (id.startsWith(family.name() + "-")) {
                    return family;
                }
            }
            return forProduct(title);
        }

        static ProductFamily forProduct(String text) {
            for (ProductFamily family : values()) {
                if (family.matches(text)) {
                    return family;
                }
            }
            return OTHER;
        }
    }

    record Rule(ProductFamily family, Set<String> orderStates, String ticketType, Decision decision, Document document) {
    }

    record Snapshot(String revision, List<Document> documents, List<Rule> rules, VectorStore store) {
    }
}
