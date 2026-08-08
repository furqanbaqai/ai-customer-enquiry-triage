package com.sib.triage.persistence;

import com.sib.triage.domain.CustomerEnquiry;
import com.sib.triage.domain.TriageResult;

@FunctionalInterface
public interface TriageRepository {
    void save(CustomerEnquiry enquiry, TriageResult result, String correlationId);
}
