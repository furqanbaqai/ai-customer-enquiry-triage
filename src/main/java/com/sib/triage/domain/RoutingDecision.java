package com.sib.triage.domain;

public sealed interface RoutingDecision permits RoutingDecision.Standard, RoutingDecision.Escalated {
    record Standard(String team) implements RoutingDecision {}
    record Escalated(String team, int urgencyScore) implements RoutingDecision {}

    static RoutingDecision from(TriageResult result, int threshold) {
        return result.urgencyScore() >= threshold
                ? new Escalated(result.recommendedTeam(), result.urgencyScore())
                : new Standard(result.recommendedTeam());
    }
}
