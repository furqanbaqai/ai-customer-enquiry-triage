package com.sib.triage.messaging;

import com.sib.triage.domain.TriageResult;

/**
 * Writes a completed AI triage result to the configured downstream integration point.
 *
 * <p>The implementation is intentionally decoupled from the pipeline so the same triage result
 * can be emitted to different transports without forcing the orchestration layer to know the
 * transport mechanics.</p>
 */
@FunctionalInterface
public interface TriageResultPublisher {
    /**
     * Publishes the final classification outcome for downstream processing.
     *
     * @param result completed triage result returned by the AI classifier
     * @param correlationId worker request identifier used to correlate the message on the result queue
     */
    void publish(TriageResult result, String correlationId);
}
