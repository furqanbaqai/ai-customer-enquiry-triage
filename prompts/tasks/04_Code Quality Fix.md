# Role

Act as a **Principal Java Software Engineer and Code Quality Reviewer**.

You are working on the existing Java repository:

`ai-customer-enquiry-triage`

The application uses **Java 21+**, Maven, IBM MQ Jakarta JMS, SQL Server/HikariCP, native `java.net.http.HttpClient`, Resilience4j, Jackson, SLF4J/Logback, and a **Spring-free architecture**.

Your task is to review and update the **entire Java codebase** to introduce consistent, professional, production-quality documentation and code comments.

---

# Primary Objective

Review **all Java source files** in the repository and add appropriate documentation at the following levels:

1. File / class / interface / enum / record level
2. Constructor level
3. Public, protected, and important private method level
4. Important processing blocks within methods
5. Complex business logic
6. Integration points
7. Exception handling and recovery logic
8. Concurrency and asynchronous processing
9. Database operations
10. IBM MQ operations
11. AI service invocation and response processing

The comments must explain **why the code exists and what it is doing**, rather than simply translating Java syntax into English.

---

# 1. Class-Level Documentation

Add standard **Javadoc comments** to every Java:

* class
* interface
* enum
* record
* annotation, if any

Example:

```java
/**
 * Consumes customer enquiry messages from IBM MQ and coordinates
 * asynchronous processing of each enquiry.
 *
 * <p>The consumer validates the incoming payload, applies idempotency
 * checks, persists request metadata, invokes the AI classification
 * service, and routes the resulting response to the appropriate queue.</p>
 *
 * <p>Message processing is offloaded to Java virtual threads to prevent
 * long-running AI inference calls from blocking the JMS consumer.</p>
 */
public final class EnquiryMessageConsumer {
}
```

The class documentation should explain, where applicable:

* Purpose of the class
* Responsibility within the application
* Important collaborators/dependencies
* Input/output
* Threading/concurrency behavior
* External systems used
* Important design decisions
* Important failure/recovery behavior

Do **not** add author names, dates, company names, or copyright information unless such information already exists in the repository.

---

# 2. Method / Procedure Documentation

Treat every meaningful Java method as a **procedure** for the purpose of this requirement.

Add documentation explaining what each procedure does.

For public/protected methods and important private methods, use Javadoc.

Example:

```java
/**
 * Processes an incoming customer enquiry message.
 *
 * <p>The procedure deserializes the JSON payload, validates the reference
 * number, persists the initial processing state, invokes the AI service,
 * and stores the final processing result.</p>
 *
 * @param json incoming customer enquiry JSON payload
 * @param correlationId correlation identifier used for logging and tracing
 * @return a completion stage representing the asynchronous processing operation
 */
public CompletionStage<Void> process(
        String json,
        String correlationId) {
}
```

Where relevant, include:

* `@param`
* `@return`
* `@throws`

Do not add meaningless `@return` descriptions such as:

```java
@return the result
```

Instead describe what the returned value represents.

---

# 3. Internal Code Comments

Inside each procedure, add comments around meaningful processing stages.

Example:

```java
// Parse and validate the incoming customer enquiry before performing
// any database or AI processing.
var enquiry = mapper.readValue(json, CustomerEnquiry.class);

// Persist the request before invoking the AI service so that the
// transaction can be traced even if downstream processing fails.
repository.insertReceivedRequest(enquiry);
```

For longer procedures, use comments to identify major logical phases.

Example:

```java
// 1. Parse and validate the incoming request.
...

// 2. Perform the idempotency check using the reference number.
...

// 3. Persist the initial RECEIVED processing state.
...

// 4. Submit the request to the AI classification service.
...

// 5. Persist the AI result and final processing status.
...

// 6. Route the response to the downstream IBM MQ queue.
...
```

Use numbered comments only when a procedure genuinely represents a multi-stage workflow.

---

# 4. Do Not Over-Comment

Avoid comments that merely repeat obvious Java syntax.

Do NOT produce comments such as:

```java
// Create a new string.
String name = enquiry.firstName();

// Check if result is null.
if (result == null) {
}

// Return the value.
return result;

// Log the message.
LOGGER.info("Message received");
```

Comments should instead explain:

* business intent
* architectural intent
* non-obvious behavior
* assumptions
* important constraints
* reason for implementation choices
* failure handling
* concurrency implications

---

# 5. Database Code

Pay particular attention to repository/database classes.

Add comments explaining:

* Purpose of each SQL operation
* Transaction boundaries
* Idempotency handling
* Unique constraint handling
* Why a field/state is being updated
* Error handling
* Resource lifecycle

For example:

```java
// Insert the request in RECEIVED state before AI processing begins.
// The unique referenceNumber constraint provides the database-level
// idempotency guarantee for duplicate messages.
```

When an existing request is detected, document the behavior clearly.

Example:

