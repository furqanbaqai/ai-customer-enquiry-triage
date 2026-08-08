package com.sib.triage.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sib.triage.ai.TriageClassifier;
import com.sib.triage.domain.CustomerEnquiry;
import com.sib.triage.persistence.TriageRepository;
import com.sib.triage.routing.DownstreamRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

public final class TriagePipeline {
    private static final Logger LOGGER = LoggerFactory.getLogger(TriagePipeline.class);
    private final ObjectMapper mapper;
    private final TriageClassifier classifier;
    private final TriageRepository repository;
    private final DownstreamRouter router;
    private final Executor executor;

    public TriagePipeline(ObjectMapper mapper, TriageClassifier classifier, TriageRepository repository,
                          DownstreamRouter router, Executor executor) {
        this.mapper = mapper; this.classifier = classifier; this.repository = repository; this.router = router; this.executor = executor;
    }

    public CompletionStage<Void> process(String json, String correlationId) {
        try {
            var enquiry = mapper.readValue(json, CustomerEnquiry.class);
            LOGGER.info("Enquiry received enquiryId={}", enquiry.enquiryId());
            return classifier.classify(enquiry, correlationId)
                    .thenCompose(result -> java.util.concurrent.CompletableFuture.runAsync(
                            () -> withCorrelation(correlationId, () -> repository.save(enquiry, result, correlationId)), executor)
                            .thenCompose(ignored -> router.route(enquiry, result, correlationId)))
                    .whenComplete((ignored, error) -> withCorrelation(correlationId, () -> {
                        if (error == null) LOGGER.info("Enquiry processing completed enquiryId={}", enquiry.enquiryId());
                        else LOGGER.error("Enquiry processing failed enquiryId={}", enquiry.enquiryId(), error);
                    }));
        } catch (Exception e) {
            return java.util.concurrent.CompletableFuture.failedFuture(new InvalidEnquiryException("Invalid enquiry payload", e));
        }
    }

    private static void withCorrelation(String correlationId, Runnable action) {
        try (var ignored = MDC.putCloseable("correlationId", correlationId)) { action.run(); }
    }

    public static final class InvalidEnquiryException extends RuntimeException {
        public InvalidEnquiryException(String message, Throwable cause) { super(message, cause); }
    }
}
