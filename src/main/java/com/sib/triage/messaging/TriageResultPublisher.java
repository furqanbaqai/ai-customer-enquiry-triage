package com.sib.triage.messaging;

import com.sib.triage.domain.TriageResult;

@FunctionalInterface
public interface TriageResultPublisher {
    void publish(TriageResult result, String correlationId);
}