```java
// The reference number already exists, so the original request is not
// inserted again. Only recUpdatedAt is refreshed to record the latest
// delivery attempt.
```

---

# 6. IBM MQ / JMS Code

For all IBM MQ and Jakarta JMS components, document:

* Queue being consumed or produced
* Message acknowledgement behavior
* Transaction behavior
* Backout/retry behavior
* Correlation IDs
* Failure queue handling
* Message routing decisions
* Virtual-thread handoff

Example:

```java
// Offload message processing from the JMS listener onto a virtual thread.
// This prevents slow AI inference calls from blocking delivery of subsequent
// IBM MQ messages.
```

For failure routing:

```java
// Forward messages that cannot be processed because of persistence or
// constraint failures to the configured MQ backout queue. This preserves
// the original payload for investigation or controlled replay.
```

---

# 7. AI Integration Code

Document all AI/LLM related processing clearly.

Explain:

* Request construction
* HTTP invocation
* Retry logic
* Circuit breaker behavior
* Timeout behavior
* Response validation
* JSON deserialization
* Token usage capture
* AI response persistence
* Success/failure determination

Example:

```java
// Preserve the complete AI response payload for auditability and future
// troubleshooting while separately extracting frequently queried fields
// such as token usage, inference timing, and response identifier.
```

---

# 8. Asynchronous and Virtual Thread Code

The application uses Java 21+ asynchronous constructs and virtual threads.

Add comments where concurrency behavior may not be immediately obvious.

For example:

```java
// Execute the blocking database operation on the virtual-thread executor.
// Although JDBC is blocking, virtual threads allow the application to
// support a large number of concurrent requests without maintaining a
// large platform-thread pool.
```

For `CompletionStage`, `CompletableFuture`, `thenCompose`, `whenComplete`, etc., describe the workflow rather than the syntax.

Example:

```java
// Continue with response routing only after the database update completes
// successfully. Any exception propagates to the final completion handler.
```

---

# 9. Exception Handling

Review all `try/catch`, exceptional completion, and recovery paths.

Add comments where useful explaining:

* What failure is being handled
* Whether it is recoverable
* Whether it should be retried
* Whether the message is sent to a backout queue
* Whether database status is updated
* Whether the exception is rethrown

Example:

```java
// Persist the failure state before propagating the exception so operational
// support can identify requests that failed after AI invocation.
```

Do not hide exceptions or introduce empty catch blocks.

---

# 10. Configuration Classes

Add documentation to configuration classes explaining:

* Environment-variable-first configuration
* Local development fallback
* Default values
* Required configuration
* Validation

Example:

```java
/**
 * Provides application configuration without depending on the Spring
 * Framework.
 *
 * <p>Production values are resolved primarily from environment variables.
 * Local development may fall back to application-dev.properties when an
 * equivalent environment variable is not defined.</p>
 */
```

Do not expose secrets in comments.

---

# 11. Models / DTOs / Records

For request/response models, add concise class-level documentation.

For fields whose meaning is not obvious, add appropriate documentation.

Example:

```java
/**
 * Metadata supplied by the originating channel for request tracing
 * and idempotency.
 */
public record RequestMeta(
        String refNumber,
        String channel,
        Instant reqIssuedAt) {
}
```

Avoid excessive comments on self-explanatory fields.

---

# 12. Constants and Enums

Document important constants where their purpose is not obvious.

Example:

```java
/**
 * Represents the lifecycle state of a customer enquiry while it is
 * processed by the AI triage worker.
 */
public enum ProcessingStatus {
    RECEIVED,
    SUCCESS,
    FAILURE
}
```

Do not add comments such as:

```java
// Success status
SUCCESS
```

unless additional explanation is required.

---

# 13. Logging

Review existing logging statements while updating comments.

Do not add comments that simply describe logging.

Make sure comments help explain the operation surrounding the log.

Preserve correlation/reference-number logging where currently implemented.

Do not log:

* passwords
* credentials
* connection strings
* tokens
* personally identifiable information unnecessarily
* complete sensitive customer payloads unless explicitly required by existing design

Do not materially redesign logging unless required to correct an obvious issue.

---

# 14. README and Documentation

After reviewing the code:

* Update `README.md` only if required to explain the documentation conventions or architecture more accurately.
* Do not duplicate the entire source-code documentation in README.
* Preserve existing architectural and operational instructions.

If useful, add a small section such as:

```markdown
## Code Documentation Standards

The Java source follows standard Javadoc conventions for classes and
public APIs. Internal comments describe business intent, integration
behavior, concurrency decisions, persistence behavior, and non-obvious
processing logic rather than restating Java syntax.
```

Only add this section if it provides useful value.

---

# 15. Preserve Existing Behavior

This task is primarily a **documentation and maintainability update**.

Do not unnecessarily change:

* application behavior
* queue names
* configuration names
* database schema
* API contracts
* JSON structures
* method signatures
* SQL logic
* transaction semantics
* retry behavior
* concurrency behavior
* exception behavior

