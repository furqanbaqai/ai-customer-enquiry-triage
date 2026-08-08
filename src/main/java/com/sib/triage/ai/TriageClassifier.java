package com.sib.triage.ai;

import com.sib.triage.domain.CustomerEnquiry;
import com.sib.triage.domain.TriageResult;
import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface TriageClassifier {
    CompletionStage<TriageResult> classify(CustomerEnquiry enquiry, String correlationId);
}
