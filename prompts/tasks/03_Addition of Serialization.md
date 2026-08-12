# Role

Act as a **Principal Java Software Engineer** and update the existing locally checked-out repository:

`ai-customer-enquiry-triage`

The application is a **Java 21+, Spring-free** asynchronous customer-enquiry triage service using IBM MQ, SQL Server, and an AI inference service.

Preserve the existing project architecture, coding conventions, configuration approach, error-handling strategy, logging standards, and dependency choices unless a change is necessary to implement the requirements below.

Do not introduce Spring Framework or unnecessary new libraries.

---

# Objective

Enhance the customer enquiry processing flow so that request lifecycle information and AI response information are persisted in the SQL Server table:

`customer_enquiry_triage_tracker`

The implementation must correctly handle:

* Initial request persistence
* Duplicate requests
* Database constraint violations
* AI success responses
* AI failure responses
* Raw AI response auditing
* MQ backout/failure routing
* Automated tests

---

# Database Table

Assume the following SQL Server table exists:

```sql
CREATE TABLE customer_enquiry_triage_tracker
(
    referenceNumber   NVARCHAR(36)  NOT NULL PRIMARY KEY,
    channel           NVARCHAR(16)  NOT NULL,
    reqIssuedAt       DATETIME2(3)  NOT NULL,

    processingStatus  NVARCHAR(16)  NOT NULL,
    lastErrorMssg     NVARCHAR(128) NULL,
    processingCount   INT           NOT NULL DEFAULT 0,

    totalTokens       INT           NULL,
    genAiId           NVARCHAR(56)  NULL,

    timingJson        NVARCHAR(MAX) NULL,
    aiResponseJson    NVARCHAR(MAX) NULL,

    recCreatedAt      DATETIME2(3)  NOT NULL DEFAULT SYSUTCDATETIME(),
    recUpdatedAt      DATETIME2(3)  NOT NULL DEFAULT SYSUTCDATETIME(),

    CONSTRAINT CK_customer_enquiry_triage_tracker_timingJson
        CHECK (
            timingJson IS NULL
            OR ISJSON(timingJson) = 1
        ),

    CONSTRAINT CK_customer_enquiry_triage_tracker_aiResponseJson
        CHECK (
            aiResponseJson IS NULL
            OR ISJSON(aiResponseJson) = 1
        )
);
```

Use the actual repository SQL/migration mechanism if one already exists. Do not create a second competing database initialization mechanism.

---

# Functional Requirements

## 1. Persist Request When Received

When a message is consumed from the IBM MQ request queue and successfully parsed and validated, use:

* `meta.refNumber` → `referenceNumber`
* `meta.channel` → `channel`
* `meta.requestIssuedAt` → `reqIssuedAt`
* constant `"RECEIVED"` → `processingStatus`

For a new request, insert a record into:

`customer_enquiry_triage_tracker`

Example logical mapping:

```text
referenceNumber  = request.meta.refNumber
channel          = request.meta.channel
reqIssuedAt      = request.meta.requestIssuedAt
processingStatus = "RECEIVED"
```

Allow the database defaults to populate `recCreatedAt` and `recUpdatedAt` where appropriate.

Do not populate AI-related fields during initial request insertion.

---

# 2. Handle Existing Requests

Before inserting, or through an atomic database operation, determine whether the same `referenceNumber` already exists.

If a record already exists:

* Do not insert another record.
* Update `recUpdatedAt` to the current UTC date/time.
* Increment `processingCount` if this field currently represents processing/retry attempts in the existing implementation.

Prefer an atomic SQL operation or a safe update/insert strategy that avoids race conditions.

Do not implement an unsafe:

```text
SELECT -> if not found -> INSERT
```

pattern if concurrent processing could result in duplicate inserts.

The primary key remains:

```text
referenceNumber
```

---

# 3. Database Constraint / Persistence Failure Handling

If request persistence fails because of a database constraint violation, invalid data, or another unrecoverable database validation problem:

1. Log the error with:

   * `referenceNumber`
   * correlation ID, if available
   * relevant exception type
   * concise error message

2. Do not continue with AI processing for that message.

3. Reject/fail the request according to the existing MQ transaction/acknowledgement design.

4. Forward or route the original message to the queue configured using:

```text
MQ_BACKOUT_QUEUE_NAME
```

Use the existing application configuration mechanism for obtaining this value.

Do not hard-code the MQ queue name.

Ensure the behavior does not cause an infinite redelivery loop.

Reuse an existing MQ routing/backout abstraction if the project already contains one.

---

# 4. Update Tracker After AI Response

Once a response is received from the AI inference service, update the existing tracker record identified by:

```text
referenceNumber
```

