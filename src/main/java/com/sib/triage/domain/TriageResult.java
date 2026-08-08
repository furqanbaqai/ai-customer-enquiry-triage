package com.sib.triage.domain;

import java.time.Instant;

public record TriageResult(String intent, int urgencyScore, String sentiment,
                           String recommendedTeam, String rationale, Instant classifiedAt) {
    public TriageResult {
        if (intent == null || intent.isBlank()) throw new IllegalArgumentException("intent is required");
        if (urgencyScore < 0 || urgencyScore > 10) throw new IllegalArgumentException("urgencyScore must be between 0 and 10");
        if (recommendedTeam == null || recommendedTeam.isBlank()) throw new IllegalArgumentException("recommendedTeam is required");
        sentiment = sentiment == null ? "UNKNOWN" : sentiment;
        classifiedAt = classifiedAt == null ? Instant.now() : classifiedAt;
    }
}
