package com.sib.triage.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.sqlserver.jdbc.SQLServerDataSource;
import com.sib.triage.ai.AiClassification;
import com.sib.triage.domain.CustomerEnquiry;
import com.sib.triage.domain.TriageResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Real SQL Server contract tests. Set TRIAGE_TEST_DB_URL (and optionally username/password) to run.
 * The configured database is treated as disposable because the schema script drops its two tables.
 */
class SqlServerTriageRepositoryIntegrationTest {
    private DataSource dataSource;
    private SqlServerTriageRepository repository;

    @BeforeEach void resetSchema() throws Exception {
        var url = System.getenv("TRIAGE_TEST_DB_URL");
        assumeTrue(url != null && !url.isBlank(), "TRIAGE_TEST_DB_URL is not configured");
        var ds = new SQLServerDataSource();
        ds.setURL(url);
        ds.setUser(System.getenv().getOrDefault("TRIAGE_TEST_DB_USERNAME", "sa"));
        ds.setPassword(System.getenv().getOrDefault("TRIAGE_TEST_DB_PASSWORD", ""));
        dataSource = ds;
        executeScript(Files.readString(Path.of("src/main/resources/db/schema.sql")));
        repository = new SqlServerTriageRepository(dataSource, new ObjectMapper());
    }

    @Test void insertsTrackerPreservesOriginalJsonAndHandlesDuplicate() throws Exception {
        var original = " {\"delivery\":1} \n";
        repository.registerRequest(enquiry(), original, "first");
        repository.registerRequest(enquiry(), "{\"delivery\":2}", "duplicate");

        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     SELECT COUNT(*) AS rows, MAX(processingCount) AS processingCount,
                            MAX(requestJSON) AS requestJSON, MAX(processingStatus) AS processingStatus
                       FROM customer_enquiry_triage_tracker WHERE referenceNumber = ?
                     """)) {
            statement.setString(1, "ref-integration");
            try (var result = statement.executeQuery()) {
                assertTrue(result.next());
                assertEquals(1, result.getInt("rows"));
                assertEquals(1, result.getInt("processingCount"));
                assertEquals(original, result.getString("requestJSON"));
                assertEquals("RECEIVED", result.getString("processingStatus"));
            }
        }
    }

    @Test void successfulAttemptsAppendCompleteHistoryRows() throws Exception {
        repository.registerRequest(enquiry(), "{\"request\":true}", "first");
        var raw = "{\"id\":\"ai-1\",\"providerField\":true}";
        var result = new AiClassification(new TriageResult("ok", enquiry()), "ai-1", 27,
                new ObjectMapper().readTree("{\"elapsedMs\":5}"), raw);
        repository.updateSuccess("ref-integration", result, "one");
        repository.updateSuccess("ref-integration", result, "two");

        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement("""
                SELECT COUNT(*) AS rows, MIN(totalTokens) AS tokens, MIN(genAiId) AS genAiId,
                       MIN(timingJson) AS timingJson, MIN(aiResponseJson) AS aiResponseJson,
                       COUNT(historyId) AS generatedIds
                  FROM customer_enquiry_triage_history WHERE referenceNumber = ?
                """)) {
            statement.setString(1, "ref-integration");
            try (var query = statement.executeQuery()) {
                assertTrue(query.next());
                assertEquals(2, query.getInt("rows"));
                assertEquals(2, query.getInt("generatedIds"));
                assertEquals(27, query.getInt("tokens"));
                assertEquals("ai-1", query.getString("genAiId"));
                assertEquals("{\"elapsedMs\":5}", query.getString("timingJson"));
                assertEquals(raw, query.getString("aiResponseJson"));
            }
        }
    }

    @Test void concurrentDuplicatesCreateOneTrackerAndAtomicCount() throws Exception {
        int deliveries = 12;
        try (var executor = Executors.newFixedThreadPool(deliveries)) {
            var tasks = IntStream.range(0, deliveries)
                    .mapToObj(i -> (java.util.concurrent.Callable<Void>) () -> {
                        repository.registerRequest(enquiry(), "{\"delivery\":" + i + "}", "corr-" + i);
                        return null;
                    }).toList();
            for (var future : executor.invokeAll(tasks)) future.get(30, TimeUnit.SECONDS);
        }
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(
                "SELECT COUNT(*) AS rows, MAX(processingCount) AS processingCount " +
                        "FROM customer_enquiry_triage_tracker WHERE referenceNumber = 'ref-integration'");
             var query = statement.executeQuery()) {
            assertTrue(query.next());
            assertEquals(1, query.getInt("rows"));
            assertEquals(deliveries - 1, query.getInt("processingCount"));
        }
    }

    @Test void historyInsertFailureRollsBackTrackerStatus() throws Exception {
        repository.registerRequest(enquiry(), "{\"request\":true}", "first");
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TRIGGER fail_history_insert ON customer_enquiry_triage_history
                    INSTEAD OF INSERT AS THROW 51000, 'forced history failure', 1
                    """);
        }
        assertThrows(SqlServerTriageRepository.PersistenceException.class,
                () -> repository.updateSuccess("ref-integration", new AiClassification(
                        new TriageResult("ok", enquiry()), null, null, null, "{\"ok\":true}"), "corr"));
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(
                "SELECT processingStatus FROM customer_enquiry_triage_tracker WHERE referenceNumber = 'ref-integration'");
             var query = statement.executeQuery()) {
            assertTrue(query.next());
            assertEquals("RECEIVED", query.getString(1));
        }
    }

    private void executeScript(String script) throws Exception {
        try (Connection connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            for (var sql : script.split(";")) if (!sql.isBlank()) statement.execute(sql);
        }
    }

    private static CustomerEnquiry enquiry() {
        return new CustomerEnquiry(new CustomerEnquiry.Meta("ref-integration", "WebSite",
                Instant.parse("2026-08-08T11:59:00Z")), "customer", null, "Sara", "Khan",
                "sara@example.com", "Help", Instant.parse("2026-08-08T12:00:00Z"));
    }
}
