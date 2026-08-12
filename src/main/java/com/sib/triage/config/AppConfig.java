package com.sib.triage.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/**
 * Resolves runtime configuration without depending on Spring.
 *
 * <p>Environment variables take precedence over local property files so the same code can be
 * deployed with secure runtime configuration while still supporting local development through a
 * small properties file.</p>
 */
public final class AppConfig {
    public static final String DEFAULT_QUEUE = "AI.CUST.ENQ.TRIAGE.REQUEST.Q";
    public static final String DEFAULT_BACKOUT_QUEUE = "AI.CUST.ENQ.TRIAGE.BACKOUT.Q";
    public static final String DEFAULT_RESULT_QUEUE = "AI.CUST.ENQ.TRIAGE.RESULT.Q";
    private final Map<String, String> environment;
    private final Properties local;

    private AppConfig(Map<String, String> environment, Properties local) {
        this.environment = Map.copyOf(environment);
        this.local = local;
    }

    /**
     * Loads application configuration using the configured local properties file.
     *
     * @return application configuration built from environment variables and optional local overrides
     */
    public static AppConfig load() {
        var configuredPath = System.getenv().getOrDefault("APP_CONFIG_FILE", "application-dev.properties");
        return from(System.getenv(), Path.of(configuredPath));
    }

    /**
     * Builds configuration from an explicitly supplied environment map and optional property file.
     *
     * @param environment runtime environment values; these take precedence over local properties
     * @param localFile optional file that provides default settings for development work
     * @return a configuration object for the application
     */
    public static AppConfig from(Map<String, String> environment, Path localFile) {
        var properties = new Properties();
        if (Files.isRegularFile(localFile)) {
            try (InputStream input = Files.newInputStream(localFile)) {
                properties.load(input);
            } catch (IOException e) {
                throw new IllegalStateException("Cannot load configuration file " + localFile, e);
            }
        }
        return new AppConfig(environment, properties);
    }

    /**
     * Returns a required configuration value or fails fast when the setting is absent.
     *
     * @param key configuration key to resolve
     * @return the resolved configuration value
     */
    public String required(String key) {
        var value = value(key, null);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing required configuration: " + key);
        return value;
    }

    /**
     * Resolves a configuration key using the environment before falling back to the local file.
     *
     * @param key configuration key name
     * @param defaultValue value used when neither source defines the setting
     * @return the resolved value or the supplied fallback
     */
    public String value(String key, String defaultValue) {
        var environmentValue = environment.get(key);
        return environmentValue != null && !environmentValue.isBlank()
                ? environmentValue : local.getProperty(key, defaultValue);
    }

    /**
     * Parses an integer configuration value and throws a descriptive exception on invalid input.
     *
     * @param key configuration key name
     * @param defaultValue fallback used when the key is not defined
     * @return the parsed integer value
     */
    public int integer(String key, int defaultValue) {
        try { return Integer.parseInt(value(key, Integer.toString(defaultValue))); }
        catch (NumberFormatException e) { throw new IllegalStateException(key + " must be an integer", e); }
    }

    /**
     * Converts a configuration value in seconds to a Duration.
     *
     * @param key configuration key name
     * @param defaultValue default number of seconds used when the key is absent
     * @return a Duration derived from the configured seconds value
     */
    public Duration durationSeconds(String key, int defaultValue) {
        return Duration.ofSeconds(integer(key, defaultValue));
    }

    /**
     * Builds the IBM MQ connection configuration used by the JMS listener and result publisher.
     *
     * @return MQ connection and queue metadata
     */
    public Mq mq() {
        return new Mq(required("MQ_HOST"), integer("MQ_PORT", 1414), required("MQ_CHANNEL"),
                required("MQ_QUEUE_MANAGER"), value("MQ_QUEUE_NAME", DEFAULT_QUEUE),
                value("MQ_BACKOUT_QUEUE_NAME", DEFAULT_BACKOUT_QUEUE),
                value("MQ_RESULT_QUEUE_NAME", DEFAULT_RESULT_QUEUE),
                value("MQ_USER", ""), value("MQ_PASSWORD", ""));
    }

    /**
     * Builds the SQL Server data source configuration.
     *
     * @return configuration for the Hikari connection pool and database connectivity
     */
    public Database database() {
        return new Database(required("DB_URL"), required("DB_USER"), required("DB_PASSWORD"), integer("DB_POOL_SIZE", 10));
    }

    /**
     * Builds the HTTP endpoint configuration used by the AI inference client.
     *
     * @return AI endpoint metadata including URL, API key, and timeout
     */
    public HttpEndpoint ai() {
        return new HttpEndpoint(required("AI_API_URL"), required("AI_API_KEY"), durationSeconds("AI_TIMEOUT_SECONDS", 15));
    }

    /**
     * Configuration describing the IBM MQ listener, backout queue, and result queue.
     */
    public record Mq(String host, int port, String channel, String queueManager, String queueName,
                     String backoutQueueName, String resultQueueName,
                     String username, String password) {
        public Mq {
            Objects.requireNonNull(host);
            Objects.requireNonNull(queueName);
            Objects.requireNonNull(backoutQueueName);
            Objects.requireNonNull(resultQueueName);
        }
    }
    /**
     * Database connection settings consumed by the Hikari connection pool.
     */
    public record Database(String url, String username, String password, int maximumPoolSize) {}

    /**
     * An HTTP endpoint definition for upstream or downstream service integration.
     */
    public record HttpEndpoint(String url, String apiKey, Duration timeout) {}
}
