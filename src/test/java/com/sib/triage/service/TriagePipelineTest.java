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
    @Test void classifiesPersistsAndRoutesWithCorrelationId() {
        var mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        var result = new TriageResult("CARD_LOST", 9, "NEGATIVE", "Cards", "Urgent", Instant.now());
        var persistedCorrelation = new AtomicReference<String>();
        var routedCorrelation = new AtomicReference<String>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var pipeline = new TriagePipeline(mapper,
                    (enquiry, correlation) -> CompletableFuture.completedFuture(result),
                    (enquiry, triage, correlation) -> persistedCorrelation.set(correlation),
                    (enquiry, triage, correlation) -> { routedCorrelation.set(correlation); return CompletableFuture.completedFuture(null); },
                    executor);
            pipeline.process("""
                    {"enquiryId":"e-1","customerId":"c-1","message":"My card is missing",
                     "receivedAt":"2026-08-08T12:00:00Z"}
                    """, "corr-123").toCompletableFuture().join();
        }
        assertEquals("corr-123", persistedCorrelation.get());
        assertEquals("corr-123", routedCorrelation.get());
    }

    @Test void rejectsMalformedPayload() {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var pipeline = new TriagePipeline(new ObjectMapper(),
                    (e, c) -> CompletableFuture.failedFuture(new AssertionError()), (e, r, c) -> {},
                    (e, r, c) -> CompletableFuture.completedFuture(null), executor);
            assertThrows(Exception.class, () -> pipeline.process("{}", "corr").toCompletableFuture().join());
        }
    }
}
