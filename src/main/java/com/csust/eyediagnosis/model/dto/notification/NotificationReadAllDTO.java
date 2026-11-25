package com.csust.eyediagnosis.model.dto.notification;

import lombok.Data;

import java.io.Serializable;

/**
 * 「全部已读」的范围：只清当前筛选下的未读，不是全账号一键清空。
 *
 * <p>刻意没有 {@code unreadOnly}：把已读行再标一次已读是空操作，
 * 能改变结果的条件只有 {@code type}。多一个恒等于无操作的参数，
 * 只会让人以为它有别的含义。
 */
@Data
public class NotificationReadAllDTO implements Serializable {

    /** 只清这个类型；空表示当前用户全部未读。 */
    private String type;

    private static final long serialVersionUID = 1L;
}
