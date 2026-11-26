-- WP-08B.2 收口：候选替代（审计）+ 生命周期总预算 + 建稿模板版本
--
-- 1) 候选替代：证据核验被撤回并重新核验后，旧候选按规则不能再用；替代不是覆盖，
--    而是「新建候选 + 旧候选转入 SUPERSEDED + 事件行」，三样都留痕。
--    唯一键因此从 (materialRevisionId, policyVersion) 改为「仅活跃行唯一」的生成列，
--    与 t_news_evidence_verification 的 activeKey 同一手法：已替代的行保留全部历史。
-- 2) 总预算：按日/按 scope 的预算挡不住「换一天再来」「拆 scope」「重启」，所以把
--    整次实验的调用数与费用上限钉在一张单例表上，事务内以行锁串行化。
-- 3) 建稿：新增 draftTemplateVersion 与冻结的结构化内容副本，
--    旧行的 draftTemplateVersion 为 NULL，建稿仍走旧版拼接逻辑。

ALTER TABLE t_news_curation_candidate
    ADD COLUMN supersededByCandidateId BIGINT NULL
        COMMENT '替代它的新候选 id；只写一次，旧候选内容列永不覆盖',
    ADD COLUMN supersededAt            DATETIME(6) NULL,
    ADD COLUMN supersedeReason         VARCHAR(255) NULL,
    ADD COLUMN draftTemplateVersion    VARCHAR(32) NULL
        COMMENT '建稿模板版本；NULL 表示本列引入前的旧候选，建稿仍走旧版拼接逻辑',
    ADD COLUMN structuredContentJson   MEDIUMTEXT NULL
        COMMENT 'WP-08B.2 契约校验通过后的完整结构化内容（冻结回执的 content 节点副本），建稿只排版不判断',
    ADD COLUMN researchValueScore      INT NULL
        COMMENT '联合精选 P05 的研究价值评分；与 relevanceScore 一起保留可追溯性',
    ADD INDEX idx_news_curation_candidate_superseded (supersededByCandidateId),
    ALGORITHM = INPLACE, LOCK = NONE;

ALTER TABLE t_news_curation_candidate
    ADD COLUMN activeKey VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin
        GENERATED ALWAYS AS (IF(supersededAt IS NULL, CONCAT(materialRevisionId, ':', policyVersion), NULL)) VIRTUAL,
    ALGORITHM = INPLACE, LOCK = NONE;

-- 同名旧唯一键退场：唯一性改为「每个资料修订 + 策略版本最多一条活跃候选」。
ALTER TABLE t_news_curation_candidate
    DROP INDEX uk_news_curation_rev_policy,
    ADD UNIQUE KEY uk_news_curation_active (activeKey),
    ALGORITHM = INPLACE, LOCK = NONE;

CREATE TABLE IF NOT EXISTS t_news_curation_candidate_event (
    id                 BIGINT       PRIMARY KEY AUTO_INCREMENT,
    candidateId        BIGINT       NOT NULL,
    eventType          VARCHAR(32)  CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    fromStatus         VARCHAR(32)  CHARACTER SET ascii COLLATE ascii_bin NULL,
    toStatus           VARCHAR(32)  CHARACTER SET ascii COLLATE ascii_bin NULL,
    relatedCandidateId BIGINT       NULL,
    relatedCallId      VARCHAR(64)  CHARACTER SET ascii COLLATE ascii_bin NULL,
    actor              VARCHAR(128) NOT NULL,
    note               VARCHAR(255) NULL,
    createdAt          DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX idx_news_candidate_event_candidate (candidateId, id),
    INDEX idx_news_candidate_event_related (relatedCandidateId),
    INDEX idx_news_candidate_event_call (relatedCallId)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'Curated candidate lifecycle events: materialized / superseded, append-only';

CREATE TABLE IF NOT EXISTS t_news_curation_lifetime_budget (
    id            TINYINT      PRIMARY KEY,
    maxCalls      INT          NOT NULL,
    maxTokens     BIGINT       NOT NULL,
    maxCostMicros BIGINT       NOT NULL,
    note          VARCHAR(255) NULL,
    createdAt     DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updatedAt     DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'Single-row lifetime (whole-experiment) budget ceiling; counts every receipt across dates and scopes';
