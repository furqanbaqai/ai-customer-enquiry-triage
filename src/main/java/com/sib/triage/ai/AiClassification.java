package com.sib.triage.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.sib.triage.domain.TriageResult;

/**
 * Captures the fully parsed AI response and the provider metadata used for auditing.
 *
 * <p>The record retains the normalized domain result plus the upstream provider identifier,
 * token usage, timing details, and original raw JSON payload; this allows the repository to
 * persist the exact response body while still exposing the structured values used by the
 * application.</p>
 *
 * @param result normalized triage result returned to the application
 * @param id provider-generated identifier for the inference call
 * @param totalTokens total token count reported by the provider, if available
 * @param timings structured execution timings from the provider, if available
 * @param rawResponse complete provider response body used for auditability
 */
public record AiClassification(TriageResult result, String id, Integer totalTokens,
                               JsonNode timings, String rawResponse) {
}
