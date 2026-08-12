package com.sib.triage.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sib.triage.domain.CustomerEnquiry;
import com.sib.triage.domain.TriageResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import com.sib.triage.support.ConsoleTestDescription;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies that final triage results are serialized to the expected JSON contract before they are
 * published to the IBM MQ result queue.
 */
@ExtendWith(ConsoleTestDescription.class)
class MqTriageResultPublisherTest {
    @Test void serializesContentAndCustomerEnquiryForResultQueue() throws Exception {
        var mapper = new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        var enquiry = new CustomerEnquiry(
                new CustomerEnquiry.Meta("e-1", "WebSite", Instant.parse("2026-08-08T11:59:00Z")),
                "ABCDEF123456", "+971501234567", "Sara", "Khan", "sara@example.com", "Help",
                Instant.parse("2026-08-08T12:00:00Z"));

        var json = mapper.readTree(MqTriageResultPublisher.serialize(mapper,
                new TriageResult("{\"category\":\"Cards\"}", enquiry)));

        assertEquals("{\"category\":\"Cards\"}", json.path("content").textValue());
        assertEquals("e-1", json.path("customerEnquiry").path("meta").path("refNumber").textValue());
        assertEquals("ABCDEF123456", json.path("customerEnquiry").path("customerId").textValue());
        assertEquals("2026-08-08T12:00:00Z",
                json.path("customerEnquiry").path("receivedAt").textValue());
    }
}
