-- 配置中心核心表（Iris Control Center 1.0 / WP-01B）。
--
-- 五张表对应 ADR D2–D4 的四类载体：
--   1. t_config_draft           单例草稿 + revision 乐观锁（D3）
--   2. t_config_release         不可变发布快照，只插入（D2）
--   3. t_config_active_pointer  单行活跃指针，发布事务内 FOR UPDATE（D2）
--   4. t_config_request         请求级幂等记录（D3 / §7.7）
--   5. t_config_audit_event     只追加审计，与业务写同事务（D4）
--
-- 命名口径与既有库一致：列名小驼峰（MyBatis Plus map-underscore-to-camel-case=false）、
-- utf8mb4、ENGINE=InnoDB；不建外键（与 V30 同口径，跨表完整性由服务层事务保证）。
--
-- 编号说明：V29–V34 已被占用（V32 知识库候选索引版本、V33 判读任务受限原因、
-- V34 判读证据语义），本迁移从 V35 起；V1–V34 不得修改。
-- 重编号记录：本文件原拟为 V32，2026-11-24 核对 master c90b0caa 后发现 V32–V34
-- 已被占用，故改为 V35（V36 同理）。迁移内容未因此改变。

-- 单例草稿。id 恒为 1，靠主键把「全局只有一份草稿」（D3）落成结构约束，
-- 而不是靠服务层自觉。
CREATE TABLE t_config_draft (
    id BIGINT NOT NULL COMMENT '单例主键，恒为 1',
    revision BIGINT NOT NULL DEFAULT 0 COMMENT '草稿版本；每次成功编辑 +1，用于乐观并发',
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT / VALIDATED',
    payload JSON NOT NULL COMMENT '键值对；键必须来自代码内 Config Registry',
    validationRevision BIGINT NULL COMMENT '校验结果对应的 revision；NULL 表示未校验或已失效',
    validationResult JSON NULL COMMENT '字段级校验结果',
    updatedBy BIGINT NULL COMMENT '最后编辑人 userId',
    createTime DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updateTime DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT '配置中心单例草稿';

-- 不可变发布快照。只插入，不更新——回滚也是生成一条新 release（D2 / FR-02）。
CREATE TABLE t_config_release (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    releaseVersion BIGINT NOT NULL COMMENT '单调递增的发布序号，全局唯一',
    schemaVersion INT NOT NULL COMMENT '载荷 schema 版本；与代码内 SCHEMA_VERSION 对比',
    payload JSON NOT NULL COMMENT '自包含快照：本版本的全部配置值',
    digest CHAR(64) NOT NULL COMMENT '规范化 JSON 的 SHA-256（十六进制），用于比对与回滚溯源',
    rollbackFromReleaseId BIGINT NULL COMMENT '非空表示本版本由回滚某历史版本生成',
    createdBy BIGINT NOT NULL COMMENT '发布人 userId',
    createdTime DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_config_release_version (releaseVersion)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT '配置中心不可变发布快照';

-- 单行活跃指针。发布事务先 SELECT ... FOR UPDATE 本行，把并发发布串行化。
CREATE TABLE t_config_active_pointer (
    id BIGINT NOT NULL COMMENT '单例主键，恒为 1',
    releaseId BIGINT NULL COMMENT '当前生效的 release id；NULL 表示尚未发布过',
    updatedBy BIGINT NULL COMMENT '最后切换人 userId',
    updatedTime DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT '配置中心单活跃指针';

-- 请求级幂等。UNIQUE(actorId, requestId) 是唯一判重点；
-- requestId 用 utf8mb4_bin，避免大小写不敏感的唯一键把两个不同请求判成同一个。
CREATE TABLE t_config_request (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    actorId BIGINT NOT NULL COMMENT '发起人 userId',
    requestId VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '客户端生成的幂等键',
    requestDigest CHAR(64) NOT NULL COMMENT 'method + path + 规范化 body 的 SHA-256',
    action VARCHAR(32) NOT NULL COMMENT 'DRAFT_CREATE / DRAFT_UPDATE / DRAFT_VALIDATE',
    resultCode INT NOT NULL COMMENT '首次执行的结果码（0 表示成功）',
    resultPayload JSON NULL COMMENT '首次执行的响应数据，用于重放',
    createdTime DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_config_request_actor (actorId, requestId)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT '配置中心幂等请求记录';

-- 只追加审计。与业务写同事务（D4）；不落密钥、Session、CSRF token、Provider 凭据。
CREATE TABLE t_config_audit_event (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    eventId CHAR(36) NOT NULL COMMENT '事件 UUID',
    actorId BIGINT NULL COMMENT '操作人；前置认证阶段拒绝时可能无法确定',
    action VARCHAR(48) NOT NULL COMMENT 'DRAFT_CREATE / DRAFT_UPDATE / DRAFT_VALIDATE / ACCESS_DENIED …',
    targetId VARCHAR(64) NULL COMMENT '作用对象（草稿/发布 id）',
    requestId VARCHAR(64) NULL COMMENT '客户端幂等键，可与 t_config_request 对照',
    beforeVersion VARCHAR(64) NULL COMMENT '变更前版本标识（如草稿 revision）',
    afterVersion VARCHAR(64) NULL COMMENT '变更后版本标识',
    result VARCHAR(16) NOT NULL COMMENT 'SUCCESS / DENIED / FAILED',
    reasonCode VARCHAR(48) NULL COMMENT '失败时与错误码一一对应的原因码',
    payload JSON NULL COMMENT '字段级 diff 摘要；不含敏感值',
    occurredAt DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_config_audit_event (eventId),
    KEY idx_config_audit_time (occurredAt)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT '配置中心审计事件（只追加）';

-- 活跃指针的单例行。先建行，发布事务才能直接 SELECT ... FOR UPDATE，
-- 不必在并发下先抢一把插入。
INSERT INTO t_config_active_pointer (id, releaseId) VALUES (1, NULL);
