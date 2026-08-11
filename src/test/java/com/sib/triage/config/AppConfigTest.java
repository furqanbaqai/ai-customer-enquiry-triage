package com.sib.triage.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class AppConfigTest {
    @TempDir Path directory;

    @Test void environmentOverridesDevelopmentProperties() throws Exception {
        var file = directory.resolve("application-dev.properties");
        Files.writeString(file, "MQ_HOST=from-file\nMQ_QUEUE_NAME=file-q\n");
        var config = AppConfig.from(Map.of("MQ_HOST", "from-environment"), file);
        assertEquals("from-environment", config.value("MQ_HOST", "missing"));
        assertEquals("file-q", config.value("MQ_QUEUE_NAME", "missing"));
    }

    @Test void missingRequiredValueHasActionableMessage() {
        var config = AppConfig.from(Map.of(), directory.resolve("absent"));
        var error = assertThrows(IllegalStateException.class, () -> config.required("DB_URL"));
        assertTrue(error.getMessage().contains("DB_URL"));
    }

    @Test void backoutQueueHasDefaultAndEnvironmentOverride() {
        var requiredMq = Map.of(
                "MQ_HOST", "localhost", "MQ_CHANNEL", "DEV.APP.SVRCONN",
                "MQ_QUEUE_MANAGER", "QM1", "MQ_BACKOUT_QUEUE_NAME", "CUSTOM.BACKOUT.Q");
        assertEquals("CUSTOM.BACKOUT.Q",
                AppConfig.from(requiredMq, directory.resolve("absent")).mq().backoutQueueName());

        var withoutOverride = new java.util.HashMap<>(requiredMq);
        withoutOverride.remove("MQ_BACKOUT_QUEUE_NAME");
        assertEquals(AppConfig.DEFAULT_BACKOUT_QUEUE,
                AppConfig.from(withoutOverride, directory.resolve("absent")).mq().backoutQueueName());
    }
}
