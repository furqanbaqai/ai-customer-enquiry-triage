# AI Customer Enquiry Triage

A lightweight, Spring-free Java 21 service that consumes enquiries from IBM MQ, classifies them through an AI API, stores the result in SQL Server, and asynchronously notifies a routing API for urgent cases.

## Run

1. Install JDK 21+ and Maven 3.9+.
2. Copy `application-dev.properties.example` to `application-dev.properties` for local development, or set the corresponding environment variables in production.
3. Create the database table using `src/main/resources/db/schema.sql`.
4. Run `mvn clean test`, then `mvn package` and `java -jar target/ai-customer-enquiry-triage-1.0.0-SNAPSHOT.jar`.

Environment variables always take precedence. Local property files are intentionally ignored by Git.
