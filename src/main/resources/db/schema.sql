DROP TABLE IF EXISTS customer_enquiry_triage_tracker;
GO

CREATE TABLE customer_enquiry_triage_tracker
(
    referenceNumber   NVARCHAR(36)  NOT NULL PRIMARY KEY,
    channel           NVARCHAR(16)  NOT NULL,
    reqIssuedAt       DATETIME2(3)  NOT NULL,
    processingStatus  NVARCHAR(16)  NOT NULL,
    lastErrorMssg     NVARCHAR(128) NULL,
    processingCount   INT           NOT NULL DEFAULT 0,
    totalTokens       INT           NULL,
    genAiId           NVARCHAR(35)  NULL,
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
GO