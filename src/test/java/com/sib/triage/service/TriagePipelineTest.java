package com.sib.triage.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sib.triage.domain.TriageResult;
import com.sib.triage.persistence.TriageRepository;
import com.sib.triage.persistence.SqlServerTriageRepository;
import com.sib.triage.domain.CustomerEnquiry;
import com.sib.triage.ai.AiClassification;
import com.sib.triage.support.ConsoleTestDescription;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.Test;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the orchestration logic that validates, tracks, classifies, and publishes customer
 * enquiries across the triage pipeline.
 */
@ExtendWith(ConsoleTestDescription.class)
class TriagePipelineTest {
    @Test void passesOriginalJsonUnchangedToRepository() {
        var raw = validPayload() + "  \n";
        var captured = new AtomicReference<String>();
        var repository = new TriageRepository() {
            @Override public void registerRequest(CustomerEnquiry e, String json, String c) { captured.set(json); }
            @Override public void updateSuccess(String r, AiClassification a, String c) { }
            @Override public void updateFailure(String r, String e, String response, String c) { }
        };
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var pipeline = new TriagePipeline(new ObjectMapper().registerModule(new JavaTimeModule()),
                    new EnquirySchemaValidator(),
                    (e, c) -> CompletableFuture.completedFuture(new TriageResult("ok", e)), repository,
                    (r, c) -> { }, executor);
            pipeline.process(raw, "corr").toCompletableFuture().join();
        }
        assertSame(raw, captured.get());
    }

    @Test void publishesAiResponseWithCorrelationId() {
        var mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        var publishedCorrelation = new AtomicReference<String>();
        var publishedResult = new AtomicReference<TriageResult>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var pipeline = new TriagePipeline(mapper, new EnquirySchemaValidator(),
                    (enquiry, correlation) -> CompletableFuture.completedFuture(new TriageResult("TEST", enquiry)),
                    repository(),
                    (triage, correlation) -> {
                        publishedCorrelation.set(correlation);
                        publishedResult.set(triage);
                    },
                    executor);
            pipeline.process("""
                    {"meta":{"refNumber":"e-1","channel":"WebSite","reqIssuedAt":"2026-08-08T11:59:00Z"},
                     "customerId":"ABCDEF123456","mobileNumber":"+971 50 123 4567",
                     "firstName":"Sara","lastName":"Khan","emailAddress":"sara@example.com",
                     "message":"My card is missing","receivedAt":"2026-08-08T12:00:00Z"}
                    """, "corr-123").toCompletableFuture().join();
        }
        assertEquals("corr-123", publishedCorrelation.get());
        assertEquals("TEST", publishedResult.get().content());
        assertEquals("e-1", publishedResult.get().customerEnquiry().enquiryId());
    }

    @Test void rejectsMalformedPayload() {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var pipeline = new TriagePipeline(new ObjectMapper(), new EnquirySchemaValidator(),
                    (e, c) -> CompletableFuture.failedFuture(new AssertionError()),
                    repository(), (r, c) -> fail("Invalid requests must not be published"), executor);
            assertThrows(Exception.class, () -> pipeline.process("{}", "corr").toCompletableFuture().join());
        }
    }

    @Test void rejectsInvalidEmailAndDateTimeFormats() {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var pipeline = new TriagePipeline(new ObjectMapper().registerModule(new JavaTimeModule()),
                    new EnquirySchemaValidator(),
                    (e, c) -> CompletableFuture.failedFuture(new AssertionError()),
                    repository(), (r, c) -> fail("Invalid requests must not be published"), executor);
            var invalid = """
                    {"meta":{"refNumber":"e-1","channel":"WebSite","reqIssuedAt":"not-a-date"},
                     "mobileNumber":"+971501234567","firstName":"Sara","lastName":"Khan",
                     "emailAddress":"not-an-email","message":"Help","receivedAt":"2026-08-08T12:00:00Z"}
                    """;
            var error = assertThrows(Exception.class,
                    () -> pipeline.process(invalid, "corr").toCompletableFuture().join());
            assertInstanceOf(TriagePipeline.InvalidEnquiryException.class, error.getCause());
        }
    }

    @Test void doesNotPublishWhenClassificationFails() {
        var published = new java.util.concurrent.atomic.AtomicBoolean();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var pipeline = new TriagePipeline(new ObjectMapper().registerModule(new JavaTimeModule()),
                    new EnquirySchemaValidator(),
                    (e, c) -> CompletableFuture.failedFuture(new IllegalStateException("AI unavailable")),
                    repository(), (r, c) -> published.set(true), executor);

            assertThrows(Exception.class, () -> pipeline.process(validPayload(), "corr").toCompletableFuture().join());
        }
        assertFalse(published.get());
    }

    @Test void propagatesResultPublishingFailure() {
        var successes = new java.util.concurrent.atomic.AtomicInteger();
        var failures = new java.util.concurrent.atomic.AtomicInteger();
        var repository = new TriageRepository() {
            @Override public void registerRequest(CustomerEnquiry e, String json, String c) { }
            @Override public void updateSuccess(String r, AiClassification a, String c) {
                successes.incrementAndGet();
            }
            @Override public void updateFailure(String r, String e, String raw, String c) {
                failures.incrementAndGet();
            }
        };
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var pipeline = new TriagePipeline(new ObjectMapper().registerModule(new JavaTimeModule()),
                    new EnquirySchemaValidator(),
                    (e, c) -> CompletableFuture.completedFuture(new TriageResult("response", e)),
                    repository,
                    (r, c) -> { throw new IllegalStateException("MQ result queue unavailable"); }, executor);

            assertThrows(Exception.class, () -> pipeline.process(validPayload(), "corr").toCompletableFuture().join());
        }
        assertEquals(1, successes.get(), "One successful execution must create one history record");
        assertEquals(0, failures.get(), "A publisher failure must not create a second history record");
    }

    @Test void persistenceFailureStopsAiProcessing() {
        var aiInvoked = new java.util.concurrent.atomic.AtomicBoolean();
        var repository = new TriageRepository() {
            @Override public void registerRequest(CustomerEnquiry e, String json, String c) {
                throw new SqlServerTriageRepository.PersistenceException("constraint violation");
            }
            @Override public void updateSuccess(String r, AiClassification a, String c) { }
            @Override public void updateFailure(String r, String e, String raw, String c) { }
        };
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var pipeline = new TriagePipeline(new ObjectMapper().registerModule(new JavaTimeModule()),
                    new EnquirySchemaValidator(), (e, c) -> {
                        aiInvoked.set(true);
                        return CompletableFuture.completedFuture(new TriageResult("response", e));
                    }, repository, (r, c) -> fail("Must not publish"), executor);
            assertThrows(Exception.class, () -> pipeline.process(validPayload(), "corr").toCompletableFuture().join());
        }
        assertFalse(aiInvoked.get());
    }

    @Test void aiFailureIsPersistedBeforePropagation() {
        var persistedError = new AtomicReference<String>();
        var failureWrites = new java.util.concurrent.atomic.AtomicInteger();
        var repository = new TriageRepository() {
            @Override public void registerRequest(CustomerEnquiry e, String json, String c) { }
            @Override public void updateSuccess(String r, AiClassification a, String c) { }
            @Override public void updateFailure(String ref, String error, String raw, String correlation) {
                persistedError.set(error);
                failureWrites.incrementAndGet();
            }
        };
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var pipeline = new TriagePipeline(new ObjectMapper().registerModule(new JavaTimeModule()),
                    new EnquirySchemaValidator(),
                    (e, c) -> CompletableFuture.failedFuture(new IllegalStateException("AI unavailable")),
                    repository, (r, c) -> fail("Must not publish"), executor);
            assertThrows(Exception.class, () -> pipeline.process(validPayload(), "corr").toCompletableFuture().join());
        }
        assertEquals("AI unavailable", persistedError.get());
        assertEquals(1, failureWrites.get(), "One failed execution must create one history record");
    }

    private static String validPayload() {
        return """
                {"meta":{"refNumber":"e-1","channel":"WebSite","reqIssuedAt":"2026-08-08T11:59:00Z"},
                 "customerId":"ABCDEF123456","mobileNumber":"+971 50 123 4567",
                 "firstName":"Sara","lastName":"Khan","emailAddress":"sara@example.com",
                 "message":"My card is missing","receivedAt":"2026-08-08T12:00:00Z"}
                """;
    }

    private static TriageRepository repository() {
        return new TriageRepository() {
            @Override public void registerRequest(CustomerEnquiry e, String json, String c) { }
            @Override public void updateSuccess(String r, AiClassification a, String c) { }
            @Override public void updateFailure(String r, String e, String raw, String c) { }
        };
    }
}
