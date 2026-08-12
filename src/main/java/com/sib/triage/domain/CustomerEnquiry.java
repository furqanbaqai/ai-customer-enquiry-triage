package com.sib.triage.domain;

import java.time.Instant;

/**
 * Canonical request model received from the customer contact channel.
 *
 * <p>The record holds the broker metadata, customer identity, contact information, and the
 * original message content used for AI classification and downstream routing.</p>
 *
 * @param meta trace and delivery metadata associated with this enquiry
 * @param customerId external customer identifier used by the bank or channel
 * @param mobileNumber customer mobile number, if provided
 * @param firstName customer first name, if provided
 * @param lastName customer last name, if provided
 * @param emailAddress contact email address, if provided
 * @param message original customer message submitted for AI triage
 * @param receivedAt timestamp when the message entered the triage workflow
 */
public record CustomerEnquiry(Meta meta, String customerId, String mobileNumber, String firstName,
                              String lastName, String emailAddress, String message, Instant receivedAt) {
    /**
     * Returns the natural enquiry identifier used throughout the application lifecycle.
     *
     * @return reference number from the message metadata
     */
    public String enquiryId() {
        return meta.refNumber();
    }

    /**
     * Transport and trace metadata included with the inbound customer enquiry.
     *
     * @param refNumber business reference number or correlation key
     * @param channel originating channel such as website, mobile app, or branch
     * @param reqIssuedAt timestamp when the original request was issued by the customer or channel
     */
    public record Meta(String refNumber, String channel, Instant reqIssuedAt) {}
}