The update must include the following.

## processingStatus

Set:

```text
SUCCESS
```

when the AI HTTP/API response indicates successful processing.

Set:

```text
FAILURE
```

when the AI response indicates failure or the AI invocation completes unsuccessfully.

Use the existing HTTP response/status abstraction where possible rather than duplicating status interpretation.

---

# 5. lastErrorMssg

For successful processing:

```text
lastErrorMssg = NULL
```

For failed processing:

Store the relevant exception/error message.

Maximum length:

```text
128 characters
```

The Java implementation must safely truncate longer messages before persistence.

For example:

```java
errorMessage.length() > 128
    ? errorMessage.substring(0, 128)
    : errorMessage;
```

Handle `null` error messages safely.

Do not allow error-message persistence itself to cause a database constraint failure.

---

# 6. totalTokens

Populate:

```text
totalTokens = usage.total_tokens
```

from the AI response payload.

Handle cases where:

* `usage` is absent
* `total_tokens` is absent
* the AI request failed before usage information was produced

In these cases, store `NULL` unless the existing domain model specifies another convention.

---

# 7. genAiId

Populate:

```text
genAiId = id
```

from the AI response.

If the AI response does not contain an ID, persist `NULL`.

Respect the database maximum:

```text
NVARCHAR(56)
```

Do not silently persist an invalid oversized value.

Use validation or safe truncation according to the project's existing conventions.

---

# 8. timingJson

Populate:

```text
timingJson = timings
```

from the AI response.

The database column contains valid JSON and has an `ISJSON()` constraint.

If `timings` is represented internally as a Java object, map, record, or DTO, serialize it using the project's existing Jackson `ObjectMapper`.

Example target:

```json
{
  "prompt_n": 1234,
  "prompt_ms": 456.7,
  "predicted_n": 120,
  "predicted_ms": 3200.5
}
```

Do not use:

```java
object.toString()
```

to create JSON.

Use proper Jackson serialization.

If no timings are returned, store `NULL`.

---

# 9. aiResponseJson

Store the **complete raw AI response payload** in:

```text
aiResponseJson
```

This field is intended for:

* audit
* troubleshooting
* AI-result traceability
* future analysis

Prefer storing the exact response body returned by the AI endpoint before transforming it into domain objects.

Do not reconstruct the JSON from selected fields if the original raw response payload is available.

The stored value must remain valid JSON because the database applies:

```sql
ISJSON(aiResponseJson) = 1
```

For a successful AI call:

```text
aiResponseJson = complete raw AI JSON response
```

For an AI failure where a valid JSON response body was received, store that JSON response as well.

If no valid JSON response was received, store `NULL`.

Do not store an HTML error page, stack trace, or arbitrary text in this JSON column.

---

# 10. recUpdatedAt

Whenever the tracker row is updated, set:

```text
recUpdatedAt = current UTC datetime
```

Prefer SQL Server:

```sql
SYSUTCDATETIME()
```

rather than generating the timestamp in Java unless the existing repository has a consistent application-time abstraction.

This applies to:

* duplicate request handling
* AI success update
* AI failure update
* subsequent processing-state updates

---

# 11. Repository / DAO Design

Inspect the existing repository before modifying the implementation.

Extend the current data-access abstraction rather than bypassing it.

A reasonable API may resemble:

```java
void registerRequest(CustomerEnquiry enquiry, String correlationId);

void updateAiResponse(
        String referenceNumber,
        AiProcessingResult result,
        String rawAiResponse,
        String correlationId);
```

However, adapt method names and signatures to the existing design rather than forcing these exact APIs.

Use:

* JDBC
* existing HikariCP `DataSource`
* prepared statements
* try-with-resources

Do not introduce an ORM unless one already exists.

Do not open raw SQL Server connections outside the project's existing connection-pool abstraction.

---

# 12. Transaction Handling

Review the existing transaction boundaries.

Ensure database failures and MQ processing do not result in inconsistent states.

At minimum:

```text
MQ message received
    |
    v
Parse + validate
    |
    v
Register/update request in DB
    |
    +-- persistence failure --> MQ_BACKOUT_QUEUE_NAME
    |
    v
Invoke AI
    |
    v
Update tracker with AI result
    |
    v
Continue existing downstream routing
```

Do not acknowledge a message prematurely if doing so would prevent the application from handling a persistence failure correctly.

Preserve the project's current JMS transaction model unless modification is necessary.

---

# 13. Logging

Add structured and useful logging around the lifecycle.

Examples:

