package com.sib.triage.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sib.triage.ai.AiClassification;
import com.sib.triage.domain.CustomerEnquiry;
import com.sib.triage.domain.TriageResult;
import com.sib.triage.support.ConsoleTestDescription;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Verifies SQL binding, JSON preservation, append-only history, and transaction behavior. */
@ExtendWith(ConsoleTestDescription.class)
class SqlServerTriageRepositoryTest {
    private final DataSource dataSource = mock(DataSource.class);
    private final Connection connection = mock(Connection.class);
    private final PreparedStatement tracker = mock(PreparedStatement.class);
    private final PreparedStatement history = mock(PreparedStatement.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final SqlServerTriageRepository repository = new SqlServerTriageRepository(dataSource, mapper);

    @BeforeEach void prepareConnection() throws Exception {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(SqlServerTriageRepository.UPDATE_TRACKER)).thenReturn(tracker);
        when(connection.prepareStatement(SqlServerTriageRepository.INSERT_HISTORY)).thenReturn(history);
        when(tracker.executeUpdate()).thenReturn(1);
        when(history.executeUpdate()).thenReturn(1);
    }

    @Test void newRequestBindsOriginalJsonAndUsesSchemaDefaults() throws Exception {
        var registration = mock(PreparedStatement.class);
        when(connection.prepareStatement(SqlServerTriageRepository.REGISTER)).thenReturn(registration);
        var raw = " {\"meta\": {\"refNumber\":\"ref-1\"}} \n";

        repository.registerRequest(enquiry(), raw, "corr-1");

        verify(registration).setString(1, "ref-1");
        verify(registration).setString(2, "WebSite");
        verify(registration).setString(4, raw);
        assertTrue(SqlServerTriageRepository.REGISTER.contains("'RECEIVED', 0, source.requestJSON"));
        assertFalse(SqlServerTriageRepository.REGISTER.contains("target.requestJSON ="));
    }

    @Test void rejectsInvalidRequestJsonBeforeOpeningConnection() {
        assertThrows(SqlServerTriageRepository.PersistenceException.class,
                () -> repository.registerRequest(enquiry(), "not-json", "corr"));
        verifyNoInteractions(dataSource);
    }

    @Test void rejectsNullBlankAndScalarRequestJsonAtValidationBoundary() {
        assertAll(
                () -> assertThrows(SqlServerTriageRepository.PersistenceException.class,
                        () -> repository.registerRequest(enquiry(), null, "null")),
                () -> assertThrows(SqlServerTriageRepository.PersistenceException.class,
                        () -> repository.registerRequest(enquiry(), "   ", "blank")),
                () -> assertThrows(SqlServerTriageRepository.PersistenceException.class,
                        () -> repository.registerRequest(enquiry(), "42", "scalar")));
        verifyNoInteractions(dataSource);
    }

    @Test void requestDatabaseFailureCanBeRetriedWithoutChangingOriginalPayload() throws Exception {
        var registration = mock(PreparedStatement.class);
        when(connection.prepareStatement(SqlServerTriageRepository.REGISTER)).thenReturn(registration);
        when(registration.executeUpdate()).thenThrow(new SQLException("database unavailable")).thenReturn(1);
        var raw = " {\"attempt\":1} ";

        assertThrows(SqlServerTriageRepository.PersistenceException.class,
                () -> repository.registerRequest(enquiry(), raw, "first"));
        repository.registerRequest(enquiry(), raw, "retry");

        verify(registration, times(2)).executeUpdate();
        verify(registration, times(2)).setString(4, raw);
    }

