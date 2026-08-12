# AI Customer Enquiry Triage

A lightweight, Spring-free Java 21 service that:

1. consumes customer enquiries from IBM MQ;
2. calls an AI service to classify each enquiry; and
3. publishes the AI response to an IBM MQ result queue.

Message processing, HTTP calls, and result publication are dispatched using Java virtual threads.

## Prerequisites

- JDK 21 or newer
- Apache Maven 3.9 or newer
- An IBM MQ queue manager, server-connection channel, and request queue
- Microsoft SQL Server and credentials permitted to read/write the triage table
- An AI classification HTTP endpoint

Verify the local toolchain:

```shell
java -version
mvn -version
```

## Configuration

Production configuration is read from environment variables. For local development, the service falls back to `application-dev.properties` in the current working directory. Environment variables always override values in the file.

Create a local configuration file from the committed template:

```powershell
Copy-Item application-dev.properties.example application-dev.properties
```

```bash
cp application-dev.properties.example application-dev.properties
```

Edit the copied file with values for your local services. It is excluded from Git and must never be committed.

To use a properties file at another location, set `APP_CONFIG_FILE`:

```powershell
$env:APP_CONFIG_FILE = 'C:\secure\triage.properties'
```

```bash
export APP_CONFIG_FILE=/secure/triage.properties
```

### Configuration reference

| Variable | Required | Default | Description |
| --- | --- | --- | --- |
| `MQ_HOST` | Yes | — | IBM MQ host name |
| `MQ_PORT` | No | `1414` | IBM MQ listener port |
| `MQ_CHANNEL` | Yes | — | Server-connection channel |
| `MQ_QUEUE_MANAGER` | Yes | — | Queue manager name |
| `MQ_QUEUE_NAME` | No | `AI.CUST.ENQ.TRIAGE.REQUEST.Q` | Request queue name |
| `MQ_BACKOUT_QUEUE_NAME` | No | `AI.CUST.ENQ.TRIAGE.BACKOUT.Q` | Queue for malformed or schema-invalid requests |
| `MQ_RESULT_QUEUE_NAME` | No | `AI.CUST.ENQ.TRIAGE.RESULT.Q` | Queue receiving successful AI responses |
| `MQ_USER` | No | empty | MQ application user |
| `MQ_PASSWORD` | No | empty | MQ application password |
| `DB_URL` | Yes | — | SQL Server JDBC URL |
| `DB_USER` | Yes | — | Database user |
| `DB_PASSWORD` | Yes | — | Database password |
| `DB_POOL_SIZE` | No | `10` | Maximum HikariCP pool size |
| `AI_API_URL` | Yes | — | AI classification endpoint |
| `AI_API_KEY` | Yes | — | Bearer token for the AI endpoint |
| `AI_TIMEOUT_SECONDS` | No | `15` | AI request timeout |

For production, inject secrets through the deployment platform. For example, a temporary PowerShell session can be configured with:

```powershell
$env:MQ_HOST = 'mq.internal.example'
$env:MQ_CHANNEL = 'TRIAGE.SVRCONN'
$env:MQ_QUEUE_MANAGER = 'QM_PROD'
$env:MQ_QUEUE_NAME = 'AI.CUST.ENQ.TRIAGE.REQUEST.Q'
$env:MQ_BACKOUT_QUEUE_NAME = 'AI.CUST.ENQ.TRIAGE.BACKOUT.Q'
$env:MQ_RESULT_QUEUE_NAME = 'AI.CUST.ENQ.TRIAGE.RESULT.Q'
$env:DB_URL = 'jdbc:sqlserver://sql.internal.example:1433;databaseName=triage;encrypt=true'
$env:DB_USER = 'triage_app'
$env:DB_PASSWORD = '<secret>'
$env:AI_API_URL = 'https://ai.internal.example/v1/classify'
$env:AI_API_KEY = '<secret>'
```

## Database setup

Run [src/main/resources/db/schema.sql](src/main/resources/db/schema.sql) against the database named in `DB_URL`. For example, with Microsoft `sqlcmd`:

```shell
sqlcmd -S localhost,1433 -d triage -U triage_app -P "<password>" -i src/main/resources/db/schema.sql
```

The application does not automatically apply schema migrations. The script must be applied before the first message is processed.

### Tracker lifecycle

Each valid request is atomically registered in `customer_enquiry_triage_tracker` before AI processing:

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

`referenceNumber` is the request's `meta.refNumber`; `processingCount` starts at one and is incremented
when the same reference is received again. AI completion records `totalTokens`, `genAiId`, serialized
`timingJson`, and the complete valid raw response in `aiResponseJson`. Every duplicate or state change
refreshes `recUpdatedAt` using SQL Server UTC time.

## IBM MQ setup

