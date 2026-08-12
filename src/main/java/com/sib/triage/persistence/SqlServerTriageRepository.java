package com.sib.triage.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sib.triage.ai.AiClassification;
import com.sib.triage.domain.CustomerEnquiry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.sql.Timestamp;

/**
 * Persists the lifecycle state of a customer enquiry in SQL Server.
 *
 * <p>The repository uses the reference number as the natural idempotency key and records every
 * accepted message in a tracker table before AI processing begins. The same tracker row is then
 * updated on success or failure to provide an operational audit trail for the full triage flow.</p>
 */
public final class SqlServerTriageRepository implements TriageRepository {
    private static final Logger LOGGER = LoggerFactory.getLogger(SqlServerTriageRepository.class);
    static final int MAX_GEN_AI_ID_LENGTH = 56;
    // HOLDLOCK makes the existence check and insert/update one serializable operation,
    // preventing concurrent deliveries of the same reference from inserting twice.
    private static final String REGISTER = """
            MERGE customer_enquiry_triage_tracker WITH (HOLDLOCK) AS target
            USING (VALUES (?, ?, ?)) AS source(referenceNumber, channel, reqIssuedAt)
              ON target.referenceNumber = source.referenceNumber
            WHEN MATCHED THEN UPDATE SET
              recUpdatedAt = SYSUTCDATETIME(), processingCount = target.processingCount + 1
            WHEN NOT MATCHED THEN INSERT
              (referenceNumber, channel, reqIssuedAt, processingStatus, processingCount)
              VALUES (source.referenceNumber, source.channel, source.reqIssuedAt, 'RECEIVED', 1);
            """;
    private static final String UPDATE = """
            UPDATE customer_enquiry_triage_tracker SET processingStatus = ?, lastErrorMssg = ?,
              totalTokens = ?, genAiId = ?, timingJson = ?, aiResponseJson = ?,
              recUpdatedAt = SYSUTCDATETIME() WHERE referenceNumber = ?
            """;
    private final DataSource dataSource;
    private final ObjectMapper mapper;

    /**
     * Creates a repository bound to the application data source and JSON mapper.
     *
     * @param dataSource SQL Server connection pool used for tracker state changes
     * @param mapper Jackson mapper used to validate and serialize audit JSON columns
     */
    public SqlServerTriageRepository(DataSource dataSource, ObjectMapper mapper) {
        this.dataSource = dataSource;
        this.mapper = mapper;
    }

