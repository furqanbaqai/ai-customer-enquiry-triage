# AI Customer Enquiry Triage

A lightweight, Spring-free Java 21 service that:

1. consumes customer enquiries from IBM MQ;
2. calls an AI service to classify intent, urgency, sentiment, and destination team;
3. stores the enquiry and classification in Microsoft SQL Server; and
4. sends urgent enquiries to a downstream routing REST API.

Message processing, HTTP calls, and blocking database work are dispatched using Java virtual threads.

## Prerequisites

- JDK 21 or newer
- Apache Maven 3.9 or newer
- An IBM MQ queue manager, server-connection channel, and request queue
- Microsoft SQL Server and credentials permitted to read/write the triage table
- An AI classification HTTP endpoint
- A downstream routing HTTP endpoint

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
| `MQ_USER` | No | empty | MQ application user |
| `MQ_PASSWORD` | No | empty | MQ application password |
| `DB_URL` | Yes | — | SQL Server JDBC URL |
| `DB_USER` | Yes | — | Database user |
| `DB_PASSWORD` | Yes | — | Database password |
| `DB_POOL_SIZE` | No | `10` | Maximum HikariCP pool size |
| `AI_API_URL` | Yes | — | AI classification endpoint |
| `AI_API_KEY` | Yes | — | Bearer token for the AI endpoint |
| `AI_TIMEOUT_SECONDS` | No | `15` | AI request timeout |
| `ROUTING_API_URL` | Yes | — | Urgent-case routing endpoint |
| `ROUTING_API_KEY` | No | empty | Bearer token for the routing endpoint |
| `ROUTING_TIMEOUT_SECONDS` | No | `10` | Routing request timeout |
| `URGENCY_THRESHOLD` | No | `8` | Minimum urgency score that triggers routing |

For production, inject secrets through the deployment platform. For example, a temporary PowerShell session can be configured with:

```powershell
$env:MQ_HOST = 'mq.internal.example'
$env:MQ_CHANNEL = 'TRIAGE.SVRCONN'
$env:MQ_QUEUE_MANAGER = 'QM_PROD'
$env:DB_URL = 'jdbc:sqlserver://sql.internal.example:1433;databaseName=triage;encrypt=true'
$env:DB_USER = 'triage_app'
$env:DB_PASSWORD = '<secret>'
$env:AI_API_URL = 'https://ai.internal.example/v1/classify'
$env:AI_API_KEY = '<secret>'
$env:ROUTING_API_URL = 'https://routing.internal.example/v1/escalations'
```

## Database setup

Run [src/main/resources/db/schema.sql](src/main/resources/db/schema.sql) against the database named in `DB_URL`. For example, with Microsoft `sqlcmd`:

```shell
sqlcmd -S localhost,1433 -d triage -U triage_app -P "<password>" -i src/main/resources/db/schema.sql
```

The application does not automatically apply schema migrations. The script must be applied before the first message is processed.

## IBM MQ setup

The queue configured by `MQ_QUEUE_NAME` must exist before startup. Its default name is:

```text
AI.CUST.ENQ.TRIAGE.REQUEST.Q
```

The configured MQ user needs permission to connect to the queue manager and consume from this queue. The application uses IBM MQ client transport rather than bindings mode.

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

Send a Jakarta JMS `TextMessage` containing JSON shaped like:

```json
{
  "enquiryId": "ENQ-2026-0001",
  "customerId": "CUST-10042",
  "message": "My card is missing and I can see an unknown transaction.",
  "receivedAt": "2026-08-08T12:00:00Z"
}
```

`enquiryId`, `customerId`, and `message` are required. `receivedAt` may be omitted, in which case the application uses the current time.

Set `JMSCorrelationID` on the message when possible. If it is absent, the service uses `JMSMessageID`; if both are absent, it generates a UUID. The ID is forwarded as `X-Correlation-ID` to both HTTP services and stored in SQL Server.

### AI service response

The endpoint configured by `AI_API_URL` must return a successful `2xx` response with JSON shaped like:

```json
{
  "intent": "CARD_FRAUD",
  "urgencyScore": 9,
  "sentiment": "NEGATIVE",
  "recommendedTeam": "Fraud Operations",
  "rationale": "Missing card and an unrecognized transaction require immediate review.",
  "classifiedAt": "2026-08-08T12:00:01Z"
}
```

`urgencyScore` must be between `0` and `10`. `classifiedAt` may be omitted and will default to the current time. The request uses `Authorization: Bearer <AI_API_KEY>` and `X-Correlation-ID` headers.

When the score is greater than or equal to `URGENCY_THRESHOLD`, the persisted result is also posted asynchronously to `ROUTING_API_URL`.

## Troubleshooting

- **Missing required configuration:** the startup exception names the missing variable. Define it in the environment or local properties file.
- **MQ initialization failure:** check the host, port, channel, queue manager, queue existence, credentials, and MQ authority records.
- **SQL Server connection failure:** verify the JDBC URL, TLS options, database name, credentials, and network access.
- **AI failures:** non-`2xx` responses and invalid JSON are treated as failures and surfaced immediately without application-level retries.
- **No downstream notification:** confirm the returned urgency score meets `URGENCY_THRESHOLD` and inspect the routing endpoint response.
- **Maven PKIX error:** update the JDK trust store or configure Maven to use the organization-approved certificate store; do not disable TLS verification in production.

Logs are written to standard output using the format configured in `src/main/resources/logback.xml` and include the correlation ID where processing context is available.