    @Test void successUpdatesTrackerAndAppendsMappedHistoryInOneTransaction() throws Exception {
        var raw = " {\"id\":\"gen-1\",\"extra\":true} ";
        var timings = mapper.readTree("{\"elapsedMs\":12}");
        var classification = new AiClassification(new TriageResult("ok", enquiry()), "gen-1", 42, timings, raw);

        repository.updateSuccess("ref-1", classification, "corr");

        verify(connection).setAutoCommit(false);
        verify(tracker).setString(1, "SUCCESS");
        verify(tracker).setString(2, null);
        verify(history).setString(1, "ref-1");
        verify(history).setInt(2, 42);
        verify(history).setString(3, "gen-1");
        verify(history).setString(4, "{\"elapsedMs\":12}");
        verify(history).setString(5, raw);
        var order = inOrder(history, tracker, connection);
        order.verify(history).executeUpdate();
        order.verify(tracker).executeUpdate();
        order.verify(connection).commit();
        verify(connection).commit();
        verify(connection, never()).rollback();
        assertFalse(SqlServerTriageRepository.INSERT_HISTORY.toLowerCase().contains("historyid"));
    }

    @Test void multipleSuccessfulAttemptsAlwaysExecuteHistoryInsert() throws Exception {
        var result = new AiClassification(new TriageResult("ok", enquiry()), null, null, null, "{\"ok\":true}");
        repository.updateSuccess("ref-1", result, "one");
        repository.updateSuccess("ref-1", result, "two");
        verify(history, times(2)).executeUpdate();
        verify(connection, times(2)).commit();
    }

    @Test void successfulResponseSupportsNullOptionalMetadataBoundary() throws Exception {
        var result = new AiClassification(new TriageResult("ok", enquiry()), null, null, null, null);

        repository.updateSuccess("ref-1", result, "corr");

        verify(history).setNull(2, java.sql.Types.INTEGER);
        verify(history).setString(3, null);
        verify(history).setString(4, null);
        verify(history).setString(5, null);
        verify(tracker).setString(1, "SUCCESS");
        verify(connection).commit();
    }

    @Test void successfulResponseAcceptsProviderIdAtMaximumBoundary() throws Exception {
        var maximumId = "g".repeat(SqlServerTriageRepository.MAX_GEN_AI_ID_LENGTH);
        var result = new AiClassification(new TriageResult("ok", enquiry()), maximumId, 0,
                mapper.createObjectNode(), "{\"ok\":true}");

        repository.updateSuccess("ref-1", result, "corr");

        verify(history).setString(3, maximumId);
        verify(history).setInt(2, 0);
        verify(history).setString(4, "{}");
        verify(connection).commit();
    }

    @Test void oversizedProviderIdFailsBeforeAnyDatabaseWrite() {
        var result = new AiClassification(new TriageResult("ok", enquiry()),
                "g".repeat(SqlServerTriageRepository.MAX_GEN_AI_ID_LENGTH + 1), 1, null, "{\"ok\":true}");

        assertThrows(SqlServerTriageRepository.PersistenceException.class,
                () -> repository.updateSuccess("ref-1", result, "corr"));

        verifyNoInteractions(dataSource);
    }

    @Test void failureTruncatesMessageUpdatesStatusAndAppendsHistory() throws Exception {
        repository.updateFailure("ref-1", "x".repeat(200), "{\"error\":\"provider\"}", "corr");
        verify(tracker).setString(1, "FAILURE");
        verify(tracker).setString(2, "x".repeat(128));
        verify(history).setNull(2, java.sql.Types.INTEGER);
        verify(history).setString(5, "{\"error\":\"provider\"}");
        verify(connection).commit();
    }

    @Test void failureAtExactErrorBoundaryIsStoredWithoutModification() throws Exception {
        var error = "e".repeat(SqlServerTriageRepository.MAX_ERROR_LENGTH);

        repository.updateFailure("ref-1", error, "not-json", "corr");

        verify(tracker).setString(2, error);
        verify(history).setString(5, null);
        verify(connection).commit();
    }

    @Test void nullFailureDetailsStillRecordFailureAttempt() throws Exception {
        repository.updateFailure("ref-1", null, null, "corr");

        verify(tracker).setString(1, "FAILURE");
        verify(tracker).setString(2, null);
        verify(history).setNull(2, java.sql.Types.INTEGER);
        verify(history).executeUpdate();
        verify(connection).commit();
    }

