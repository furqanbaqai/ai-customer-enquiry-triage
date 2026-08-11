package com.sib.triage.persistence;

import com.sib.triage.domain.CustomerEnquiry;
import com.sib.triage.ai.AiClassification;

public interface TriageRepository {
    void registerRequest(CustomerEnquiry enquiry, String correlationId);
    void updateSuccess(String referenceNumber, AiClassification result, String correlationId);
    void updateFailure(String referenceNumber, String errorMessage, String rawAiResponse, String correlationId);
}
