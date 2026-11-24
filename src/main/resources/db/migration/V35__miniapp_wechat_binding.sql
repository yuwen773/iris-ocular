-- 小程序（微信）账号绑定表。
--
-- 设计取舍：
-- 1) 不改 t_user —— 其 userAccount 有唯一键、userPassword 为 NOT NULL，直接加列会让
--    既有注册/登录路径与历史数据一起承担迁移风险；绑定关系单独成表，读写都在新代码里。
-- 2) 一个 openid 在同一个 AppID 下只对应一个 t_user；换主体/换小程序时 openid 会不同，
--    因此唯一键是 (appid, openid) 而不是 openid。
-- 3) unionid 仅在绑定微信开放平台后才会下发，允许为空。
CREATE TABLE IF NOT EXISTS `t_user_wechat`
(
    `id`         BIGINT AUTO_INCREMENT PRIMARY KEY,
    `userId`     BIGINT       NOT NULL COMMENT 't_user.id',
    `appid`      VARCHAR(64)  NOT NULL COMMENT '小程序 AppID',
    `openid`     VARCHAR(128) NOT NULL COMMENT '微信 openid（同一 AppID 内唯一）',
    `unionid`    VARCHAR(128) NULL COMMENT '微信 unionid（绑定开放平台时才有）',
    `createTime` DATETIME DEFAULT CURRENT_TIMESTAMP NOT NULL COMMENT '创建时间',
    `updateTime` DATETIME DEFAULT CURRENT_TIMESTAMP NOT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY `uk_appid_openid` (`appid`, `openid`),
    INDEX `idx_userId` (`userId`)
) COMMENT '小程序微信账号绑定' COLLATE = utf8mb4_unicode_ci;
