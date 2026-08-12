package com.sib.triage.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sib.triage.ai.AiClassification;
import com.sib.triage.domain.CustomerEnquiry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;

/** JDBC persistence for the current tracker state and append-only AI attempt history. */
public final class SqlServerTriageRepository implements TriageRepository {
    private static final Logger LOGGER = LoggerFactory.getLogger(SqlServerTriageRepository.class);
    static final int MAX_GEN_AI_ID_LENGTH = 56;
    static final int MAX_ERROR_LENGTH = 128;

    /* HOLDLOCK serializes competing registrations for the same natural key. The matched branch
       deliberately leaves requestJSON, channel and reqIssuedAt unchanged. */
    static final String REGISTER = """
            MERGE customer_enquiry_triage_tracker WITH (HOLDLOCK) AS target
            USING (VALUES (?, ?, ?, ?)) AS source(referenceNumber, channel, reqIssuedAt, requestJSON)
              ON target.referenceNumber = source.referenceNumber
            WHEN MATCHED THEN UPDATE SET
              recUpdatedAt = SYSUTCDATETIME(), processingCount = target.processingCount + 1
            WHEN NOT MATCHED THEN INSERT
              (referenceNumber, channel, reqIssuedAt, processingStatus, processingCount, requestJSON)
              VALUES (source.referenceNumber, source.channel, source.reqIssuedAt, 'RECEIVED', 0, source.requestJSON);
            """;
    static final String UPDATE_TRACKER = """
            UPDATE customer_enquiry_triage_tracker
               SET processingStatus = ?, lastErrorMssg = ?, recUpdatedAt = SYSUTCDATETIME()
             WHERE referenceNumber = ?
            """;
    static final String INSERT_HISTORY = """
            INSERT INTO customer_enquiry_triage_history
              (referenceNumber, totalTokens, genAiId, timingJson, aiResponseJson)
            VALUES (?, ?, ?, ?, ?)
            """;

    private final DataSource dataSource;
    private final ObjectMapper mapper;

    public SqlServerTriageRepository(DataSource dataSource, ObjectMapper mapper) {
        this.dataSource = dataSource;
        this.mapper = mapper;
    }

    @Override
    public void registerRequest(CustomerEnquiry enquiry, String requestJson, String correlationId) {
        requireJsonContainer(requestJson, "request JSON");
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(REGISTER)) {
            statement.setString(1, enquiry.meta().refNumber());
            statement.setString(2, enquiry.meta().channel());
            statement.setTimestamp(3, Timestamp.from(enquiry.meta().reqIssuedAt()));
            statement.setString(4, requestJson);
            statement.executeUpdate();
            LOGGER.info("Customer enquiry registered referenceNumber={} status=RECEIVED", enquiry.enquiryId());
        } catch (SQLException e) {
            throw persistenceFailure(enquiry.enquiryId(), correlationId, e);
        }
    }

    @Override
    public void updateSuccess(String referenceNumber, AiClassification result, String correlationId) {
        var timingJson = serializeTimings(referenceNumber, result);
        completeAttempt(referenceNumber, "SUCCESS", null, result.totalTokens(),
                validatedGenAiId(result.id()), timingJson, validJsonOrNull(result.rawResponse()), correlationId);
    }

    @Override
    public void updateFailure(String referenceNumber, String errorMessage, String rawAiResponse,
                              String correlationId) {
        completeAttempt(referenceNumber, "FAILURE", truncate(errorMessage, MAX_ERROR_LENGTH),
                null, null, null, validJsonOrNull(rawAiResponse), correlationId);
    }

    private void completeAttempt(String referenceNumber, String status, String error, Integer tokens,
                                 String genAiId, String timingJson, String aiResponseJson,
                                 String correlationId) {
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            connection.setAutoCommit(false);
            updateTracker(connection, referenceNumber, status, error);
            insertHistory(connection, referenceNumber, tokens, genAiId, timingJson, aiResponseJson);
            connection.commit();
        } catch (SQLException | RuntimeException e) {
            rollback(connection, referenceNumber);
            if (e instanceof PersistenceException persistenceException) throw persistenceException;
            if (e instanceof SQLException sqlException)
                throw persistenceFailure(referenceNumber, correlationId, sqlException);
            throw (RuntimeException) e;
        } finally {
            close(connection, referenceNumber);
        }
    }

    private static void updateTracker(Connection connection, String referenceNumber, String status, String error)
            throws SQLException {
        try (var statement = connection.prepareStatement(UPDATE_TRACKER)) {
            statement.setString(1, status);
            statement.setString(2, error);
            statement.setString(3, referenceNumber);
            if (statement.executeUpdate() != 1)
                throw new PersistenceException("Tracker row not found for " + referenceNumber);
        }
    }

    private static void insertHistory(Connection connection, String referenceNumber, Integer tokens,
                                      String genAiId, String timingJson, String aiResponseJson) throws SQLException {
        try (var statement = connection.prepareStatement(INSERT_HISTORY)) {
            statement.setString(1, referenceNumber);
            if (tokens == null) statement.setNull(2, Types.INTEGER); else statement.setInt(2, tokens);
            statement.setString(3, genAiId);
            statement.setString(4, timingJson);
            statement.setString(5, aiResponseJson);
            if (statement.executeUpdate() != 1)
                throw new PersistenceException("History row was not inserted for " + referenceNumber);
        }
    }

    private String serializeTimings(String referenceNumber, AiClassification result) {
        if (result.timings() == null) return null;
        try {
            return mapper.writeValueAsString(result.timings());
        } catch (JsonProcessingException e) {
            throw new PersistenceException("Failed to serialize AI timings for " + referenceNumber, e);
        }
    }

    String validJsonOrNull(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            var json = mapper.readTree(value);
            return json != null && json.isContainerNode() ? value : null;
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private void requireJsonContainer(String value, String description) {
        if (validJsonOrNull(value) == null) throw new PersistenceException("Invalid " + description);
    }

    static String validatedGenAiId(String value) {
        if (value == null) return null;
        if (value.length() > MAX_GEN_AI_ID_LENGTH)
            throw new PersistenceException("AI response id exceeds " + MAX_GEN_AI_ID_LENGTH + " characters");
        return value;
    }

    static String truncate(String value, int maximumLength) {
        return value == null || value.length() <= maximumLength ? value : value.substring(0, maximumLength);
    }

    private static void rollback(Connection connection, String referenceNumber) {
        if (connection == null) return;
        try {
            connection.rollback();
        } catch (SQLException e) {
            LOGGER.error("Failed to roll back enquiry referenceNumber={}", referenceNumber, e);
        }
    }

    private static void close(Connection connection, String referenceNumber) {
        if (connection == null) return;
        try {
            connection.close();
        } catch (SQLException e) {
            LOGGER.warn("Failed to close database connection referenceNumber={}", referenceNumber, e);
        }
    }

    private static PersistenceException persistenceFailure(String referenceNumber, String correlationId,
                                                           SQLException cause) {
        LOGGER.error("Failed to persist enquiry referenceNumber={} correlationId={} exceptionType={} error={}",
                referenceNumber, correlationId, cause.getClass().getSimpleName(), cause.getMessage());
        return new PersistenceException("Failed to persist enquiry " + referenceNumber, cause);
    }

    public static final class PersistenceException extends RuntimeException {
        public PersistenceException(String message) { super(message); }
        public PersistenceException(String message, Throwable cause) { super(message, cause); }
    }
}
