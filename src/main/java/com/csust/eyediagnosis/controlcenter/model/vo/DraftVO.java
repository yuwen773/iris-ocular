package com.csust.eyediagnosis.controlcenter.model.vo;

import java.util.Date;
import java.util.Map;

/**
 * 草稿视图。
 *
 * @param revision           乐观并发用的版本号；PUT 必须回传
 * @param status             DRAFT / VALIDATED
 * @param values             当前草稿值（未与默认值合并）
 * @param validationRevision 校验结论对应的 revision；null 表示未校验
 * @param validationResult   字段级校验结果；null 表示未校验
 */
public record DraftVO(long id,
                      long revision,
                      String status,
                      Map<String, Object> values,
                      Long validationRevision,
                      Object validationResult,
                      Long updatedBy,
                      Date updateTime) {
}
