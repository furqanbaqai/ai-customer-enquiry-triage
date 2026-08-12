package com.sib.triage.ai;

import com.sib.triage.domain.CustomerEnquiry;
import com.sib.triage.domain.TriageResult;
import java.util.concurrent.CompletionStage;

/**
 * Strategy interface for AI-based classification of a customer enquiry.
 *
 * <p>Implementations may expose either a compact triage result or the full provider payload so the
 * tracker repository can persist audit metadata such as provider ID, token usage, and timing details.</p>
 */
@FunctionalInterface
public interface TriageClassifier {
    /**
     * Classifies a customer enquiry and returns the normalized triage result.
     *
     * @param enquiry enquiry to classify
     * @param correlationId tracing identifier shared with downstream logging and audit columns
     * @return the asynchronous result of AI classification
     */
    CompletionStage<TriageResult> classify(CustomerEnquiry enquiry, String correlationId);

    /**
     * Produces the detailed AI classification payload when the caller needs provider metadata.
     *
     * @param enquiry enquiry to classify
     * @param correlationId tracing identifier shared with downstream logging and audit columns
     * @return asynchronous classification result that includes provider metadata and raw response
     */
    default CompletionStage<AiClassification> classifyDetailed(CustomerEnquiry enquiry, String correlationId) {
        return classify(enquiry, correlationId).thenApply(result -> new AiClassification(result, null, null, null, null));
    }
}
