package com.sib.triage.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sib.triage.ai.TriageClassifier;
import com.sib.triage.domain.CustomerEnquiry;
import com.sib.triage.persistence.TriageRepository;
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
    private final Executor executor;

    public TriagePipeline(ObjectMapper mapper, TriageClassifier classifier, TriageRepository repository,
            Executor executor) {
        this.mapper = mapper;
        this.classifier = classifier;
        this.repository = repository;
        this.executor = executor;
    }

    /**
     * Processes a customer enquiry through the triage pipeline asynchronously.
     *
     * The method performs the following steps:
     * 1. Parses the JSON payload into a CustomerEnquiry object
     * 2. Classifies the enquiry using the TriageClassifier with correlation tracking
     * 3. Saves the enquiry and classification result to the repository asynchronously using the executor
     * 4. Logs completion or error status with correlation context
     *
     * All operations maintain correlation context for distributed tracing.
     *
     * @param json the JSON string representing the customer enquiry payload
     * @param correlationId the unique identifier for tracking this enquiry through the system
     * @return a CompletionStage that completes when the entire pipeline processing is done,
     *         or fails with InvalidEnquiryException if the JSON cannot be parsed
     */
    public CompletionStage<Void> process(String json, String correlationId) {
        try {
            var enquiry = mapper.readValue(json, CustomerEnquiry.class);
            LOGGER.info("Enquiry received enquiryId={}", enquiry.enquiryId());
            return classifier.classify(enquiry, correlationId)
                    .thenCompose(result -> java.util.concurrent.CompletableFuture.runAsync(
                            () -> withCorrelation(correlationId, () -> repository.save(enquiry, result, correlationId)),
                            executor))
                    .whenComplete((ignored, error) -> withCorrelation(correlationId, () -> {
                        if (error == null)
                            LOGGER.info("Enquiry processing completed enquiryId={}", enquiry.enquiryId());
                        else
                            LOGGER.error("Enquiry processing failed enquiryId={}", enquiry.enquiryId(), error);
                    }));
        } catch (Exception e) {
            return java.util.concurrent.CompletableFuture
                    .failedFuture(new InvalidEnquiryException("Invalid enquiry payload", e));
        }
    }

    private static void withCorrelation(String correlationId, Runnable action) {
        try (var ignored = MDC.putCloseable("correlationId", correlationId)) {
            action.run();
        }
    }

    public static final class InvalidEnquiryException extends RuntimeException {
        public InvalidEnquiryException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
