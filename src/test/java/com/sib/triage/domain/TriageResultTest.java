package com.sib.triage.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import com.sib.triage.support.ConsoleTestDescription;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies the invariants of the triage result model used by the AI response and downstream queue.
 */
@ExtendWith(ConsoleTestDescription.class)
class TriageResultTest {
    private final CustomerEnquiry enquiry = new CustomerEnquiry(
            new CustomerEnquiry.Meta("e-1", "WebSite", Instant.parse("2026-08-08T11:59:00Z")),
            null, "+971501234567", "Sara", "Khan", "sara@example.com", "Help",
            Instant.parse("2026-08-08T12:00:00Z"));

    @Test void retainsContentExactly() {
        var content = "{\"department\":\"Cards\",\"category\":\"Lost Card\"}";

        var result = new TriageResult(content, enquiry);
        assertEquals(content, result.content());
        assertEquals(enquiry, result.customerEnquiry());
    }

    @Test void rejectsNullContent() {
        assertThrows(IllegalArgumentException.class, () -> new TriageResult(null, enquiry));
    }

    @Test void rejectsBlankContent() {
        assertThrows(IllegalArgumentException.class, () -> new TriageResult("  \t\n", enquiry));
    }

    @Test void rejectsNullCustomerEnquiry() {
        assertThrows(IllegalArgumentException.class, () -> new TriageResult("response", null));
    }
}
