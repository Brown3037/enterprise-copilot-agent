package com.enterprise.copilot.config;

import org.springframework.ai.document.Document;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Locale;

@Configuration(proxyBeanMethods = false)
public class RagConfig {

    @Bean
    public TokenTextSplitter policyTextSplitter() {
        return new TokenTextSplitter(200, 40, 5, 1000, true);
    }

    @Bean
    public KnowledgeEmbedding knowledgeEmbedding(
            @Value("${copilot.knowledge.embedding.base-url}") String baseUrl,
            @Value("${copilot.knowledge.embedding.api-key}") String apiKey,
            @Value("${copilot.knowledge.embedding.model}") String model,
            @Value("${copilot.knowledge.embedding.path}") String path) {
        if (!StringUtils.hasText(baseUrl) || !StringUtils.hasText(apiKey) || !StringUtils.hasText(model)) {
            return new KnowledgeEmbedding(new LocalEmbeddingModel(), "local-ngram-128-v1");
        }
        OpenAiApi api = OpenAiApi.builder().baseUrl(baseUrl).apiKey(apiKey).embeddingsPath(path).build();
        EmbeddingModel remote = new OpenAiEmbeddingModel(api, MetadataMode.NONE,
                OpenAiEmbeddingOptions.builder().model(model).build());
        return new KnowledgeEmbedding(remote, "openai-compatible|" + baseUrl + "|" + path + "|" + model);
    }

    public record KnowledgeEmbedding(EmbeddingModel model, String fingerprint) {
    }

    private static final class LocalEmbeddingModel implements EmbeddingModel {
        private static final int DIMENSIONS = 128;

        @Override
        public int dimensions() {
            return DIMENSIONS;
        }

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            var embeddings = new ArrayList<Embedding>();
            for (String text : request.getInstructions()) {
                embeddings.add(new Embedding(embed(text), embeddings.size()));
            }
            return new EmbeddingResponse(embeddings);
        }

        @Override
        public float[] embed(Document document) {
            return embed(document.getText());
        }

        @Override
        public float[] embed(String text) {
            String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
            int[] codePoints = normalized.codePoints().filter(Character::isLetterOrDigit).toArray();
            float[] vector = new float[DIMENSIONS];
            for (int i = 0; i < codePoints.length; i++) {
                addFeature(vector, codePoints[i], 1);
                if (i + 1 < codePoints.length) {
                    addFeature(vector, codePoints[i] * 1_000_003 + codePoints[i + 1], 2);
                }
            }
            double squaredNorm = 0;
            for (float value : vector) {
                squaredNorm += value * value;
            }
            if (squaredNorm > 0) {
                double norm = Math.sqrt(squaredNorm);
                for (int i = 0; i < vector.length; i++) {
                    vector[i] /= (float) norm;
                }
            }
            return vector;
        }

        private static void addFeature(float[] vector, int feature, int weight) {
            int hash = feature * 0x9e3779b9;
            hash ^= hash >>> 16;
            vector[hash & (DIMENSIONS - 1)] += hash < 0 ? -weight : weight;
        }
    }
}