```text
Customer enquiry received referenceNumber={}
Customer enquiry registered referenceNumber={} status=RECEIVED
Existing enquiry detected referenceNumber={}
AI processing completed referenceNumber={} status=SUCCESS totalTokens={}
AI processing failed referenceNumber={} error={}
Failed to persist enquiry referenceNumber={}
Routing failed enquiry to backout queue referenceNumber={} queue={}
```

Do not log sensitive customer information.

Do not log the complete enquiry message.

Avoid logging the complete AI response at INFO level.

Raw AI responses may contain sensitive information and should primarily be persisted in `aiResponseJson`.

---

# 14. Exception Handling

Use specific exception handling where possible.

Differentiate between:

* validation errors
* database constraint violations
* SQL/database availability problems
* AI HTTP errors
* AI timeout errors
* JSON parsing/deserialization errors
* MQ publishing/routing errors

Do not add broad:

```java
catch (Exception e)
```

blocks unless they exist at an appropriate top-level processing boundary.

Preserve the root cause when wrapping exceptions.

---

# 15. AI Response Mapping

Inspect the actual AI response DTO/model.

Update it if necessary to expose:

```text
id
usage.total_tokens
timings
```

For example, if the response structurally resembles:

```json
{
  "id": "cmpl-abc123",
  "choices": [...],
  "usage": {
    "prompt_tokens": 700,
    "completion_tokens": 80,
    "total_tokens": 780
  },
  "timings": {
    "prompt_n": 700,
    "prompt_ms": 3500,
    "predicted_n": 80,
    "predicted_ms": 12000
  }
}
```

ensure the Jackson mapping handles these values correctly.

Use the existing Jackson naming convention.

If Java camelCase fields are used, map snake_case fields using annotations or the configured naming strategy.

---

# 16. Preserve Raw AI HTTP Response

Review the AI HTTP client implementation.

Modify it if necessary so that both are available:

```text
1. Parsed AI response DTO/domain object
2. Original raw JSON response body
```

A wrapper such as the following may be appropriate:

```java
public record AiHttpResponse<T>(
        int statusCode,
        T body,
        String rawBody) {
}
```

This is only an example.

Use the project's existing design if an equivalent abstraction already exists.

Do not serialize the parsed DTO again merely to obtain `aiResponseJson` if the exact original HTTP response body is already available.

---

# 17. SQL Statements

Use parameterized SQL.

Example insert logic:

```sql
INSERT INTO customer_enquiry_triage_tracker
(
    referenceNumber,
    channel,
    reqIssuedAt,
    processingStatus,
    processingCount
)
VALUES
(
    ?, ?, ?, 'RECEIVED', 1
);
```

Example duplicate update:

```sql
UPDATE customer_enquiry_triage_tracker
SET
    recUpdatedAt = SYSUTCDATETIME(),
    processingCount = processingCount + 1
WHERE referenceNumber = ?;
```

Example AI result update:

```sql
UPDATE customer_enquiry_triage_tracker
SET
    processingStatus = ?,
    lastErrorMssg = ?,
    totalTokens = ?,
    genAiId = ?,
    timingJson = ?,
    aiResponseJson = ?,
    recUpdatedAt = SYSUTCDATETIME()
WHERE referenceNumber = ?;
```

Adapt these statements if the existing repository uses another safe SQL pattern.

---

# 18. Tests

Add or update automated tests covering the new behavior.

At minimum cover:

### Request persistence

Verify a newly received enquiry creates a tracker record containing:

```text
referenceNumber = meta.refNumber
channel = meta.channel
reqIssuedAt = meta.requestIssuedAt
processingStatus = RECEIVED
```

### Duplicate request

Verify an existing `referenceNumber`:

* does not create another row
* updates `recUpdatedAt`
* updates `processingCount` if applicable

### Constraint violation

Simulate a database constraint failure and verify:

* AI processing is not invoked
* the original message is routed to `MQ_BACKOUT_QUEUE_NAME`
* the failure is logged/propagated according to existing architecture

### AI success

Verify:

```text
processingStatus = SUCCESS
lastErrorMssg = NULL
totalTokens = usage.total_tokens
genAiId = response.id
timingJson = serialized timings
aiResponseJson = complete raw response
recUpdatedAt = updated
```

### AI failure

Verify:

```text
processingStatus = FAILURE
lastErrorMssg = error message <= 128 characters
recUpdatedAt = updated
```

Also verify available AI response metadata is persisted when appropriate.

### Long error message

Verify an error message longer than 128 characters cannot break persistence and is safely reduced to the supported length.

### Invalid/no AI JSON response

Verify invalid non-JSON content is not inserted into `aiResponseJson`.

### Missing usage

Verify missing:

```text
usage.total_tokens
```

does not cause a `NullPointerException`.

### Missing timings

Verify missing timings results in:

