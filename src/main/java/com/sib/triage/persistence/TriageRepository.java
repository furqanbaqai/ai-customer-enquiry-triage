package com.sib.triage.persistence;

import com.sib.triage.domain.CustomerEnquiry;
import com.sib.triage.ai.AiClassification;

/**
 * Persistence contract for tracking the lifecycle of a customer enquiry.
 *
 * <p>The repository records the initial request state, stores AI classification outcomes, and
 * persists failure details when the external inference service does not complete successfully.</p>
 */
public interface TriageRepository {
    /**
     * Creates or refreshes the tracker row for a customer enquiry before AI processing begins.
     *
     * @param enquiry inbound customer request to be tracked
     * @param correlationId operation identifier for logs and audit tracing
     */
    void registerRequest(CustomerEnquiry enquiry, String requestJson, String correlationId);

    /**
     * Persists the successful AI classification path and stores provider metadata in the tracker.
     *
     * @param referenceNumber unique enquiry identifier used as the tracker key
     * @param result detailed AI classification metadata
     * @param correlationId operation identifier for logs and audit tracing
     */
    void updateSuccess(String referenceNumber, AiClassification result, String correlationId);

    /**
     * Persists the failure state and raw AI payload when inference fails after the request has been accepted.
     *
     * @param referenceNumber unique enquiry identifier used as the tracker key
     * @param errorMessage human-readable failure reason
     * @param rawAiResponse raw provider output captured for audit or diagnostic review
     * @param correlationId operation identifier for logs and audit tracing
     */
    void updateFailure(String referenceNumber, String errorMessage, String rawAiResponse, String correlationId);
}
