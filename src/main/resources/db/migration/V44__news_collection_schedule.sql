-- WP-08A: persistent scheduler progress and explicit automatic/manual task identity.
ALTER TABLE t_news_collection_task
    ADD COLUMN taskType VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'MANUAL',
    ADD INDEX idx_news_task_schedule_success (source, taskType, completedAt),
    ALGORITHM = INPLACE, LOCK = NONE;

CREATE TABLE t_news_collection_schedule (
    source               VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    sourceConfigVersion  BIGINT NULL,
    baselineCursor       DATE NULL,
    evaluatedVersion     BIGINT NOT NULL,
    lastRunAt            DATETIME(6) NOT NULL,
    nextRunAt            DATETIME(6) NOT NULL,
    status               VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    relatedTaskId        VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'Automatic news collection ownership and last scheduling decision';

CREATE TABLE t_news_worker_poll (
    workerId     VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    lastPollAt   DATETIME(6) NOT NULL,
    lastClaimAt  DATETIME(6) NULL,
    lastTaskId   VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
    INDEX idx_news_worker_poll_at (lastPollAt)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'Latest authenticated claim poll and successful claim per worker';