Small refactoring is allowed only where necessary to make existing logic understandable or where a clear code-quality defect is discovered.

Do not perform broad architectural refactoring as part of this task.

---

# 16. Comment Quality Standard

All comments must follow these principles:

### Good

```java
// Persist RECEIVED before invoking the AI service so that every accepted
// request has an audit record even if inference subsequently fails.
```

### Bad

```java
// Save request.
```

### Good

```java
// Limit the persisted error text to the database column capacity while
// retaining the complete exception in application logs.
```

### Bad

```java
// Set error message.
```

### Good

```java
// Duplicate reference numbers represent redelivered or previously received
// requests and therefore must not result in a second business transaction.
```

### Bad

```java
// Check duplicate.
```

---

# 17. Documentation Style

Follow standard Java documentation conventions.

Use:

```java
/**
 * Javadoc.
 */
```

for:

* classes
* interfaces
* records
* enums
* constructors where useful
* public methods
* protected methods
* important private methods

Use:

```java
// Inline explanation.
```

for internal implementation details.

Use:

```java
/*
 * Multi-line implementation explanation.
 */
```

only when a longer internal explanation is genuinely required.

Keep comments:

* concise
* professional
* grammatically correct
* technically accurate
* maintainable
* implementation-relevant

Use **English** throughout.

---

# 18. Review Existing Comments

Do not simply add new comments.

Review all existing comments and:

* retain accurate comments
* improve unclear comments
* remove obsolete comments
* remove misleading comments
* remove commented-out dead code unless there is a strong reason to retain it
* standardize terminology

Use consistent terminology throughout the repository, including:

* customer enquiry
* reference number
* correlation ID
* AI service
* AI response
* request queue
* result queue
* failure/backout queue
* processing status
* virtual thread
* repository
* idempotency

---

# 19. Tests

Apply the same documentation principles to test classes.

Add class-level comments explaining the purpose of major test classes.

For complex tests, document:

* scenario being tested
* important setup assumptions
* reason for unusual mocks/stubs
* concurrency behavior where applicable

Do not comment trivial assertions.

Prefer descriptive test method names over excessive comments.

For example:

```java
@Test
void shouldRouteMessageToBackoutQueueWhenDuplicateConstraintHandlingFails() {
```

is preferable to:

```java
// Test duplicate failure.
@Test
void test1() {
```

---

# 20. Repository-Wide Review

Do not limit the changes to the most obvious classes.

Inspect the entire repository, including as applicable:

```text
src/main/java/**
src/test/java/**
```

Review:

* application bootstrap classes
* configuration
* MQ consumers
* MQ producers
* JMS connection management
* message processors
* services
* AI clients
* HTTP clients
* repositories
* database classes
* DTOs
* domain models
* enums
* exceptions
* utilities
* validators
* routing components
* resilience components
* monitoring/health components
* tests

Every meaningful Java source file should be reviewed.

---

# 21. Build and Verification

After making the changes:

1. Format the code consistently with the existing project style.
2. Run the Maven test suite.
3. Compile the complete project.
4. Ensure Javadoc syntax does not introduce compilation errors.
5. Ensure imports remain clean.
6. Ensure there are no unused imports introduced by the changes.
7. Ensure no test behavior has changed unintentionally.

Run, where supported:

```bash
mvn clean test
```

If appropriate:

```bash
mvn clean verify
```

Do not skip failing tests merely to complete the task.

---

# 22. Final Repository Review

Before finishing, inspect the diff and specifically check for:

* meaningless comments
* obvious comments
* stale comments
* misleading comments
* duplicated comments
* grammatical errors
* comments inconsistent with implementation
* excessively verbose Javadoc
* undocumented important procedures
* undocumented complex asynchronous flows
* undocumented SQL/database behavior
* undocumented MQ routing logic
* undocumented exception/recovery logic

The objective is **not maximum comment quantity**.

The objective is that another experienced Java engineer should be able to enter the project and understand:

**what each component does, why important processing decisions exist, how each important procedure works, and how data flows through the application.**

---

# Expected Final Response

After completing the implementation, provide a concise summary containing:

### Files Reviewed

Number of Java source and test files reviewed.

### Documentation Added

Summarize the major documentation improvements, such as:

* class-level Javadocs
* method/procedure-level Javadocs
* database logic comments
* MQ processing comments
* AI integration comments
* asynchronous/virtual-thread comments
* failure handling comments
* test documentation

### Functional Changes

Explicitly state whether any functional code was changed.

If functional changes were necessary, identify each one separately.

### Verification

Report the result of:

```bash
mvn clean test
```

and/or:

```bash
mvn clean verify
```

### Important Findings

List any significant code-quality, architectural, concurrency, database, MQ, or error-handling concerns discovered during the review.

Do **not** fix unrelated major architectural issues silently. Report them separately unless they are necessary for compilation, correctness, or documentation accuracy.
