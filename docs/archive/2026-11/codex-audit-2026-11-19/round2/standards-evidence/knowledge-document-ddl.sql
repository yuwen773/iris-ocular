CREATE TABLE IF NOT EXISTS t_knowledge_document (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    userId      BIGINT        NOT NULL COMMENT '上传用户id',
    title       VARCHAR(255)  NOT NULL COMMENT '文档标题',
    fileName    VARCHAR(512)  NOT NULL COMMENT '原始文件名',
    fileUrl     VARCHAR(1024) NOT NULL COMMENT 'COS存储地址',
    fileSize    BIGINT        NULL COMMENT '文件大小(字节)',
    fileType    VARCHAR(32)   NOT NULL COMMENT '文件类型:pdf/md/txt/docx',
    chunkCount  INT           DEFAULT 0 COMMENT '分块数量',
    contentHash VARCHAR(64)   NULL COMMENT '内容SHA-256哈希，用于去重',
    status      TINYINT       DEFAULT 0 NOT NULL COMMENT '状态:0=处理中,1=已完成,2=失败',
    createTime  DATETIME      DEFAULT CURRENT_TIMESTAMP NOT NULL COMMENT '创建时间',
    updateTime  DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    isDelete    TINYINT       DEFAULT 0 NOT NULL COMMENT '逻辑删除',
    INDEX idx_userId (userId),
    INDEX idx_status (status)
) COMMENT '知识库文档表' COLLATE = utf8mb4_unicode_ci;