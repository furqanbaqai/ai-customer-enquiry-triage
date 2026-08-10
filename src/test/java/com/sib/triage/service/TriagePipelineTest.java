package com.sib.triage.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sib.triage.domain.TriageResult;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class TriagePipelineTest {
    @Test void classifiesAndPersistsWithCorrelationId() {
        var mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        var result = new TriageResult("CARD_LOST", 9, "NEGATIVE", "Cards", "Urgent", Instant.now());
        var persistedCorrelation = new AtomicReference<String>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var pipeline = new TriagePipeline(mapper,
                    (enquiry, correlation) -> CompletableFuture.completedFuture(result),
                    (enquiry, triage, correlation) -> persistedCorrelation.set(correlation),
                    executor);
            pipeline.process("""
                    {"enquiryId":"e-1","customerId":"c-1","message":"My card is missing",
                     "receivedAt":"2026-08-08T12:00:00Z"}
                    """, "corr-123").toCompletableFuture().join();
        }
        assertEquals("corr-123", persistedCorrelation.get());
    }

    @Test void rejectsMalformedPayload() {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var pipeline = new TriagePipeline(new ObjectMapper(),
                    (e, c) -> CompletableFuture.failedFuture(new AssertionError()), (e, r, c) -> {}, executor);
            assertThrows(Exception.class, () -> pipeline.process("{}", "corr").toCompletableFuture().join());
        }
    }
}
