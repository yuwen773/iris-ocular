package com.csust.eyediagnosis.controlcenter.model.vo;

import java.util.List;

/** 注册表视图：前端据此渲染表单与长度上限。 */
public record RegistryVO(int schemaVersion, List<KeyVO> keys) {

    public record KeyVO(String key, String type, String defaultValue, int minLength, int maxLength) {
    }
}
