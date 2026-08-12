package com.sib.triage.domain;
/**
 * Final output produced by the AI triage service and published downstream.
 *
 * <p>The content is expected to carry the provider classification result while the nested
 * customer enquiry provides the original request context for routing and audit trails.</p>
 *
 * @param content raw classification response returned by the AI service
 * @param customerEnquiry original request used to derive the triage result
 */
public record TriageResult(String content, CustomerEnquiry customerEnquiry) {
    /**
     * Validates the result contract before it is used outside the classification layer.
     */
    public TriageResult {
        if (content == null || content.isBlank()) throw new IllegalArgumentException("content is required");
        if (customerEnquiry == null) throw new IllegalArgumentException("customerEnquiry is required");
    }
}
