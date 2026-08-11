package com.sib.triage.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sib.triage.config.AppConfig;
import com.sib.triage.domain.CustomerEnquiry;
import org.junit.jupiter.api.Test;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AiHttpClassifierTest {
    private final CustomerEnquiry enquiry = new CustomerEnquiry(
            new CustomerEnquiry.Meta("e-1", "WebSite", Instant.parse("2026-08-08T11:59:00Z")),
            "ABCDEF123456", "+971501234567", "Sara", "Khan", "sara@example.com", "Help",
            Instant.parse("2026-08-08T12:00:00Z"));
    private final AiHttpClassifier classifier = new AiHttpClassifier(
            HttpClient.newHttpClient(), new ObjectMapper(),
            new AppConfig.HttpEndpoint("http://localhost/classify", "test-key", Duration.ofSeconds(1)));

    @Test void parsesClassificationFromChatCompletionMessageContent() throws Exception {
        var result = classifier.parseResponse("""
                {
                  "choices": [{
                    "finish_reason": "stop",
                    "index": 0,
                    "message": {
                      "role": "assistant",
                      "content": "{\\\"category\\\":\\\"Card Fraud/Errors\\\",\\\"subcategory\\\":\\\"Duplicate Charges\\\"}"
                    }
                  }],
                  "object": "chat.completion"
                }
                """, enquiry);
        assertEquals("{\"category\":\"Card Fraud/Errors\",\"subcategory\":\"Duplicate Charges\"}",
                result.content());
        assertSame(enquiry, result.customerEnquiry());
    }

    @Test void rejectsMissingChoices() {
        assertThrows(Exception.class, () -> classifier.parseResponse("{\"choices\":[]}", enquiry));
    }

    @Test void preservesNonJsonMessageContent() throws Exception {
        var result = classifier.parseResponse("""
                {"choices":[{"message":{"content":"not-json"}}]}
                """, enquiry);
        assertEquals("not-json", result.content());
        assertSame(enquiry, result.customerEnquiry());
    }

    @Test void rejectsBlankMessageContent() {
        assertThrows(Exception.class, () -> classifier.parseResponse("""
                {"choices":[{"message":{"content":"   "}}]}
                """, enquiry));
    }
}
