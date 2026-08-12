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