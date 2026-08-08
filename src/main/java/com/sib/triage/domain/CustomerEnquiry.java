package com.sib.triage.domain;

import java.time.Instant;

public record CustomerEnquiry(String enquiryId, String customerId, String message, Instant receivedAt) {
    public CustomerEnquiry {
        if (enquiryId == null || enquiryId.isBlank()) throw new IllegalArgumentException("enquiryId is required");
        if (customerId == null || customerId.isBlank()) throw new IllegalArgumentException("customerId is required");
        if (message == null || message.isBlank()) throw new IllegalArgumentException("message is required");
        receivedAt = receivedAt == null ? Instant.now() : receivedAt;
    }
}
