package com.sib.triage.persistence;

import com.sib.triage.domain.CustomerEnquiry;
import com.sib.triage.domain.TriageResult;
import javax.sql.DataSource;
import java.sql.SQLException;
import java.sql.Timestamp;

public final class SqlServerTriageRepository implements TriageRepository {
    private static final String UPSERT = """
            MERGE customer_enquiry_triage AS target
            USING (SELECT ? AS enquiry_id) AS source ON target.enquiry_id = source.enquiry_id
            WHEN NOT MATCHED THEN INSERT
              (enquiry_id, correlation_id, customer_id, message_text, received_at, intent, urgency_score,
               sentiment, recommended_team, rationale, classified_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);
            """;
    private final DataSource dataSource;

    public SqlServerTriageRepository(DataSource dataSource) { this.dataSource = dataSource; }

    @Override
    public void save(CustomerEnquiry enquiry, TriageResult result, String correlationId) {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(UPSERT)) {
            statement.setString(1, enquiry.enquiryId());
            statement.setString(2, enquiry.enquiryId());
            statement.setString(3, correlationId);
            statement.setString(4, enquiry.customerId());
            statement.setString(5, enquiry.message());
            statement.setTimestamp(6, Timestamp.from(enquiry.receivedAt()));
            statement.setString(7, result.intent());
            statement.setInt(8, result.urgencyScore());
            statement.setString(9, result.sentiment());
            statement.setString(10, result.recommendedTeam());
            statement.setString(11, result.rationale());
            statement.setTimestamp(12, Timestamp.from(result.classifiedAt()));
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new PersistenceException("Failed to persist enquiry " + enquiry.enquiryId(), e);
        }
    }

    public static final class PersistenceException extends RuntimeException {
        public PersistenceException(String message, Throwable cause) { super(message, cause); }
    }
}
