CREATE TABLE customer_enquiry_triage (
    enquiry_id NVARCHAR(100) NOT NULL PRIMARY KEY,
    correlation_id NVARCHAR(100) NOT NULL,
    customer_id NVARCHAR(100) NOT NULL,
    message_text NVARCHAR(MAX) NOT NULL,
    received_at DATETIME2 NOT NULL,
    intent NVARCHAR(100) NOT NULL,
    urgency_score INT NOT NULL,
    sentiment NVARCHAR(50) NOT NULL,
    recommended_team NVARCHAR(100) NOT NULL,
    rationale NVARCHAR(1000) NULL,
    classified_at DATETIME2 NOT NULL
);
