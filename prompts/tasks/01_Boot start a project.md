Role: Principal Java Software Engineer

Task: Update and extend the existing, locally checked-out repository `ai-customer-enquiry-triage`. Implement modern, lightweight, Spring-free Java 21+ architecture to process customer enquiry messages, run AI classification, store results, and route downstream notifications.

---

### Technical Stack & Dependencies
* **JDK:** Java 21+ (utilize Virtual Threads via `Executors.newVirtualThreadPerTaskExecutor()`).
* **Build System:** Apache Maven (`pom.xml`).
* **Messaging:** Official IBM MQ Java Client (`com.ibm.mq:com.ibm.mq.jakarta.client`, Jakarta JMS 3.0+).
* **Database & Pooling:** Microsoft SQL Server (`com.microsoft.sqlserver:mssql-jdbc`) with HikariCP connection pooling (`com.zaxxer:HikariCP`).
* **HTTP Client:** Native `java.net.http.HttpClient` configured for async execution with Virtual Threads.
* **Logging & Parsing:** SLF4J + Logback, Jackson for JSON serialization/deserialization.
* **Framework Restriction:** Zero Spring Framework dependencies.

---

### Functional & Architectural Requirements

1. **Messaging & Consumer Logic:**
   * Listen for incoming messages on the designated request queue: `AI.CUST.ENQ.TRIAGE.REQUEST.Q`.
   * Implement an asynchronous Jakarta JMS listener loop that offloads incoming payload processing onto Virtual Threads to ensure high-throughput, non-blocking operation.

2. **Externalized Configuration Strategy:**
   * Create a lightweight configuration provider (e.g., `AppConfig.java`) using standard Java utilities or lightweight libraries (no Spring).
   * **Production:** Primary configuration source must read from System Environment Variables (e.g., `MQ_HOST`, `MQ_QUEUE_NAME`, `DB_URL`, `DB_USER`, `DB_PASSWORD`, `AI_API_KEY`).
   * **Development:** Fallback gracefully to loading key-value pairs from a local development file (e.g., `config.properties` or `application-dev.properties`).
   * Provide a committed template file named `application-dev.properties.example` in the repository root or resources directory with sample placeholder values.

3. **Repository Management (`.gitignore`):**
   * Update or create the `.gitignore` file to explicitly exclude all local runtime and secret properties files (`application-dev.properties`, `config.properties`, `*.env`, `*.local`).

4. **Pipeline Execution Flow:**
   * **Ingest:** Consume customer enquiry payloads from `AI.CUST.ENQ.TRIAGE.REQUEST.Q`.
   * **Parse & Triage:** Parse JSON into a strongly typed `CustomerEnquiry` Record. Call an upstream AI/LLM classification service via `HttpClient` to obtain intent, urgency score, sentiment, and team routing recommendations.
   * **Route:** Issue non-blocking downstream REST calls for high-urgency/escalated cases.

5. **Code Style & Infrastructure Guidelines:**
   * Modern Java 21 semantics: Records, Pattern Matching, Sealed Types, and `var` where readable.
   * Structured, contextual logging via SLF4J/Logback, propagating correlation IDs throughout the entire processing pipeline (MQ -> AI Triage -> DB -> REST).
   * Ensure standard build execution succeeds (`mvn clean test`).
