package com.sib.triage.domain;



public record TriageResult(String content, CustomerEnquiry customerEnquiry) {
    public TriageResult {
        if (content == null || content.isBlank()) throw new IllegalArgumentException("content is required");
        if (customerEnquiry == null) throw new IllegalArgumentException("customerEnquiry is required");
    }
}
