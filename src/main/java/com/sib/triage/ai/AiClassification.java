package com.sib.triage.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.sib.triage.domain.TriageResult;

public record AiClassification(TriageResult result, String id, Integer totalTokens,
                               JsonNode timings, String rawResponse) {
}