The queues configured by `MQ_QUEUE_NAME`, `MQ_BACKOUT_QUEUE_NAME`, and `MQ_RESULT_QUEUE_NAME` must exist before startup. Their default names are:

```text
AI.CUST.ENQ.TRIAGE.REQUEST.Q
AI.CUST.ENQ.TRIAGE.BACKOUT.Q
AI.CUST.ENQ.TRIAGE.RESULT.Q
```

The configured MQ user needs permission to consume from the request queue and put messages on the backout and result queues. The application uses IBM MQ client transport rather than bindings mode.

## Build and test

Run the standard clean build from the repository root:

```shell
mvn clean test
```

Create the self-contained executable JAR:

```shell
mvn package
```

## Run the service

The package phase includes all runtime dependencies in the application JAR. Run the same command on Windows, Linux, or macOS:

```bash
java -jar target/ai-customer-enquiry-triage-1.0.0-SNAPSHOT.jar
```

A successful startup logs both `IBM MQ consumer started` and `AI customer enquiry triage service is ready`. Stop the process with `Ctrl+C`; the shutdown hook closes MQ, the database pool, and the virtual-thread executor.

## Message contracts

### Incoming IBM MQ message

Send a Jakarta JMS `TextMessage` or UTF-8 `BytesMessage` whose body is a JSON object matching
[`equiry-request-schema-v1.0.json`](src/main/resources/com/sib/triage/ai/schema/equiry-request-schema-v1.0.json):

```json
{
  "meta": {
    "refNumber": "ENQ-2026-0001",
    "channel": "WebSite",
    "reqIssuedAt": "2026-08-08T11:59:30Z"
  },
  "customerId": "ABCDEF123456",
  "mobileNumber": "+971 50 123 4567",
  "firstName": "Sara",
  "lastName": "Khan",
  "emailAddress": "sara.khan@example.com",
  "message": "My card is missing and I can see an unknown transaction.",
  "receivedAt": "2026-08-08T12:00:00Z"
}
```

The required fields are `meta`, `mobileNumber`, `firstName`, `lastName`, `emailAddress`, `message`, and `receivedAt`. Within `meta`, `refNumber`, `channel`, and `reqIssuedAt` are required. `customerId` is optional; when supplied, it must contain exactly 12 alphanumeric characters.

The following schema constraints are enforced before AI classification:

- `meta.refNumber` must contain 1–36 characters.
- `meta.channel` must be one of `WebSite`, `Mobile App`, `Web App`, `ATM`, `MFK`, `IVR`, or `Other`.
- `meta.reqIssuedAt` and `receivedAt` must be ISO 8601 date-time values.
- `mobileNumber` must match the international-number pattern defined by the schema.
- `firstName` and `lastName` may contain at most 35 characters each.
- `emailAddress` must be a valid email address.
- `message` must contain 1–512 characters.
- Additional properties are rejected at both the root and `meta` levels.

`meta.refNumber` is used as the enquiry ID stored in SQL Server. All date-time values should include a UTC offset, for example `2026-08-08T12:00:00Z`.

Set `JMSCorrelationID` on the message when possible. If it is absent, the service uses `JMSMessageID`; if both are absent, it generates a UUID. The ID is forwarded to the AI API as `X-Correlation-ID` and stored in SQL Server.

If the body is malformed JSON, cannot be deserialized, fails schema validation, or cannot be persisted,
the original body is sent once to `MQ_BACKOUT_QUEUE_NAME` with the same correlation ID. AI processing is
not started after initial persistence failure. AI endpoint failures are recorded as `FAILURE` and do not
produce a result message.

### AI service response

The endpoint configured by `AI_API_URL` must return a successful OpenAI-compatible chat-completion response. The classification is JSON encoded in `choices[0].message.content`:

```json
{
  "choices": [
    {
      "finish_reason": "stop",
      "index": 0,
      "message": {
        "role": "assistant",
        "content": "{\"category\":\"Card Fraud/Errors\",\"subcategory\":\"Duplicate Charges\"}"
      }
    }
  ],
  "object": "chat.completion"
}
```

`choices[0].message.content` must be a non-blank string. Its contents are treated as an opaque response and are not parsed or transformed by the service. The AI request uses `Authorization: Bearer <AI_API_KEY>` and `X-Correlation-ID` headers.

### Outgoing IBM MQ result message

After a successful AI call, the service sends a JSON `TriageResult` to the queue configured by `MQ_RESULT_QUEUE_NAME`. The result contains the unchanged `choices[0].message.content` value and the original customer enquiry. It is sent as a JMS text message and uses the same `JMSCorrelationID` selected for the incoming request.

For the AI response above, the result queue receives:

