package com.sib.triage.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sib.triage.config.AppConfig;
import com.sib.triage.domain.CustomerEnquiry;
import com.sib.triage.domain.TriageResult;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AiHttpClassifier implements TriageClassifier {
    private static final Logger LOGGER = LoggerFactory.getLogger(AiHttpClassifier.class);
    private static final String PROMPT_CLASSPATH = "com/sib/triage/ai/prompts/TriagePipelinePromptv1";
    private static final String PROMPT_CLASSPATH_MD = PROMPT_CLASSPATH + ".md";
    private static final String PROMPT_TEMPLATE;

    static {
        String prompt = null;
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(PROMPT_CLASSPATH)) {
            if (in != null) prompt = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("Failed to read prompt {}: {}", PROMPT_CLASSPATH, e.getMessage());
        }
        if (prompt == null) {
            try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(PROMPT_CLASSPATH_MD)) {
                if (in != null) prompt = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                LOGGER.warn("Failed to read prompt {}: {}", PROMPT_CLASSPATH_MD, e.getMessage());
            }
        }
        if (prompt == null) throw new IllegalStateException("Missing required prompt resource: " + PROMPT_CLASSPATH + "(.md)");
        PROMPT_TEMPLATE = prompt;
        LOGGER.info("Loaded AI prompt template (length={} chars)", PROMPT_TEMPLATE.length());
    }

    private final HttpClient client;
    private final ObjectMapper mapper;
    private final AppConfig.HttpEndpoint endpoint;

    public AiHttpClassifier(HttpClient client, ObjectMapper mapper, AppConfig.HttpEndpoint endpoint) {
        this.client = client; this.mapper = mapper; this.endpoint = endpoint;
    }

    @Override
    public CompletionStage<TriageResult> classify(CustomerEnquiry enquiry, String correlationId) {
        return invoke(enquiry, correlationId);
    }

    private CompletionStage<TriageResult> invoke(CustomerEnquiry enquiry, String correlationId) {
        try {
            // Replace placeholder in prompt with incoming message content
            var prompt = PROMPT_TEMPLATE.replace("{INCOMING_TEXT}", enquiry.message() == null ? "" : enquiry.message());

            // Build request payload according to required structure
            Map<String, Object> payload = new HashMap<>();
            payload.put("model", "/models/qwen2.5-3b-instruct-q4_k_m.gguf");
            Map<String, String> message = new HashMap<>();
            message.put("role", "user");
            message.put("content", prompt);
            payload.put("messages", List.of(message));
            payload.put("temperature", 0.1);
            payload.put("max_tokens", 200);
            payload.put("top_p", 0.9);
            payload.put("stream", false);

            var body = mapper.writeValueAsString(payload);

            var request = HttpRequest.newBuilder(URI.create(endpoint.url())).timeout(endpoint.timeout())
                    .header("Content-Type", "application/json").header("Accept", "application/json")
                    .header("Authorization", "Bearer " + endpoint.apiKey()).header("X-Correlation-ID", correlationId)
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            return client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
                if (response.statusCode() < 200 || response.statusCode() >= 300)
                    throw new AiServiceException("AI service returned HTTP " + response.statusCode());
                try { return parseResponse(response.body()); }
                catch (Exception e) { throw new AiServiceException("Invalid AI response", e); }
            });
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e instanceof AiServiceException ? e : new AiServiceException("Cannot create AI request", e));
        }
    }

    TriageResult parseResponse(String responseBody) throws IOException {
        var response = mapper.readTree(responseBody);
        var choices = response.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new IOException("AI response does not contain choices");
        }

        var content = choices.path(0).path("message").path("content");
        if (!content.isTextual() || content.textValue().isBlank()) {
            throw new IOException("AI response does not contain message content");
        }

        var classification = mapper.readTree(content.textValue());
        var category = requiredText(classification, "category");
        var subcategory = requiredText(classification, "subcategory");
        return new TriageResult(category, 0, "UNKNOWN", category, subcategory, Instant.now());
    }

    private static String requiredText(com.fasterxml.jackson.databind.JsonNode object, String field) throws IOException {
        var value = object.path(field);
        if (!value.isTextual() || value.textValue().isBlank()) {
            throw new IOException("AI message content does not contain " + field);
        }
        return value.textValue();
    }

    public static final class AiServiceException extends RuntimeException {
        public AiServiceException(String message) { super(message); }
        public AiServiceException(String message, Throwable cause) { super(message, cause); }
    }
}
