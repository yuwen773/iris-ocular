-- WP-07G: audit trail for manual collection-window operations (enqueue / failed-window retry).
-- Additive to master V39 (this branch already owns V40-V42).
-- Naming convention: camelCase columns (mybatis-plus map-underscore-to-camel-case: false),
-- UTC DATETIME(6) timestamps, and ascii_bin collations for case-exact identifiers.

CREATE TABLE IF NOT EXISTS t_news_collection_task_event (
    id          BIGINT       PRIMARY KEY AUTO_INCREMENT,
    taskId      VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    action      VARCHAR(32)  CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    fromStatus  VARCHAR(24)  CHARACTER SET ascii COLLATE ascii_bin NULL,
    toStatus    VARCHAR(24)  CHARACTER SET ascii COLLATE ascii_bin NULL,
    actorId     BIGINT       NULL,
    actorRole   VARCHAR(24)  NULL,
    note        VARCHAR(512) NULL,
    createdAt   DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX idx_news_task_event_task (taskId, id),
    INDEX idx_news_task_event_created (createdAt)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'Admin operation audit trail for news collection window tasks';
