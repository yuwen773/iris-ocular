-- 候选索引版本：文档更新时先写入候选版本，成功后再原子切换 indexVersion。
-- 旧版本在候选版本 READY 之前保持可检索，避免一次失败的编辑让知识立即不可用。
ALTER TABLE t_knowledge_document
    ADD COLUMN pendingIndexVersion VARCHAR(64) NULL COMMENT '候选索引版本（处理中）',
    ADD COLUMN pendingUpdatedTime DATETIME(6) NULL COMMENT '候选版本开始时间';
