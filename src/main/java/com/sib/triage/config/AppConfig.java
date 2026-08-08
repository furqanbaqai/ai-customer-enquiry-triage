package com.sib.triage.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

public final class AppConfig {
    public static final String DEFAULT_QUEUE = "AI.CUST.ENQ.TRIAGE.REQUEST.Q";
    private final Map<String, String> environment;
    private final Properties local;

    private AppConfig(Map<String, String> environment, Properties local) {
        this.environment = Map.copyOf(environment);
        this.local = local;
    }

    public static AppConfig load() {
        var configuredPath = System.getenv().getOrDefault("APP_CONFIG_FILE", "application-dev.properties");
        return from(System.getenv(), Path.of(configuredPath));
    }

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

    public String required(String key) {
        var value = value(key, null);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing required configuration: " + key);
        return value;
    }

    public String value(String key, String defaultValue) {
        var environmentValue = environment.get(key);
        return environmentValue != null && !environmentValue.isBlank()
                ? environmentValue : local.getProperty(key, defaultValue);
    }

    public int integer(String key, int defaultValue) {
        try { return Integer.parseInt(value(key, Integer.toString(defaultValue))); }
        catch (NumberFormatException e) { throw new IllegalStateException(key + " must be an integer", e); }
    }

    public Duration durationSeconds(String key, int defaultValue) {
        return Duration.ofSeconds(integer(key, defaultValue));
    }

    public Mq mq() {
        return new Mq(required("MQ_HOST"), integer("MQ_PORT", 1414), required("MQ_CHANNEL"),
                required("MQ_QUEUE_MANAGER"), value("MQ_QUEUE_NAME", DEFAULT_QUEUE),
                value("MQ_USER", ""), value("MQ_PASSWORD", ""));
    }

    public Database database() {
        return new Database(required("DB_URL"), required("DB_USER"), required("DB_PASSWORD"), integer("DB_POOL_SIZE", 10));
    }

    public HttpEndpoint ai() {
        return new HttpEndpoint(required("AI_API_URL"), required("AI_API_KEY"), durationSeconds("AI_TIMEOUT_SECONDS", 15));
    }

    public HttpEndpoint routing() {
        return new HttpEndpoint(required("ROUTING_API_URL"), value("ROUTING_API_KEY", ""), durationSeconds("ROUTING_TIMEOUT_SECONDS", 10));
    }

    public int urgencyThreshold() { return integer("URGENCY_THRESHOLD", 8); }

    public record Mq(String host, int port, String channel, String queueManager, String queueName,
                     String username, String password) {
        public Mq { Objects.requireNonNull(host); Objects.requireNonNull(queueName); }
    }
    public record Database(String url, String username, String password, int maximumPoolSize) {}
    public record HttpEndpoint(String url, String apiKey, Duration timeout) {}
}
