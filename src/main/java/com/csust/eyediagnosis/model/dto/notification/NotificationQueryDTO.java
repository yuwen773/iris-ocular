package com.csust.eyediagnosis.model.dto.notification;

import com.csust.eyediagnosis.common.PageRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;

/**
 * 我的通知分页条件。
 *
 * <p>没有 {@code userId}：接收人恒取会话用户，请求体夹带的一律忽略
 * （与 {@code UserFeedbackQueryDTO} 同一口径）。
 */
@EqualsAndHashCode(callSuper = true)
@Data
public class NotificationQueryDTO extends PageRequest implements Serializable {

    /** 类型精确匹配；空表示不限。取值见 {@code NotificationType}。 */
    private String type;

    /**
     * 只看未读。默认 false（「全部」页签）。
     * 「未读」页签仍要翻页，所以它必须住在查询里，而不是靠前端过滤当前页。
     */
    private Boolean unreadOnly;

    private static final long serialVersionUID = 1L;
}
