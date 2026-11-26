-- WP-08B.2. Human-verified editorial evidence ledger and candidate-side evidence binding.
--
-- The ledger is append-only: a verification row is never edited. Revocation writes revokedAt/revokedBy
-- on the same row, and the STORED generated activeKey (NULL once revoked) keeps at most one *active*
-- verification per (materialRevisionId, attribute). MySQL allows many NULL keys in a unique index,
-- so the full revocation history is retained.
--
-- Values recorded here are the only way NewsEditorialEvidence can reach a verified attribute
-- (peer review, integrity, study design, licensed results scope). Model output can never write them.

CREATE TABLE IF NOT EXISTS t_news_evidence_verification (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    materialRevisionId BIGINT       NOT NULL,
    attribute          VARCHAR(32)  CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    value              VARCHAR(64)  CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    method             VARCHAR(32)  CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    evidenceUrl        VARCHAR(512) NOT NULL,
    sourceVersion      VARCHAR(64)  CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    evidenceNote       VARCHAR(1024) NULL,
    verifiedBy         VARCHAR(128) NOT NULL,
    verifiedAt         DATETIME(6)  NOT NULL,
    revokedBy          VARCHAR(128) NULL,
    revokedAt          DATETIME(6)  NULL,
    revokeNote         VARCHAR(512) NULL,
    activeKey          VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin
                       GENERATED ALWAYS AS (IF(revokedAt IS NULL, CONCAT(materialRevisionId, ':', attribute), NULL)) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_news_evidence_active (activeKey),
    INDEX idx_news_evidence_revision (materialRevisionId, attribute, verifiedAt)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'WP-08B.2 append-only human evidence verification ledger (one active row per attribute)';

ALTER TABLE t_news_curation_candidate
    ADD COLUMN evidenceVerificationJson   JSON        NULL,
    ADD COLUMN evidenceVerificationSha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN materializedAt             DATETIME(6) NULL,
    ADD COLUMN materializedBy             VARCHAR(128) NULL,
    ADD COLUMN contentType                VARCHAR(32) NULL
        COMMENT 'WP-08B.2 冻结结果的编辑体裁（RESEARCH_INSIGHT / RESEARCH_RADAR / TRIAL_WATCH），人工编辑分类仍写 category',
    ADD INDEX idx_news_curation_call (callId),
    ALGORITHM = INPLACE, LOCK = NONE;
