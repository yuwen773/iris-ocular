-- WP-03: Iris News Engine curation candidates, evidence snapshots, paid model call receipts, budgets, and NewsBench.
-- Additive to the V40 material pool.
-- Naming convention: camelCase columns, UTC DATETIME(6) timestamps, ascii_bin on case-exact wire IDs/hashes/codes.

CREATE TABLE IF NOT EXISTS t_news_curation_budget (
    budgetDate         DATE        NOT NULL,
    scope              VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    maxCalls           INT         NOT NULL,
    usedCalls          INT         NOT NULL DEFAULT 0,
    reservedCalls      INT         NOT NULL DEFAULT 0,
    maxTokens          BIGINT      NOT NULL,
    usedTokens         BIGINT      NOT NULL DEFAULT 0,
    reservedTokens     BIGINT      NOT NULL DEFAULT 0,
    maxCostMicros      BIGINT      NOT NULL,
    usedCostMicros     BIGINT      NOT NULL DEFAULT 0,
    reservedCostMicros BIGINT      NOT NULL DEFAULT 0,
    circuitBroken      TINYINT     NOT NULL DEFAULT 0,
    circuitReason      VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    createdAt          DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updatedAt          DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (budgetDate, scope)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'Daily curation call/token/cost budget and circuit breaker state';

CREATE TABLE IF NOT EXISTS t_news_model_call_receipt (
    callId             VARCHAR(64)  CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    idempotencyKey     VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    materialRevisionId BIGINT       NOT NULL,
    policyVersion      VARCHAR(64)  CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attemptNo          INT          NOT NULL,
    budgetDate         DATE         NOT NULL,
    budgetScope        VARCHAR(64)  CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    provider           VARCHAR(64)  CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    modelName          VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    promptSha256       CHAR(64)     CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    state              VARCHAR(32)  CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reservedTokens     BIGINT       NOT NULL,
    reservedCostMicros BIGINT       NOT NULL,
    promptTokens       INT          NULL,
    completionTokens   INT          NULL,
    actualCostMicros   BIGINT       NULL,
    httpStatus         INT          NULL,
    providerRequestId  VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
    responseSha256     CHAR(64)     CHARACTER SET ascii COLLATE ascii_bin NULL,
    errorCode          VARCHAR(64)  CHARACTER SET ascii COLLATE ascii_bin NULL,
    latencyMs          INT          NULL,
    reconciledBy       VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
    reconcileNote      VARCHAR(512) NULL,
    reconciledAt       DATETIME(6)  NULL,
    reservedAt         DATETIME(6)  NOT NULL,
    dispatchedAt       DATETIME(6)  NULL,
    deadlineAt         DATETIME(6)  NOT NULL,
    completedAt        DATETIME(6)  NULL,
    createdAt          DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updatedAt          DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_news_model_call_idem (idempotencyKey),
    INDEX idx_news_model_call_rev_policy (materialRevisionId, policyVersion, attemptNo),
    INDEX idx_news_model_call_state_deadline (state, deadlineAt)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'Reserve-before-IO paid model call ledger and unknown-outcome reconciliation state';

CREATE TABLE IF NOT EXISTS t_news_curation_candidate (
    id                     BIGINT        PRIMARY KEY AUTO_INCREMENT,
    materialId             BIGINT        NOT NULL,
    materialRevisionId     BIGINT        NOT NULL,
    source                 VARCHAR(32)   CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    sourceRecordId         VARCHAR(128)  CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    contentDigest          CHAR(64)      CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    policyVersion          VARCHAR(64)   CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    callId                 VARCHAR(64)   CHARACTER SET ascii COLLATE ascii_bin NULL,
    status                 VARCHAR(32)   CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    decision               VARCHAR(24)   CHARACTER SET ascii COLLATE ascii_bin NULL,
    evidenceTier           VARCHAR(32)   CHARACTER SET ascii COLLATE ascii_bin NULL,
    relevanceScore         INT           NULL,
    suggestedTitle         VARCHAR(512)  NULL,
    suggestedSummary       VARCHAR(2048) NULL,
    category               VARCHAR(64)   NULL,
    limitationsJson        JSON          NULL,
    claimsJson             JSON          NULL,
    violationsJson         JSON          NULL,
    evidenceSnapshotJson   MEDIUMTEXT    NOT NULL,
    evidenceSnapshotSha256 CHAR(64)      CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    createdAt              DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updatedAt              DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_news_curation_rev_policy (materialRevisionId, policyVersion),
    INDEX idx_news_curation_status (status, createdAt),
    INDEX idx_news_curation_source_record (source, sourceRecordId)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'WP-03 curated news candidates bound to immutable material revisions and evidence snapshots';

CREATE TABLE IF NOT EXISTS t_news_bench_run (
    runId                VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    datasetVersion       VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    datasetSha256        CHAR(64)    CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    policyVersion        VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    mode                 VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    totalCases           INT         NOT NULL,
    passedCases          INT         NOT NULL,
    precisionBps         INT         NOT NULL,
    recallBps            INT         NOT NULL,
    evidenceCatchBps     INT         NOT NULL,
    licenseComplianceBps INT         NOT NULL,
    injectionCatchBps    INT         NOT NULL,
    totalCalls           INT         NOT NULL,
    totalTokens          BIGINT      NOT NULL,
    totalCostMicros      BIGINT      NOT NULL,
    gatePassed           TINYINT     NOT NULL,
    reportJson           MEDIUMTEXT  NOT NULL,
    createdAt            DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'NewsBench deterministic offline and authorized live evaluation reports';
