package com.sib.triage.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TriageResultTest {
    @Test void retainsContentExactly() {
        var content = "{\"department\":\"Cards\",\"category\":\"Lost Card\"}";

        assertEquals(content, new TriageResult(content).content());
    }

    @Test void rejectsNullContent() {
        assertThrows(IllegalArgumentException.class, () -> new TriageResult(null));
    }

    @Test void rejectsBlankContent() {
        assertThrows(IllegalArgumentException.class, () -> new TriageResult("  \t\n"));
    }
}
