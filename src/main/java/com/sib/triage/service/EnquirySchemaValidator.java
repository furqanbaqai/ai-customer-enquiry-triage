package com.sib.triage.service;

import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

public final class EnquirySchemaValidator {
    public static final String SCHEMA_RESOURCE =
            "/com/sib/triage/ai/schema/equiry-request-schema-v1.0.json";

    private final Schema schema;

    public EnquirySchemaValidator() {
        var registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
        schema = registry.getSchema(loadSchema(), InputFormat.JSON);
    }

    public void validate(String json) {
        var errors = schema.validate(json, InputFormat.JSON, context ->
                context.executionConfig(config -> config.formatAssertionsEnabled(true)));
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("Enquiry does not match JSON schema: " + errors);
        }
    }

    private static String loadSchema() {
        try (var input = EnquirySchemaValidator.class.getResourceAsStream(SCHEMA_RESOURCE)) {
            if (input == null) throw new IllegalStateException("JSON schema resource not found: " + SCHEMA_RESOURCE);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot load JSON schema resource: " + SCHEMA_RESOURCE, e);
        }
    }
}
