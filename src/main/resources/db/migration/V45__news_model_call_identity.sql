-- WP-08B. Existing receipts and immutable candidate evidence retain their original identity.
ALTER TABLE t_news_model_call_receipt
    ADD COLUMN logicalCallSha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN callIdentityJson JSON NULL,
    ADD COLUMN frozenResponseJson MEDIUMTEXT NULL,
    ADD COLUMN cachedPromptTokens INT NULL,
    ADD UNIQUE KEY uk_news_model_logical_attempt (logicalCallSha256, attemptNo),
    ALGORITHM = INPLACE, LOCK = NONE;

CREATE TABLE t_news_curation_budget_guard (
    budgetDate DATE PRIMARY KEY,
    maxCalls INT NOT NULL,
    maxTokens BIGINT NOT NULL,
    maxCostMicros BIGINT NOT NULL
) ENGINE = InnoDB
  COMMENT = 'Global UTC day budget limits and cross-scope transaction lock';
