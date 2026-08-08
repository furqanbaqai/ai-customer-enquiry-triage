package com.sib.triage.domain;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class RoutingDecisionTest {
    @Test void thresholdIsEscalated() {
        var result = new TriageResult("FRAUD", 8, "NEGATIVE", "Fraud", "Suspicious", Instant.now());
        assertInstanceOf(RoutingDecision.Escalated.class, RoutingDecision.from(result, 8));
    }

    @Test void belowThresholdIsStandard() {
        var result = new TriageResult("GENERAL", 7, "NEUTRAL", "Support", null, Instant.now());
        assertInstanceOf(RoutingDecision.Standard.class, RoutingDecision.from(result, 8));
    }
}
