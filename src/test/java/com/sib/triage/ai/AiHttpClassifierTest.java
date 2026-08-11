package com.sib.triage.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sib.triage.config.AppConfig;
import org.junit.jupiter.api.Test;
import java.net.http.HttpClient;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AiHttpClassifierTest {
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
                """);
        assertEquals("{\"category\":\"Card Fraud/Errors\",\"subcategory\":\"Duplicate Charges\"}",
                result.content());
    }

    @Test void rejectsMissingChoices() {
        assertThrows(Exception.class, () -> classifier.parseResponse("{\"choices\":[]}"));
    }

    @Test void preservesNonJsonMessageContent() throws Exception {
        var result = classifier.parseResponse("""
                {"choices":[{"message":{"content":"not-json"}}]}
                """);
        assertEquals("not-json", result.content());
    }

    @Test void rejectsBlankMessageContent() {
        assertThrows(Exception.class, () -> classifier.parseResponse("""
                {"choices":[{"message":{"content":"   "}}]}
                """));
    }
}
