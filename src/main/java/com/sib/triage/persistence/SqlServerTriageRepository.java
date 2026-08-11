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

public final class SqlServerTriageRepository implements TriageRepository {
    private static final Logger LOGGER = LoggerFactory.getLogger(SqlServerTriageRepository.class);
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

    public SqlServerTriageRepository(DataSource dataSource, ObjectMapper mapper) {
        this.dataSource = dataSource;
        this.mapper = mapper;
    }

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

    @Override public void updateFailure(String referenceNumber, String errorMessage, String rawAiResponse,
                                        String correlationId) {
        update(referenceNumber, "FAILURE", truncate(errorMessage, 128), null, null, null,
                validJsonOrNull(rawAiResponse), correlationId);
    }

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

    private static String validatedGenAiId(String value) {
        if (value == null) return null;
        if (value.length() > 35) throw new PersistenceException("AI response id exceeds 35 characters");
        return value;
    }

    static String truncate(String value, int maximumLength) {
        return value == null || value.length() <= maximumLength ? value : value.substring(0, maximumLength);
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