```text
timingJson = NULL
```

### Raw response preservation

Verify `aiResponseJson` contains the original complete AI JSON response rather than only the parsed classification fields.

---

# 19. Testing Approach

Reuse the project's existing testing stack.

Prefer:

* JUnit 5
* Mockito, if already present
* existing repository/integration-test utilities

Do not add Testcontainers unless there is a clear need and it fits the existing project.

Tests must remain deterministic.

Avoid real calls to:

* IBM MQ
* AI inference endpoint
* production SQL Server

in unit tests.

Mock or fake these boundaries appropriately.

---

# 20. README.md

Review `README.md`.

Update it only where required.

Document at least:

### Tracker lifecycle

```text
RECEIVED
   |
   v
AI processing
   |
   +---- SUCCESS
   |
   +---- FAILURE
```

### Persistence fields

Briefly document:

* `referenceNumber`
* `processingStatus`
* `processingCount`
* `totalTokens`
* `genAiId`
* `timingJson`
* `aiResponseJson`

### Failure routing

Document that unrecoverable request/persistence failures are routed using:

```text
MQ_BACKOUT_QUEUE_NAME
```

Do not duplicate documentation already present elsewhere.

---

# 21. Configuration

Reuse the existing environment-variable-first configuration implementation.

Ensure:

```text
MQ_BACKOUT_QUEUE_NAME
```

is available through the application's configuration abstraction.

Do not access:

```java
System.getenv("MQ_BACKOUT_QUEUE_NAME")
```

throughout business code if the project already provides an `AppConfig` or equivalent configuration abstraction.

---

# 22. Backward Compatibility

Do not break existing:

* IBM MQ processing
* AI classification
* RESULT queue routing
* FALLBACK processing
* SLA watchdog integration
* correlation-ID propagation
* Resilience4j behavior
* virtual-thread execution
* application configuration
* logging

Make the smallest coherent architectural change necessary.

---

# 23. Code Quality Requirements

Use Java 21 features where they improve readability.

Prefer:

* records for immutable transport objects where already consistent with the project
* `Optional` only where it improves API semantics
* `CompletableFuture` / `CompletionStage` consistent with existing async processing
* virtual threads through the project's existing executor
* immutable DTOs where practical

Avoid:

* blocking arbitrary platform-thread pools
* introducing Spring
* static global database connections
* string-built SQL
* duplicated JSON parsers
* duplicated HTTP clients
* swallowing exceptions

---

# 24. Important Implementation Detail

The repository currently uses asynchronous processing.

Do not convert the architecture into a synchronous processing model merely to implement database persistence.

Preserve the existing async chain, for example conceptually:

```text
receive
   -> parse
   -> persist RECEIVED
   -> invoke AI
   -> persist AI result
   -> route result
```

Ensure failures propagate correctly through the existing `CompletionStage` / `CompletableFuture` chain.

---

# 25. Deliverables

Implement the changes directly in the repository.

After implementation:

1. Show the files created or modified.
2. Briefly explain the architectural changes.
3. Show the important SQL/database-access changes.
4. Show how raw AI responses are preserved.
5. Explain database-failure-to-MQ-backout behavior.
6. List tests added or modified.
7. Run the project's relevant tests.
8. Run:

```bash
mvn test
```

9. If appropriate, also run:

```bash
mvn clean verify
```

10. Fix compilation or test failures caused by the changes.
11. Do not modify unrelated code.
12. Do not commit or push changes unless explicitly instructed.

---

# Acceptance Criteria

The implementation is complete when all of the following are true:

* A new valid request creates a tracker row with status `RECEIVED`.
* `meta.refNumber` is stored as `referenceNumber`.
* `meta.channel` is stored as `channel`.
* `meta.requestIssuedAt` is stored as `reqIssuedAt`.
* Duplicate reference numbers do not create duplicate records.
* Duplicate processing updates `recUpdatedAt`.
* Database constraint failures stop AI processing.
* Constraint/unrecoverable persistence failures route the message to `MQ_BACKOUT_QUEUE_NAME`.
* AI success updates status to `SUCCESS`.
* AI failure updates status to `FAILURE`.
* AI error messages are safely limited to 128 characters.
* `usage.total_tokens` populates `totalTokens`.
* AI `id` populates `genAiId`.
* AI `timings` populate valid JSON in `timingJson`.
* The complete raw valid AI response is stored in `aiResponseJson`.
* `recUpdatedAt` is refreshed on state changes.
* Existing asynchronous processing remains intact.
* Existing MQ/result routing behavior remains intact.
* Relevant unit/integration tests pass.
* README documentation is updated where necessary.
* The project builds successfully with Maven.
