package com.sib.triage.domain;



public record TriageResult(String content) {
    public TriageResult {
        if (content == null || content.isBlank()) throw new IllegalArgumentException("content is required");
    }
}
