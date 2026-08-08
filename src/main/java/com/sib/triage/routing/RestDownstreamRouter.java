package com.sib.triage.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sib.triage.config.AppConfig;
import com.sib.triage.domain.CustomerEnquiry;
import com.sib.triage.domain.RoutingDecision;
import com.sib.triage.domain.TriageResult;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class RestDownstreamRouter implements DownstreamRouter {
    private final HttpClient client;
    private final ObjectMapper mapper;
    private final AppConfig.HttpEndpoint endpoint;
    private final int urgencyThreshold;

    public RestDownstreamRouter(HttpClient client, ObjectMapper mapper, AppConfig.HttpEndpoint endpoint, int urgencyThreshold) {
        this.client = client; this.mapper = mapper; this.endpoint = endpoint; this.urgencyThreshold = urgencyThreshold;
    }

    @Override
    public CompletionStage<Void> route(CustomerEnquiry enquiry, TriageResult result, String correlationId) {
        return switch (RoutingDecision.from(result, urgencyThreshold)) {
            case RoutingDecision.Standard ignored -> CompletableFuture.completedFuture(null);
            case RoutingDecision.Escalated escalation -> send(enquiry, result, escalation, correlationId);
        };
    }

    private CompletionStage<Void> send(CustomerEnquiry enquiry, TriageResult result,
                                       RoutingDecision.Escalated escalation, String correlationId) {
        try {
            var body = mapper.writeValueAsString(new RoutingRequest(enquiry.enquiryId(), enquiry.customerId(),
                    enquiry.message(), escalation.team(), escalation.urgencyScore(), result.intent(), result.sentiment()));
            var builder = HttpRequest.newBuilder(URI.create(endpoint.url())).timeout(endpoint.timeout())
                    .header("Content-Type", "application/json").header("X-Correlation-ID", correlationId);
            if (!endpoint.apiKey().isBlank()) builder.header("Authorization", "Bearer " + endpoint.apiKey());
            return client.sendAsync(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.discarding()).thenApply(response -> {
                if (response.statusCode() < 200 || response.statusCode() >= 300)
                    throw new RoutingException("Routing API returned HTTP " + response.statusCode());
                return null;
            });
        } catch (Exception e) { return CompletableFuture.failedFuture(new RoutingException("Cannot route enquiry", e)); }
    }

    private record RoutingRequest(String enquiryId, String customerId, String message, String team,
                                  int urgencyScore, String intent, String sentiment) {}
    public static final class RoutingException extends RuntimeException {
        public RoutingException(String message) { super(message); }
        public RoutingException(String message, Throwable cause) { super(message, cause); }
    }
}
