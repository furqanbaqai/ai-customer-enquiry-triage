package com.sib.triage.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sib.triage.config.AppConfig;
import com.sib.triage.domain.CustomerEnquiry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import com.sib.triage.support.ConsoleTestDescription;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies the AI HTTP client parses standard provider responses and preserves audit metadata.
 */
@ExtendWith(ConsoleTestDescription.class)
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
                result.result().content());
        assertSame(enquiry, result.result().customerEnquiry());
    }

    @Test void rejectsMissingChoices() {
        assertThrows(Exception.class, () -> classifier.parseResponse("{\"choices\":[]}", enquiry));
    }

    @Test void preservesNonJsonMessageContent() throws Exception {
        var result = classifier.parseResponse("""
                {"choices":[{"message":{"content":"not-json"}}]}
                """, enquiry);
        assertEquals("not-json", result.result().content());
        assertSame(enquiry, result.result().customerEnquiry());
    }

    @Test void rejectsBlankMessageContent() {
        assertThrows(Exception.class, () -> classifier.parseResponse("""
                {"choices":[{"message":{"content":"   "}}]}
                """, enquiry));
    }

    @Test void preservesRawResponseAndMapsAuditMetadata() throws Exception {
        var raw = """
                {"id":"cmpl-abc123","choices":[{"message":{"content":"ok"}}],
                 "usage":{"total_tokens":780},"timings":{"prompt_n":700,"predicted_ms":12000.5}}
                """;
        var result = classifier.parseResponse(raw, enquiry);
        assertEquals("cmpl-abc123", result.id());
        assertEquals(780, result.totalTokens());
        assertEquals(700, result.timings().path("prompt_n").intValue());
        assertEquals(raw, result.rawResponse());
    }

    @Test void missingUsageAndTimingsRemainNull() throws Exception {
        var result = classifier.parseResponse("{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}", enquiry);
        assertEquals(null, result.totalTokens());
        assertEquals(null, result.timings());
    }
}
