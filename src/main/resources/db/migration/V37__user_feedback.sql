-- 用户反馈（UserFeedback）：产品意见通道，不是医疗记录。
-- 与 GLOSSARY.md「三个「反馈 / 意见」不要混用」一节对应。
--
-- 为什么新表：反馈有自己的生命周期（待处理 → 处理中 → 已解决 / 已忽略），
-- 与 t_diagnosis 的医疗语义无关；混进去会让判读统计、复核队列与数据分析口径
-- 带上「意见」噪声，而漏掉一处过滤就污染医疗读数。
--
-- 为什么表名带 user：代码里 feedback 一词已被 Critic 反馈占用
-- （FourRoleGraph.feedback = NONE/REWRITE/SUPPLEMENT、SSE agent.feedback.requested，
-- 已冻结契约）；裸词 t_feedback 会让两个不同概念同名。
--
-- 编号说明：V32–V34 已随 PR #156 合入 master，V35–V36 由在途的
-- Iris Control Center 占用，本迁移取当前所有分支最大版本 +1 = V37
-- （站内通知另取 V38）。若合并前又有其它线占用，按「谁先合谁保留」重编号——
-- 脚本本身不含自身版本号，重编号只需改文件名与 FlywayMySqlIntegrationTest 的断言。
--
-- 主键不写 AUTO_INCREMENT：id 由实体 @TableId(type = ASSIGN_ID) 生成雪花值，
-- 列定义与真实来源保持一致（否则读迁移的人会以为库会分配 id）。
--
-- 值域（category / status / targetType）一律由 Java 常量类守卫，不加 CHECK：
-- 本机 3306 是 MySQL 5.7，解析 CHECK 但忽略，看着像约束其实不生效。
-- 正文用 TEXT 而不是 VARCHAR(64K)：MySQL 会把它静默收成 64KB 上限。
--
-- 状态机（全部走条件更新，不做读-改-写）：
--   PENDING    → PROCESSING / RESOLVED / IGNORED
--   PROCESSING → PENDING / RESOLVED / IGNORED
--   RESOLVED   → PROCESSING
--   IGNORED    → PENDING
-- 标记 RESOLVED 之前必须已有 reply：管理员要给出结论，不能只改状态。

CREATE TABLE t_user_feedback (
    id                  BIGINT       NOT NULL PRIMARY KEY,
    userId              BIGINT       NOT NULL COMMENT '提交人',
    category            VARCHAR(32)  NOT NULL COMMENT 'SUGGESTION / ISSUE / RESULT_DOUBT / OTHER',
    content             TEXT         NOT NULL COMMENT '正文',
    targetType          VARCHAR(16)  NULL COMMENT 'DIAGNOSIS_TASK / AGENT_SESSION；空=与具体对象无关',
    targetId            VARCHAR(64)  NULL COMMENT '关联对象 id（taskId / sessionId）',
    status              VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / PROCESSING / RESOLVED / IGNORED',
    reply               TEXT         NULL COMMENT '管理员回复正文（用户可见）',
    repliedById         BIGINT       NULL COMMENT '回复人（管理员 id）',
    repliedAt           DATETIME(6)  NULL COMMENT '回复时间',
    handledById         BIGINT       NULL COMMENT '最后处理人',
    handledAt           DATETIME(6)  NULL COMMENT '最后处理时间',
    attachmentObjectKey VARCHAR(512) NULL COMMENT '截图 COS object key；不存公开 URL',
    attachmentFormat    VARCHAR(16)  NULL COMMENT 'png / jpg',
    createTime          DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '提交时间',
    updateTime          DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    isDelete            TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-未删 1-已删',
    KEY idx_user_feedback_user (userId, createTime),
    KEY idx_user_feedback_status (status, createTime),
    KEY idx_user_feedback_target (targetType, targetId)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT '用户反馈';