```json
{
  "content": "{\"category\":\"Card Fraud/Errors\",\"subcategory\":\"Duplicate Charges\"}",
  "customerEnquiry": {
    "meta": {
      "refNumber": "ENQ-2026-0001",
      "channel": "WebSite",
      "reqIssuedAt": "2026-08-08T11:59:30Z"
    },
    "customerId": "ABCDEF123456",
    "mobileNumber": "+971 50 123 4567",
    "firstName": "Sara",
    "lastName": "Khan",
    "emailAddress": "sara.khan@example.com",
    "message": "My card is missing and I can see an unknown transaction.",
    "receivedAt": "2026-08-08T12:00:00Z"
  }
}
```

Schema-invalid requests and failed AI calls do not produce a result message. A failure while putting the response on the result queue fails that enquiry's processing and is logged.

## Troubleshooting

- **Missing required configuration:** the startup exception names the missing variable. Define it in the environment or local properties file.
- **MQ initialization failure:** check the host, port, channel, queue manager, queue existence, credentials, and MQ authority records.
- **SQL Server connection failure:** verify the JDBC URL, TLS options, database name, credentials, and network access.
- **AI failures:** non-`2xx` responses and invalid JSON are treated as failures and surfaced immediately without application-level retries.
- **Maven PKIX error:** update the JDK trust store or configure Maven to use the organization-approved certificate store; do not disable TLS verification in production.

Logs are written to standard output using the format configured in `src/main/resources/logback.xml` and include the correlation ID where processing context is available.

# Appendix A: SQL Statements for the database

```sql
/* ============================================================
   DROP EXISTING TABLES
   Child table must be dropped first because of the FK.
   ============================================================ */

DROP TABLE IF EXISTS customer_enquiry_triage_history;
DROP TABLE IF EXISTS customer_enquiry_triage_tracker;


/* ============================================================
   PARENT TABLE
   customer_enquiry_triage_tracker
   ============================================================ */

CREATE TABLE customer_enquiry_triage_tracker
(
    referenceNumber   NVARCHAR(36)  NOT NULL,
    channel           NVARCHAR(16)  NOT NULL,
    reqIssuedAt       DATETIME2(3)  NOT NULL,
    processingStatus  NVARCHAR(16)  NOT NULL,
    lastErrorMssg     NVARCHAR(128) NULL,
    processingCount   INT           NOT NULL DEFAULT 0,
    requestJSON       NVARCHAR(MAX) NOT NULL,
    recCreatedAt      DATETIME2(3)  NOT NULL DEFAULT SYSUTCDATETIME(),
    recUpdatedAt      DATETIME2(3)  NOT NULL DEFAULT SYSUTCDATETIME(),

    CONSTRAINT PK_customer_enquiry_triage_tracker
        PRIMARY KEY (referenceNumber),

    CONSTRAINT CK_customer_enquiry_triage_tracker_requestJSON
        CHECK
        (
            ISJSON(requestJSON) = 1
        )
);


/* ============================================================
   CHILD / HISTORY TABLE
   customer_enquiry_triage_history

   One parent enquiry can have multiple processing / AI history
   records.

   historyId is generated automatically by SQL Server.
   ============================================================ */

CREATE TABLE customer_enquiry_triage_history
(
    historyId         UNIQUEIDENTIFIER NOT NULL
                      DEFAULT NEWSEQUENTIALID(),

    referenceNumber   NVARCHAR(36)     NOT NULL,
    totalTokens       INT              NULL,
    genAiId           NVARCHAR(56)     NULL,
    timingJson        NVARCHAR(MAX)    NULL,
    aiResponseJson    NVARCHAR(MAX)    NULL,
    recCreatedAt      DATETIME2(3)     NOT NULL DEFAULT SYSUTCDATETIME(),
    recUpdatedAt      DATETIME2(3)     NOT NULL DEFAULT SYSUTCDATETIME(),

    CONSTRAINT PK_customer_enquiry_triage_history
        PRIMARY KEY (historyId),

    CONSTRAINT FK_customer_enquiry_triage_history_tracker
        FOREIGN KEY (referenceNumber)
        REFERENCES customer_enquiry_triage_tracker(referenceNumber),

    CONSTRAINT CK_customer_enquiry_triage_history_timingJson
        CHECK
        (
            timingJson IS NULL
            OR ISJSON(timingJson) = 1
        ),

    CONSTRAINT CK_customer_enquiry_triage_history_aiResponseJson
        CHECK
        (
            aiResponseJson IS NULL
            OR ISJSON(aiResponseJson) = 1
        )
);


/* ============================================================
   INDEXES
   Improves history lookup by referenceNumber.
   ============================================================ */

CREATE INDEX IX_customer_enquiry_triage_history_referenceNumber
    ON customer_enquiry_triage_history(referenceNumber);
```