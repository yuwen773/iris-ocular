-- WP-04: Editorial review & publish gate, immutable content revisions bound to frozen evidence snapshots,
-- and durable news-list cache epoch invalidation.
-- Additive to the V41 curation schema.

ALTER TABLE t_news
    ADD COLUMN curationCandidateId BIGINT NULL COMMENT 'WP-04 绑定的精选候选 id（人工创建可空）',
    ADD COLUMN materialRevisionId BIGINT NULL COMMENT 'WP-04 绑定的可见原始资料修订 id',
    ADD COLUMN evidenceSnapshotSha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT 'WP-04 冻结原始证据快照 SHA-256',
    ADD COLUMN currentRevisionNo INT NOT NULL DEFAULT 0 COMMENT '当前内容修订序号',
    ADD COLUMN publishedRevisionNo INT NULL COMMENT '当前已发布的内容修订序号',
    ADD INDEX idx_news_curation_candidate (curationCandidateId),
    ADD INDEX idx_news_material_revision (materialRevisionId),
    ALGORITHM=INPLACE, LOCK=NONE;

CREATE TABLE IF NOT EXISTS t_news_revision (
    id                     BIGINT        PRIMARY KEY AUTO_INCREMENT,
    newsId                 BIGINT        NOT NULL,
    revisionNo             INT           NOT NULL,
    action                 VARCHAR(32)   CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status                 TINYINT       NOT NULL,
    title                  VARCHAR(255)  NOT NULL,
    summary                VARCHAR(512)  NULL,
    content                MEDIUMTEXT    NOT NULL,
    coverImg               VARCHAR(512)  NOT NULL,
    category               VARCHAR(32)   NULL,
    source                 VARCHAR(255)  NULL,
    featured               TINYINT       NOT NULL DEFAULT 0,
    curationCandidateId    BIGINT        NULL,
    materialRevisionId     BIGINT        NULL,
    evidenceSnapshotSha256 CHAR(64)      CHARACTER SET ascii COLLATE ascii_bin NULL,
    evidenceSnapshotJson   MEDIUMTEXT    NULL,
    contentSha256          CHAR(64)      CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actorId                BIGINT        NULL,
    actorRole              VARCHAR(24)   NULL,
    reviewNote             VARCHAR(512)  NULL,
    createdAt              DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_news_revision (newsId, revisionNo),
    INDEX idx_news_revision_candidate (curationCandidateId),
    INDEX idx_news_revision_material (materialRevisionId)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'Immutable editorial content revisions bound to frozen evidence snapshots';

CREATE TABLE IF NOT EXISTS t_news_cache_epoch (
    scope     VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    epoch     BIGINT      NOT NULL DEFAULT 0,
    updatedAt DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'Durable cache epoch monotonic counter for post-commit invalidation and stale-cache bypass';

INSERT IGNORE INTO t_news_cache_epoch (scope, epoch)
VALUES ('news_list', 0);
