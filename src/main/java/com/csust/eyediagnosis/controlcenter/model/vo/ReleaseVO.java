package com.csust.eyediagnosis.controlcenter.model.vo;

import java.util.Date;
import java.util.Map;

/**
 * 发布快照视图。
 *
 * @param releaseVersion       单调递增发布序号；回滚生成的 release 也占一个新号
 * @param digest               {@code values} 规范化 JSON 的 SHA-256
 * @param rollbackFromReleaseId 非空表示这条 release 由回滚生成，指向被回滚到的来源
 * @param values               该 release 的<b>完整</b>配置值（已与代码默认值合并），
 *                             不含代码默认值以外的任何字段——读它的人不需要再去查注册表
 */
public record ReleaseVO(long id,
                        long releaseVersion,
                        Integer schemaVersion,
                        String digest,
                        Long rollbackFromReleaseId,
                        Long createdBy,
                        Date createdTime,
                        Map<String, Object> values) {
}
