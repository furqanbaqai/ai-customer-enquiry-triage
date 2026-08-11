package com.sib.triage.domain;

import java.time.Instant;

public record CustomerEnquiry(Meta meta, String customerId, String mobileNumber, String firstName,
                              String lastName, String emailAddress, String message, Instant receivedAt) {
    public String enquiryId() {
        return meta.refNumber();
    }

    public record Meta(String refNumber, String channel, Instant reqIssuedAt) {}
}
