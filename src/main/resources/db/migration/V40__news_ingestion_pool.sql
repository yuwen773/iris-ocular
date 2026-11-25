-- WP-02: Iris News Engine scheduling, lease fencing, idempotent receipts, and material pool.
-- Additive to master V39.
-- Naming convention: camelCase columns (mybatis-plus map-underscore-to-camel-case: false),
-- UTC DATETIME(6) timestamps, and ascii_bin collations for case-exact wire identifiers and hashes.

CREATE TABLE IF NOT EXISTS t_news_source (
    source      VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    enabled     TINYINT     NOT NULL DEFAULT 1 COMMENT '1-enabled 0-disabled',
    createdAt   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updatedAt   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'Registered news collection sources and scheduling mutex rows';

INSERT IGNORE INTO t_news_source (source, enabled)
VALUES ('pubmed', 1),
       ('europe-pmc', 1),
       ('clinicaltrials-gov', 1);

CREATE TABLE IF NOT EXISTS t_news_source_cursor (
    source              VARCHAR(32)   CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    sourceConfigVersion BIGINT        NOT NULL,
    queryText           VARCHAR(1024) NOT NULL,
    committedCursor     DATE          NULL,
    createdAt           DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updatedAt           DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (source, sourceConfigVersion)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'Committed collection cursor per source and configuration version';

CREATE TABLE IF NOT EXISTS t_news_collection_task (
    taskId               VARCHAR(128)  CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    source               VARCHAR(32)   CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    sourceConfigVersion  BIGINT        NOT NULL,
    queryText            VARCHAR(1024) NOT NULL,
    windowStartOn        DATE          NOT NULL,
    windowEndBefore      DATE          NOT NULL,
    status               VARCHAR(24)   CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PENDING',
    generation           BIGINT        NOT NULL DEFAULT 0,
    leaseTokenHash       CHAR(64)      CHARACTER SET ascii COLLATE ascii_bin NULL,
    leaseWorkerId        VARCHAR(128)  CHARACTER SET ascii COLLATE ascii_bin NULL,
    leaseExpiresAt       DATETIME(6)   NULL,
    attemptCount         INT           NOT NULL DEFAULT 0,
    maxAttempts          INT           NOT NULL DEFAULT 3,
    nextAttemptAt        DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    lastFailureCode      VARCHAR(64)   CHARACTER SET ascii COLLATE ascii_bin NULL,
    committedBatchCount  INT           NULL,
    committedRecordCount INT           NULL,
    committedCursor      DATE          NULL,
    completedAt          DATETIME(6)   NULL,
    createdAt            DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updatedAt            DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    INDEX idx_news_task_claim (source, status, nextAttemptAt, windowStartOn, windowEndBefore),
    INDEX idx_news_task_config_window (source, sourceConfigVersion, windowStartOn, windowEndBefore, status),
    INDEX idx_news_task_lease_expiry (status, leaseExpiresAt)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'Windowed news collection tasks with generation fencing and lease state';

CREATE TABLE IF NOT EXISTS t_news_collection_lease (
    taskId         VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    generation     BIGINT       NOT NULL,
    workerId       VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    leaseTokenHash CHAR(64)     CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    leaseExpiresAt DATETIME(6)  NOT NULL,
    claimedAt      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (taskId, generation),
    INDEX idx_news_lease_worker (workerId, claimedAt)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'Historical lease ownership per task generation for post-expiry/replay verification';

CREATE TABLE IF NOT EXISTS t_news_collection_batch (
    taskId      VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    generation  BIGINT       NOT NULL,
    sequence    INT          NOT NULL,
    recordCount INT          NOT NULL,
    createdAt   DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (taskId, generation, sequence)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'Acknowledged batch headers for a leased collection attempt';

CREATE TABLE IF NOT EXISTS t_news_batch_item (
    taskId         VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    generation     BIGINT       NOT NULL,
    sequence       INT          NOT NULL,
    itemIndex      INT          NOT NULL,
    source         VARCHAR(32)  CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    sourceRecordId VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    contentDigest  CHAR(64)     CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    canonicalJson  MEDIUMTEXT   NOT NULL,
    createdAt      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (taskId, generation, sequence, itemIndex),
    INDEX idx_news_batch_item_task_gen (taskId, generation)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'Staged batch records isolated from editorial material tables until atomic complete';

CREATE TABLE IF NOT EXISTS t_news_worker_receipt (
    taskId            VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    generation        BIGINT       NOT NULL,
    action            VARCHAR(16)  CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    idempotencyKey    VARCHAR(40)  CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    workerId          VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    requestBodySha256 CHAR(64)     CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    responseJson      VARCHAR(512) NOT NULL,
    createdAt         DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (taskId, generation, action, idempotencyKey)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'Idempotency receipts binding worker identity, task, generation, action, and request hash';

CREATE TABLE IF NOT EXISTS t_news_material (
    id                  BIGINT       PRIMARY KEY AUTO_INCREMENT,
    source              VARCHAR(32)  CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    sourceRecordId      VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    latestRevisionId    BIGINT       NULL,
    latestContentDigest CHAR(64)     CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    firstSeenAt         DATETIME(6)  NOT NULL,
    lastSeenAt          DATETIME(6)  NOT NULL,
    createdAt           DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updatedAt           DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_news_material_source_record (source, sourceRecordId)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'Editorial raw material identity across source revisions';

CREATE TABLE IF NOT EXISTS t_news_material_revision (
    id              BIGINT        PRIMARY KEY AUTO_INCREMENT,
    materialId      BIGINT        NOT NULL,
    source          VARCHAR(32)   CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    sourceRecordId  VARCHAR(128)  CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    contentDigest   CHAR(64)      CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    url             VARCHAR(512)  NOT NULL,
    title           VARCHAR(2048) NOT NULL,
    language        VARCHAR(80)   NULL,
    publishedOn     VARCHAR(10)   CHARACTER SET ascii COLLATE ascii_bin NULL,
    updatedOn       DATE          NULL,
    pmid            VARCHAR(32)   CHARACTER SET ascii COLLATE ascii_bin NULL,
    doi             VARCHAR(512)  NULL,
    abstractText    MEDIUMTEXT    NULL,
    abstractLicense VARCHAR(16)   CHARACTER SET ascii COLLATE ascii_bin NULL,
    trialJson       JSON          NULL,
    canonicalJson   MEDIUMTEXT    NOT NULL,
    firstTaskId     VARCHAR(128)  CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    firstGeneration BIGINT        NOT NULL,
    visibleAt       DATETIME(6)   NOT NULL,
    createdAt       DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_news_revision_identity (source, sourceRecordId, contentDigest),
    INDEX idx_news_revision_material (materialId, visibleAt),
    INDEX idx_news_revision_visible (source, visibleAt),
    INDEX idx_news_revision_pmid (pmid),
    INDEX idx_news_revision_doi (doi(191))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'Immutable content revisions promoted upon atomic window completion';
