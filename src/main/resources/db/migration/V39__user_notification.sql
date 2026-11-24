-- 用户通知（UserNotification）：站内消息，告诉用户「有东西变了、去哪里看」。
--
-- 为什么是站内消息而不是待办：本表只承载「可读的一条消息」，真正的待办仍在各自
-- 的业务表里（未读判读任务、待处理反馈）。通知不是第二份台账，删掉它不影响任何业务。
--
-- 为什么存文案（title / body）而不是只存 type：通知是「发出去那一刻的通知」，
-- 90 天后清理。类型文案以后会改，历史消息应当保持当时的措辞，不能因为前端的
-- 枚举改名而变成空白。type 仍保留，供筛选与后续统计使用。
--
-- 为什么正文只有指针（不放结论、病名、严重度）：通知可能出现在锁屏预览、
-- 邮件摘要之类第三方上下文里。真正的内容在登录后的记录页，那里有免责声明。
--
-- 为什么没有 isDelete：通知是瞬时消息，已读且超期即物理删除（见
-- NotificationCleanupScheduler）。逻辑删除会给一张只增不减的小表再叠一层过滤。
--
-- 主键不写 AUTO_INCREMENT：id 由实体 @TableId(type = ASSIGN_ID) 生成雪花值，
-- 与 t_user_feedback 的写入路径保持一致（两表的列定义都跟着实体走，
-- 不出现「列定义说自增、代码说雪花」的不一致）。
--
-- 索引：
--   (userId, createTime)  列表按时间倒序翻页
--   (userId, readAt)      未读数 COUNT 与「全部已读」的条件更新
--
-- refType / refId 只作深链参数（DIAGNOSIS_TASK → /records、USER_FEEDBACK → /feedback），
-- 不再回表校验：通知是历史消息，关联对象被删后消息仍应显示，点进去看到的是列表页。

CREATE TABLE t_user_notification (
    id         BIGINT       NOT NULL PRIMARY KEY COMMENT '雪花 id（IdType.ASSIGN_ID）',
    userId     BIGINT       NOT NULL COMMENT '接收人',
    type       VARCHAR(32)  NOT NULL COMMENT 'DIAGNOSIS_READY / DIAGNOSIS_RESTRICTED / REVIEW_DONE / FEEDBACK_REPLIED',
    title      VARCHAR(120) NOT NULL COMMENT '发送时定稿的标题',
    body       VARCHAR(255) NOT NULL COMMENT '发送时定稿的正文；只给指针，不放结论',
    refType    VARCHAR(24)  NOT NULL COMMENT 'DIAGNOSIS_TASK / USER_FEEDBACK',
    refId      VARCHAR(64)  NOT NULL COMMENT 'taskId / 反馈 id',
    readAt     DATETIME(6)  NULL COMMENT '首次已读时间；NULL = 未读',
    createTime DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '发送时间',
    KEY idx_user_notification_user_time (userId, createTime),
    KEY idx_user_notification_user_read (userId, readAt)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT '用户通知（站内消息，不是医疗记录）';
