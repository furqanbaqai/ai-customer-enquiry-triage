package com.sib.triage.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sib.triage.domain.TriageResult;
import org.junit.jupiter.api.Test;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class TriagePipelineTest {
    @Test void publishesAiResponseWithCorrelationId() {
        var mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        var publishedCorrelation = new AtomicReference<String>();
        var publishedResult = new AtomicReference<TriageResult>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var pipeline = new TriagePipeline(mapper, new EnquirySchemaValidator(),
                    (enquiry, correlation) -> CompletableFuture.completedFuture(new TriageResult("TEST", enquiry)),
                    (enquiry, triage, correlation) -> {},
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
                    (e, r, c) -> {}, (r, c) -> fail("Invalid requests must not be published"), executor);
            assertThrows(Exception.class, () -> pipeline.process("{}", "corr").toCompletableFuture().join());
        }
    }

    @Test void rejectsInvalidEmailAndDateTimeFormats() {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var pipeline = new TriagePipeline(new ObjectMapper().registerModule(new JavaTimeModule()),
                    new EnquirySchemaValidator(),
                    (e, c) -> CompletableFuture.failedFuture(new AssertionError()),
                    (e, r, c) -> {}, (r, c) -> fail("Invalid requests must not be published"), executor);
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
                    (e, r, c) -> {}, (r, c) -> published.set(true), executor);

            assertThrows(Exception.class, () -> pipeline.process(validPayload(), "corr").toCompletableFuture().join());
        }
        assertFalse(published.get());
    }

    @Test void propagatesResultPublishingFailure() {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var pipeline = new TriagePipeline(new ObjectMapper().registerModule(new JavaTimeModule()),
                    new EnquirySchemaValidator(),
                    (e, c) -> CompletableFuture.completedFuture(new TriageResult("response", e)),
                    (e, r, c) -> {},
                    (r, c) -> { throw new IllegalStateException("MQ result queue unavailable"); }, executor);

            assertThrows(Exception.class, () -> pipeline.process(validPayload(), "corr").toCompletableFuture().join());
        }
    }

    private static String validPayload() {
        return """
                {"meta":{"refNumber":"e-1","channel":"WebSite","reqIssuedAt":"2026-08-08T11:59:00Z"},
                 "customerId":"ABCDEF123456","mobileNumber":"+971 50 123 4567",
                 "firstName":"Sara","lastName":"Khan","emailAddress":"sara@example.com",
                 "message":"My card is missing","receivedAt":"2026-08-08T12:00:00Z"}
                """;
    }
}