    /**
     * Inserts or refreshes the tracker row for a customer enquiry before the AI call begins.
     *
     * @param enquiry inbound enquiry being processed
     * @param correlationId operation identifier for logs and audit tracing
     */
    @Override public void registerRequest(CustomerEnquiry enquiry, String correlationId) {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(REGISTER)) {
            statement.setString(1, enquiry.meta().refNumber());
            statement.setString(2, enquiry.meta().channel());
            statement.setTimestamp(3, Timestamp.from(enquiry.meta().reqIssuedAt()));
            statement.executeUpdate();
            LOGGER.info("Customer enquiry registered referenceNumber={} status=RECEIVED", enquiry.enquiryId());
        } catch (SQLException e) {
            throw persistenceFailure(enquiry.enquiryId(), correlationId, e);
        }
    }

    /**
     * Stores the successful AI result and the provider metadata in the tracker row.
     *
     * @param referenceNumber unique enquiry identifier used as the tracker row key
     * @param result detailed AI response plus provider metadata
     * @param correlationId operation identifier for logs and audit tracing
     */
    @Override public void updateSuccess(String referenceNumber, AiClassification result, String correlationId) {
        String timings = null;
        try {
            // Serialize the actual JSON tree; JsonNode.toString() is intentionally not
            // used as the persistence contract is owned by the configured ObjectMapper.
            if (result.timings() != null) timings = mapper.writeValueAsString(result.timings());
        } catch (JsonProcessingException e) {
            throw new PersistenceException("Failed to serialize AI timings for " + referenceNumber, e);
        }
        update(referenceNumber, "SUCCESS", null, result.totalTokens(), validatedGenAiId(result.id()), timings,
               validJsonOrNull(result.rawResponse()), correlationId);
    }

    /**
     * Stores the failure reason and any captured raw AI response when inference fails after the
     * request has been accepted.
     *
     * @param referenceNumber unique enquiry identifier used as the tracker row key
     * @param errorMessage human-readable failure reason surfaced to operators
     * @param rawAiResponse raw provider payload when it is valid JSON and safe to persist
     * @param correlationId operation identifier for logs and audit tracing
     */
    @Override public void updateFailure(String referenceNumber, String errorMessage, String rawAiResponse,
                                       String correlationId) {
        update(referenceNumber, "FAILURE", truncate(errorMessage, 128), null, null, null,
               validJsonOrNull(rawAiResponse), correlationId);
    }

    /**
     * Common SQL update path used for both success and failure state transitions.
     *
     * @param referenceNumber tracker row key
     * @param status new processing status to persist
     * @param error persisted error message for failed requests
     * @param tokens token count reported by the AI provider
     * @param id provider identifier, trimmed to the database column limit
     * @param timings JSON data representing AI timing metadata
     * @param rawResponse raw AI JSON payload if valid JSON is available
     * @param correlationId tracing identifier for logging
     */
    private void update(String referenceNumber, String status, String error, Integer tokens, String id,
                       String timings, String rawResponse, String correlationId) {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(UPDATE)) {
            statement.setString(1, status);
            statement.setString(2, error);
            if (tokens == null) statement.setNull(3, java.sql.Types.INTEGER); else statement.setInt(3, tokens);
            statement.setString(4, id);
            statement.setString(5, timings);
            statement.setString(6, rawResponse);
            statement.setString(7, referenceNumber);
            if (statement.executeUpdate() != 1)
               throw new PersistenceException("Tracker row not found for " + referenceNumber);
        } catch (SQLException e) {
            throw persistenceFailure(referenceNumber, correlationId, e);
        }
    }

    /**
     * Persists a JSON string only when it is a complete object or array document.
     *
     * @param value raw provider payload
     * @return original value if valid JSON container, otherwise null
     */
    private String validJsonOrNull(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            var json = mapper.readTree(value);
            // SQL Server ISJSON accepts complete JSON documents. Restrict audit data
            // to object/array payloads so scalar text cannot violate that constraint.
            return json != null && json.isContainerNode() ? value : null;
        }
        catch (JsonProcessingException e) { return null; }
    }

    /**
     * Ensures the provider ID fits the database column width used by the tracker table.
     *
     * @param value provider-assigned identifier
     * @return validated identifier if within the maximum supported length
     */
    static String validatedGenAiId(String value) {
        if (value == null) return null;
        if (value.length() > MAX_GEN_AI_ID_LENGTH)
            throw new PersistenceException("AI response id exceeds " + MAX_GEN_AI_ID_LENGTH + " characters");
        return value;
    }

    /**
     * Truncates a failure message to the maximum length supported by the SQL Server column.
     *
     * @param value original error text
     * @param maximumLength maximum supported characters
     * @return original text when short enough, otherwise truncated text
     */
    static String truncate(String value, int maximumLength) {
        return value == null || value.length() <= maximumLength ? value : value.substring(0, maximumLength);
    }

    /**
     * Logs the persistence failure and rethrows a repository-specific wrapped exception.
     *
     * @param referenceNumber enquiry identifier associated with the repository failure
     * @param correlationId operation identifier used in diagnostics
     * @param cause underlying SQL exception
     * @return repository-specific failure used by upstream pipeline logic
     */
    private static PersistenceException persistenceFailure(String referenceNumber, String correlationId,
                                                           SQLException cause) {
        LOGGER.error("Failed to persist enquiry referenceNumber={} correlationId={} exceptionType={} error={}",
               referenceNumber, correlationId, cause.getClass().getSimpleName(), cause.getMessage());
        return new PersistenceException("Failed to persist enquiry " + referenceNumber, cause);
    }

    /**
     * Indicates that the tracker table could not be updated or queried successfully.
     */
    public static final class PersistenceException extends RuntimeException {
        public PersistenceException(String message) { super(message); }
        public PersistenceException(String message, Throwable cause) { super(message, cause); }
    }
}
