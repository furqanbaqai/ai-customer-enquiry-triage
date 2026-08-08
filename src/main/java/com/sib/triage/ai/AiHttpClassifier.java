package com.sib.triage.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sib.triage.config.AppConfig;
import com.sib.triage.domain.CustomerEnquiry;
import com.sib.triage.domain.TriageResult;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Supplier;

public final class AiHttpClassifier implements TriageClassifier, AutoCloseable {
    private final HttpClient client;
    private final ObjectMapper mapper;
    private final AppConfig.HttpEndpoint endpoint;
    private final Retry retry;
    private final CircuitBreaker circuitBreaker;
    private final ScheduledExecutorService retryScheduler;

    public AiHttpClassifier(HttpClient client, ObjectMapper mapper, AppConfig.HttpEndpoint endpoint) {
        this.client = client; this.mapper = mapper; this.endpoint = endpoint;
        this.retryScheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("ai-retry-", 0).factory());
        this.retry = Retry.of("ai-classification", RetryConfig.custom()
                .maxAttempts(3).waitDuration(Duration.ofMillis(300)).retryExceptions(AiServiceException.class).build());
        this.circuitBreaker = CircuitBreaker.of("ai-classification", CircuitBreakerConfig.custom()
                .failureRateThreshold(50).minimumNumberOfCalls(5).slidingWindowSize(10).waitDurationInOpenState(Duration.ofSeconds(30)).build());
    }

    @Override
    public CompletionStage<TriageResult> classify(CustomerEnquiry enquiry, String correlationId) {
        Supplier<CompletionStage<TriageResult>> operation = () -> invoke(enquiry, correlationId);
        var resilient = Retry.decorateCompletionStage(retry, retryScheduler,
                CircuitBreaker.decorateCompletionStage(circuitBreaker, operation));
        return resilient.get();
    }

    @Override public void close() { retryScheduler.close(); }

    private CompletionStage<TriageResult> invoke(CustomerEnquiry enquiry, String correlationId) {
        try {
            var body = mapper.writeValueAsString(new AiRequest(enquiry.enquiryId(), enquiry.customerId(), enquiry.message()));
            var request = HttpRequest.newBuilder(URI.create(endpoint.url())).timeout(endpoint.timeout())
                    .header("Content-Type", "application/json").header("Accept", "application/json")
                    .header("Authorization", "Bearer " + endpoint.apiKey()).header("X-Correlation-ID", correlationId)
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            return client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
                if (response.statusCode() < 200 || response.statusCode() >= 300)
                    throw new AiServiceException("AI service returned HTTP " + response.statusCode());
                try { return mapper.readValue(response.body(), TriageResult.class); }
                catch (Exception e) { throw new AiServiceException("Invalid AI response", e); }
            });
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e instanceof AiServiceException ? e : new AiServiceException("Cannot create AI request", e));
        }
    }

    private record AiRequest(String enquiryId, String customerId, String message) {}
    public static final class AiServiceException extends RuntimeException {
        public AiServiceException(String message) { super(message); }
        public AiServiceException(String message, Throwable cause) { super(message, cause); }
    }
}
