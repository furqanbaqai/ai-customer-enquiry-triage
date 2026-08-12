package com.sib.triage.service;

import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Validates incoming customer-enquiry payloads against the application's JSON contract.
 *
 * <p>The schema is loaded once during construction so every submitted message is checked
 * against the same expected structure before any AI call or database write is attempted.</p>
 */
public final class EnquirySchemaValidator {
    public static final String SCHEMA_RESOURCE =
            "/com/sib/triage/ai/schema/equiry-request-schema-v1.0.json";

    private final Schema schema;

    /**
     * Builds a validator from the packaged JSON schema resource.
     */
    public EnquirySchemaValidator() {
        var registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
        schema = registry.getSchema(loadSchema(), InputFormat.JSON);
    }

    /**
     * Validates the wire-format payload before the request enters the processing pipeline.
     *
     * @param json raw JSON request received from IBM MQ or another upstream channel
     * @throws IllegalArgumentException when the payload violates the expected schema
     */
    public void validate(String json) {
        var errors = schema.validate(json, InputFormat.JSON, context ->
                context.executionConfig(config -> config.formatAssertionsEnabled(true)));
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("Enquiry does not match JSON schema: " + errors);
        }
    }

    /**
     * Loads the packaged JSON schema resource from the classpath.
     *
     * @return the raw schema definition as UTF-8 text
     */
    private static String loadSchema() {
        try (var input = EnquirySchemaValidator.class.getResourceAsStream(SCHEMA_RESOURCE)) {
            if (input == null) throw new IllegalStateException("JSON schema resource not found: " + SCHEMA_RESOURCE);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot load JSON schema resource: " + SCHEMA_RESOURCE, e);
        }
    }
}
