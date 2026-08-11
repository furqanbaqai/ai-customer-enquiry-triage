package com.sib.triage.ai;

import com.sib.triage.domain.CustomerEnquiry;
import com.sib.triage.domain.TriageResult;
import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface TriageClassifier {
    CompletionStage<TriageResult> classify(CustomerEnquiry enquiry, String correlationId);

    default CompletionStage<AiClassification> classifyDetailed(CustomerEnquiry enquiry, String correlationId) {
        return classify(enquiry, correlationId).thenApply(result -> new AiClassification(result, null, null, null, null));
    }
}
