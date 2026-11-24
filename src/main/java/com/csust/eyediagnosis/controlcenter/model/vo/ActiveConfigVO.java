package com.csust.eyediagnosis.controlcenter.model.vo;

import java.util.Date;
import java.util.Map;

/**
 * 当前生效配置。
 *
 * @param source       DEFAULT（尚未发布过，全部来自代码默认值）/ RELEASE
 * @param releaseId    当前生效 release id；DEFAULT 时为 null
 * @param values       已与默认值合并后的完整键值
 */
public record ActiveConfigVO(Long releaseId,
                             Long releaseVersion,
                             int schemaVersion,
                             String digest,
                             String source,
                             Map<String, Object> values,
                             Date updatedTime) {
}
