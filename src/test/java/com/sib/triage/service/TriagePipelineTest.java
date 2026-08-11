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
    @Test void classifiesAndPersistsWithCorrelationId() {
        var mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        var result = new TriageResult("TEST");
        var persistedCorrelation = new AtomicReference<String>();
        var persistedResult = new AtomicReference<TriageResult>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var pipeline = new TriagePipeline(mapper, new EnquirySchemaValidator(),
                    (enquiry, correlation) -> CompletableFuture.completedFuture(result),
                    (enquiry, triage, correlation) -> {
                        persistedCorrelation.set(correlation);
                        persistedResult.set(triage);
                    },
                    executor);
            pipeline.process("""
                    {"meta":{"refNumber":"e-1","channel":"WebSite","reqIssuedAt":"2026-08-08T11:59:00Z"},
                     "customerId":"ABCDEF123456","mobileNumber":"+971 50 123 4567",
                     "firstName":"Sara","lastName":"Khan","emailAddress":"sara@example.com",
                     "message":"My card is missing","receivedAt":"2026-08-08T12:00:00Z"}
                    """, "corr-123").toCompletableFuture().join();
        }
        assertEquals("corr-123", persistedCorrelation.get());
        assertSame(result, persistedResult.get());
    }

    @Test void rejectsMalformedPayload() {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var pipeline = new TriagePipeline(new ObjectMapper(), new EnquirySchemaValidator(),
                    (e, c) -> CompletableFuture.failedFuture(new AssertionError()), (e, r, c) -> {}, executor);
            assertThrows(Exception.class, () -> pipeline.process("{}", "corr").toCompletableFuture().join());
        }
    }

    @Test void rejectsInvalidEmailAndDateTimeFormats() {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var pipeline = new TriagePipeline(new ObjectMapper().registerModule(new JavaTimeModule()),
                    new EnquirySchemaValidator(),
                    (e, c) -> CompletableFuture.failedFuture(new AssertionError()), (e, r, c) -> {}, executor);
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
}
