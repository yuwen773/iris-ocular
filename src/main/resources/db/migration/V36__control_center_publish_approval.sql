-- 配置中心发布审批（Iris Control Center 1.0 / WP-01C）。
--
-- 一张新表：审批请求把「某一刻的草稿内容 + 校验结论 + 期望的活跃版本」钉死，
-- 审批通过时逐条重验后才允许生成 release（见
-- docs/product/wp-01/wp-01c-publish-approval-design.md §2）。
--
-- 编号：V1–V35 已应用且不得修改；本迁移只追加 V36。不修改任何既有表。
-- 重编号记录：本文件原拟为 V33，2026-11-24 核对 master c90b0caa 后发现 V32–V34
-- 已被占用，故改为 V36。迁移内容未因此改变。
-- 口径与既有库一致：列名小驼峰、utf8mb4、InnoDB、不建外键。

CREATE TABLE t_config_publish_request (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    actorId BIGINT NOT NULL COMMENT '申请人 userId',
    requestId VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL
        COMMENT '申请时的客户端幂等键；与 t_config_request 对照',
    draftRevision BIGINT NOT NULL COMMENT '申请时的草稿 revision',
    payloadDigest CHAR(64) NOT NULL COMMENT '申请时草稿载荷的 SHA-256（规范化 JSON）',
    validationDigest CHAR(64) NOT NULL COMMENT '申请时校验结论（revision + result）的 SHA-256',
    expectedActiveReleaseId BIGINT NULL COMMENT '申请时的活跃 release id；NULL 表示尚未发布过',
    note VARCHAR(255) NULL COMMENT '申请人备注（不含敏感信息）',
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / APPROVED / REJECTED / STALE',
    requestedTime DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    decidedBy BIGINT NULL COMMENT '批准/拒绝人 userId',
    decidedTime DATETIME NULL,
    decisionReason VARCHAR(255) NULL,
    releaseId BIGINT NULL COMMENT '批准后生成的 release id',
    -- 同一管理员不能拿同一个 requestId 建出两条审批；这是 t_config_request 之外的结构性二道闸。
    UNIQUE KEY uk_config_publish_request_actor (actorId, requestId),
    KEY idx_config_publish_request_status (status, requestedTime),
    KEY idx_config_publish_request_draft (draftRevision)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT '配置中心发布审批请求';