    @Test void historyFailureRollsBackWithoutUpdatingTracker() throws Exception {
        when(history.executeUpdate()).thenThrow(new SQLException("history unavailable"));
        var result = new AiClassification(new TriageResult("ok", enquiry()), null, 1, null, "{\"ok\":true}");
        assertThrows(SqlServerTriageRepository.PersistenceException.class,
                () -> repository.updateSuccess("ref-1", result, "corr"));
        verify(tracker, never()).executeUpdate();
        verify(connection).rollback();
        verify(connection, never()).commit();
    }

    @Test void responseRecordingRetrySucceedsAfterTransientHistoryFailure() throws Exception {
        when(history.executeUpdate()).thenThrow(new SQLException("temporary failure")).thenReturn(1);
        var result = new AiClassification(new TriageResult("ok", enquiry()), "gen-1", 5,
                null, "{\"ok\":true}");

        assertThrows(SqlServerTriageRepository.PersistenceException.class,
                () -> repository.updateSuccess("ref-1", result, "first"));
        repository.updateSuccess("ref-1", result, "retry");

        verify(history, times(2)).executeUpdate();
        verify(tracker, times(1)).executeUpdate();
        verify(connection, times(1)).rollback();
        verify(connection, times(1)).commit();
    }

    @Test void failureRecordingRetryRollsBackFirstHistoryAndCommitsOnlyRetry() throws Exception {
        when(tracker.executeUpdate()).thenReturn(0, 1);

        assertThrows(SqlServerTriageRepository.PersistenceException.class,
                () -> repository.updateFailure("ref-1", "failed", "{\"attempt\":1}", "first"));
        repository.updateFailure("ref-1", "failed", "{\"attempt\":2}", "retry");

        verify(history, times(2)).executeUpdate();
        verify(tracker, times(2)).executeUpdate();
        verify(connection, times(1)).rollback();
        verify(connection, times(1)).commit();
    }

    @Test void missingTrackerRollsBackPreviouslyInsertedHistory() throws Exception {
        when(tracker.executeUpdate()).thenReturn(0);
        assertThrows(SqlServerTriageRepository.PersistenceException.class,
                () -> repository.updateFailure("missing", "error", null, "corr"));
        verify(history).executeUpdate();
        var order = inOrder(history, tracker, connection);
        order.verify(history).executeUpdate();
        order.verify(tracker).executeUpdate();
        order.verify(connection).rollback();
        verify(connection).rollback();
        verify(connection, never()).commit();
    }

    @Test void jsonUtilityPreservesValidPayloadAndDropsInvalidOrScalarValues() {
        var raw = " {\"a\": 1} \n";
        assertSame(raw, repository.validJsonOrNull(raw));
        assertNull(repository.validJsonOrNull("not-json"));
        assertNull(repository.validJsonOrNull("\"scalar\""));
        assertNull(repository.validJsonOrNull(null));
    }

    @Test void validatesColumnLengths() {
        assertEquals(128, SqlServerTriageRepository.truncate("x".repeat(200), 128).length());
        assertNull(SqlServerTriageRepository.truncate(null, 128));
        var id = "x".repeat(SqlServerTriageRepository.MAX_GEN_AI_ID_LENGTH);
        assertSame(id, SqlServerTriageRepository.validatedGenAiId(id));
        assertThrows(SqlServerTriageRepository.PersistenceException.class,
                () -> SqlServerTriageRepository.validatedGenAiId(id + "x"));
    }

    private static CustomerEnquiry enquiry() {
        return new CustomerEnquiry(new CustomerEnquiry.Meta("ref-1", "WebSite",
                Instant.parse("2026-08-08T11:59:00Z")), "customer", null, "Sara", "Khan",
                "sara@example.com", "Help", Instant.parse("2026-08-08T12:00:00Z"));
    }
}
