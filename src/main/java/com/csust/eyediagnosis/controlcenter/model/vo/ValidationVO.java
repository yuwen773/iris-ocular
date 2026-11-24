package com.csust.eyediagnosis.controlcenter.model.vo;

import java.util.List;
import java.util.Map;

/** 草稿校验结果。无效是正常返回值，不是异常。 */
public record ValidationVO(long id,
                           long revision,
                           boolean valid,
                           Map<String, List<String>> fieldErrors) {
}
