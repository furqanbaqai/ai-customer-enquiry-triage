package com.sib.triage.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import com.sib.triage.support.ConsoleTestDescription;

import static org.junit.jupiter.api.Assertions.*;

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
}
