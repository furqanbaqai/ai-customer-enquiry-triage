package com.sib.triage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sib.triage.ai.AiHttpClassifier;
import com.sib.triage.config.AppConfig;
import com.sib.triage.messaging.MqEnquiryConsumer;
import com.sib.triage.messaging.MqTriageResultPublisher;
import com.sib.triage.persistence.SqlServerTriageRepository;
import com.sib.triage.service.TriagePipeline;
import com.sib.triage.service.EnquirySchemaValidator;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.net.http.HttpClient;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

/**
 * Bootstraps the customer-enquiry triage service.
 *
 * <p>The application wires together the IBM MQ listener, SQL Server persistence layer,
 * AI classification client, and downstream result publisher, then blocks indefinitely so
 * the JVM remains available for inbound message processing.</p>
 */
public final class TriageApplication {
    private static final Logger LOGGER = LoggerFactory.getLogger(TriageApplication.class);
    private TriageApplication() {}

    /**
     * Starts the triage service and keeps the process alive for message-driven processing.
     *
     * @param args command-line arguments; the application does not currently use them
     * @throws InterruptedException if the main thread is interrupted while waiting for shutdown
     */
    public static void main(String[] args) throws InterruptedException {
        displayStartupBanner();
        var config = AppConfig.load();
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        var mapper = new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        var httpClient = HttpClient.newBuilder().executor(executor).version(HttpClient.Version.HTTP_2).build();
        var dataSource = dataSource(config.database());
        var classifier = new AiHttpClassifier(httpClient, mapper, config.ai());
        var repository = new SqlServerTriageRepository(dataSource, mapper);
        var resultPublisher = new MqTriageResultPublisher(config.mq(), mapper);
        var pipeline = new TriagePipeline(mapper, new EnquirySchemaValidator(), classifier, repository,
                resultPublisher, executor);
        var consumer = new MqEnquiryConsumer(config.mq(), pipeline, executor);

        Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().name("shutdown").unstarted(() -> {
            LOGGER.info("Shutting down triage service");
            consumer.close();
            resultPublisher.close();
            dataSource.close();
            executor.close();
        }));
        consumer.start();
        LOGGER.info("AI customer enquiry triage service is ready");
        new CountDownLatch(1).await();
    }

    /**
     * Prints the service banner to the console so startup diagnostics are visible in local
     * and CI environments without relying on a configured logging backend.
     */
    private static void displayStartupBanner() {
        System.out.print("""
                ----------------------------------------------------------------
                            CUSTOMER ENQUIRY TRIAGE SERVICE
                ----------------------------------------------------------------
                """);
    }

    /**
     * Creates the SQL Server connection pool used by the repository layer.
     *
     * @param config database connection configuration resolved from environment or local properties
     * @return a configured HikariCP data source for contact with the tracking database
     */
    private static HikariDataSource dataSource(AppConfig.Database config) {
        var hikari = new HikariConfig();
        hikari.setJdbcUrl(config.url());
        hikari.setUsername(config.username());
        hikari.setPassword(config.password());
        hikari.setMaximumPoolSize(config.maximumPoolSize());
        hikari.setMinimumIdle(1);
        hikari.setPoolName("triage-sqlserver");
        return new HikariDataSource(hikari);
    }
}
