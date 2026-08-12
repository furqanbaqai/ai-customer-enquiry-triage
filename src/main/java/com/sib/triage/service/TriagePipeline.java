package com.sib.triage.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sib.triage.ai.TriageClassifier;
import com.sib.triage.ai.AiHttpClassifier;
import com.sib.triage.domain.CustomerEnquiry;
import com.sib.triage.messaging.TriageResultPublisher;
import com.sib.triage.persistence.TriageRepository;
import com.sib.triage.persistence.SqlServerTriageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import java.util.concurrent.*;

/**
 * Orchestrates the end-to-end customer-enquiry triage workflow.
 *
 * <p>The pipeline validates inbound JSON, persists the initial tracking state, invokes the AI
 * classifier, updates the tracker with the outcome, and publishes the final response to the
 * configured IBM MQ result queue. All stages keep the correlation ID attached so operational
 * logs can trace the same enquiry through multiple subsystems.</p>
 */
public final class TriagePipeline {
    private static final Logger LOGGER = LoggerFactory.getLogger(TriagePipeline.class);
    private final ObjectMapper mapper;
    private final EnquirySchemaValidator schemaValidator;
    private final TriageClassifier classifier;
    private final TriageRepository repository;
    private final TriageResultPublisher resultPublisher;
    private final Executor executor;

    /**
     * Creates a pipeline bound to the JSON mapper, schema validator, AI classifier, tracking repository,
     * result publisher, and executor used for asynchronous processing.
     *
     * @param mapper JSON serializer/deserializer used for inbound request conversion
     * @param schemaValidator ensures the payload matches the expected request contract
     * @param classifier AI classification service that decides triage outcome
     * @param repository persistence layer that records the request lifecycle
     * @param resultPublisher downstream MQ writer for final classification output
     * @param executor executor used to offload blocking work from the JMS listener thread
     */
    public TriagePipeline(ObjectMapper mapper, EnquirySchemaValidator schemaValidator,
            TriageClassifier classifier, TriageRepository repository,
            TriageResultPublisher resultPublisher, Executor executor) {
        this.mapper = mapper;
        this.schemaValidator = schemaValidator;
        this.classifier = classifier;
        this.repository = repository;
        this.resultPublisher = resultPublisher;
        this.executor = executor;
    }

    /**
     * Processes a customer enquiry through the triage pipeline asynchronously.
     *
     * <p>The method validates the JSON contract, converts the payload to the domain model,
     * registers the request in the tracking table, invokes the AI classifier, persists the final
     * state, and publishes the result to the configured response queue. The correlation ID is kept
     * in thread-local MDC throughout each stage so operational logs remain traceable.</p>
     *
     * @param json raw customer-enquiry payload received from IBM MQ
     * @param correlationId unique identifier used to correlate messages, database entries, and logs
     * @return a future representing the pipeline lifecycle; failures are propagated as
     *         {@link InvalidEnquiryException} or repository/AI exceptions according to the stage that fails
     */
    public CompletionStage<Void> process(String json, String correlationId) {
        try {
            schemaValidator.validate(json);
            var enquiry = mapper.readValue(json, CustomerEnquiry.class);
            LOGGER.info("Customer enquiry received referenceNumber={}", enquiry.enquiryId());
            // Registration is deliberately the first asynchronous stage: an enquiry
            // that cannot be audited must never reach the external AI endpoint.
            return CompletableFuture.runAsync(() -> withCorrelation(correlationId,
                            () -> repository.registerRequest(enquiry, json, correlationId)), executor)
                    .thenCompose(ignored -> classifier.classifyDetailed(enquiry, correlationId))
                    .handle((classification, error) -> {
                        if (error == null) return CompletableFuture.runAsync(() -> withCorrelation(correlationId, () -> {
                            repository.updateSuccess(enquiry.enquiryId(), classification, correlationId);
                            LOGGER.info("AI processing completed referenceNumber={} status=SUCCESS totalTokens={}",
                                    enquiry.enquiryId(), classification.totalTokens());
                            resultPublisher.publish(classification.result(), correlationId);
                        }), executor);
                        var cause = unwrap(error);
                        // A registration failure has no reliable tracker row to update.
                        // Preserve its type so the MQ consumer can route the original body.
                        if (cause instanceof SqlServerTriageRepository.PersistenceException)
                            return CompletableFuture.<Void>failedFuture(cause);
                        var rawResponse = cause instanceof AiHttpClassifier.AiServiceException ai
                                ? ai.rawJsonResponse() : null;
                        // Persist FAILURE before propagating the AI exception. Downstream
                        // result publication is therefore skipped while the audit remains.
                        return CompletableFuture.runAsync(() -> withCorrelation(correlationId, () -> {
                            repository.updateFailure(enquiry.enquiryId(), cause.getMessage(), rawResponse, correlationId);
                            LOGGER.error("AI processing failed referenceNumber={} error={}", enquiry.enquiryId(), cause.getMessage());
                            throw new CompletionException(cause);
                        }), executor);
                    }).thenCompose(stage -> stage)
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

    /**
     * Unwraps completion exceptions so the underlying service error is reported consistently.
     *
     * @param error exception or completion wrapper
     * @return the underlying cause if nested completion wrappers are present
     */
    private static Throwable unwrap(Throwable error) {
        while ((error instanceof CompletionException || error instanceof ExecutionException)
                && error.getCause() != null) error = error.getCause();
        return error;
    }

    /**
     * Executes a processing block with the current correlation ID attached to MDC.
     *
     * @param correlationId record identifier used for tracing across async boundaries
     * @param action work to run with the correlation ID bound to logging context
     */
    private static void withCorrelation(String correlationId, Runnable action) {
        try (var ignored = MDC.putCloseable("correlationId", correlationId)) {
            action.run();
        }
    }

    /**
     * Indicates that an inbound payload could not be parsed or validated as a customer enquiry.
     */
    public static final class InvalidEnquiryException extends RuntimeException {
        public InvalidEnquiryException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
