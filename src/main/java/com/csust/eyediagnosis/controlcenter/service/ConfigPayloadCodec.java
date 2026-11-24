package com.csust.eyediagnosis.controlcenter.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * 配置载荷的规范化、摘要与解析。
 *
 * <p>三处需要「同一份 JSON」的判断都依赖它：发布摘要、幂等请求摘要、审计 diff。
 * 键序与空白必须稳定——否则同一份内容每次算出的 digest 都不同，幂等与回滚
 * 溯源会全部失效。因此这里是<b>唯一</b>的序列化口径：
 * <ul>
 *   <li>键按字典序重排；</li>
 *   <li>不输出多余空白（{@code writeValueAsString} 的紧凑形式）；</li>
 *   <li>值只允许字符串或 null（参见 {@code ConfigValueValidator}）。</li>
 * </ul>
 */
@Component
public class ConfigPayloadCodec {

    private final ObjectMapper mapper = new ObjectMapper();

    /** 解析数据库里的载荷；空白或 null 视为空载荷。 */
    @SuppressWarnings("unchecked")
    public Map<String, Object> parse(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Object parsed = mapper.readValue(json, Object.class);
            if (parsed == null) {
                return Map.of();
            }
            if (!(parsed instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException("配置载荷必须是 JSON 对象");
            }
            Map<String, Object> values = new LinkedHashMap<>();
            map.forEach((key, value) -> values.put(String.valueOf(key), value));
            return values;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("配置载荷不是合法 JSON", e);
        }
    }

    /** 规范化 JSON：键字典序、紧凑输出。 */
    public String canonical(Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            return "{}";
        }
        Map<String, Object> ordered = new TreeMap<>();
        values.forEach((key, value) -> ordered.put(key, value));
        try {
            return mapper.writeValueAsString(ordered);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("配置载荷无法序列化", e);
        }
    }

    /** 规范化 JSON 的 SHA-256（十六进制）。 */
    public String digest(String canonicalJson) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] hash = sha256.digest(canonicalJson.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 幂等请求摘要：method + path + 规范化 body。 */
    public String requestDigest(String method, String path, Map<String, Object> body) {
        return digest(method.toUpperCase() + "\n" + path + "\n" + canonical(body));
    }

    /**
     * 用发布值覆盖默认值，得到公开读取的有效配置。
     *
     * <p>默认值来自代码注册表，发布值来自不可变快照：未发布的键回落到默认值，
     * 而不是变成 null——空库启动必须返回完整品牌（AC-01-01）。
     */
    public Map<String, Object> overlay(Map<String, String> defaults, Map<String, Object> values) {
        Map<String, Object> merged = new TreeMap<>(defaults);
        if (values != null) {
            values.forEach((key, value) -> {
                if (value != null) {
                    merged.put(key, value);
                }
            });
        }
        return merged;
    }
}
