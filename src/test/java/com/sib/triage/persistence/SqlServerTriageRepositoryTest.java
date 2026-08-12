package com.sib.triage.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import com.sib.triage.support.ConsoleTestDescription;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the guardrails used when persisting AI metadata and failure messages in SQL Server.
 */
@ExtendWith(ConsoleTestDescription.class)
class SqlServerTriageRepositoryTest {
    @Test void truncatesLongErrorsToDatabaseLimit() {
        var value = "x".repeat(200);
        assertEquals(128, SqlServerTriageRepository.truncate(value, 128).length());
    }

    @Test void truncationIsNullSafeAndLeavesShortErrorsUnchanged() {
        assertNull(SqlServerTriageRepository.truncate(null, 128));
        assertEquals("failure", SqlServerTriageRepository.truncate("failure", 128));
    }

    @Test void acceptsGenAiIdAtDatabaseColumnLimit() {
        var value = "x".repeat(SqlServerTriageRepository.MAX_GEN_AI_ID_LENGTH);
        assertSame(value, SqlServerTriageRepository.validatedGenAiId(value));
    }

    @Test void rejectsGenAiIdBeyondDatabaseColumnLimit() {
        var value = "x".repeat(SqlServerTriageRepository.MAX_GEN_AI_ID_LENGTH + 1);
        var error = assertThrows(SqlServerTriageRepository.PersistenceException.class,
                () -> SqlServerTriageRepository.validatedGenAiId(value));
        assertEquals("AI response id exceeds 56 characters", error.getMessage());
    }

    @Test void acceptsObservedChatCompletionId() {
        var value = "chatcmpl-xp5OkS69GRu4PQqfLqs5opsE3SM1LNIa";
        assertSame(value, SqlServerTriageRepository.validatedGenAiId(value));
    }
}
