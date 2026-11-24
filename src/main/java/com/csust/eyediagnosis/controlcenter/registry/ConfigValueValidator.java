package com.csust.eyediagnosis.controlcenter.registry;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * 配置值校验。
 *
 * <p>校验分两层，但共用同一份规则：
 * <ul>
 *   <li><b>保存时</b>只拒绝「结构性错误」——未知键、部署保护键、类型不对、
 *       长度越界。草稿允许不完整（可以先存一半），但不允许存进一个永远
 *       不可能发布的内容。</li>
 *   <li><b>校验端点</b>产出字段级结果，供 UI 逐项提示。</li>
 * </ul>
 *
 * <p>长度按 <b>Unicode 码点</b> 计（{@link String#codePointCount}），不用
 * {@code length()}：中文与 emoji 在 UTF-16 里各占两个 char，用 length 会把
 * 40 字的中文名判成 80。
 */
@Component
public class ConfigValueValidator {

    /** 服务端签发的 Asset ID：32 位小写十六进制。 */
    private static final Pattern ASSET_ID = Pattern.compile("^[0-9a-f]{32}$");

    private final ConfigRegistry registry;

    public ConfigValueValidator(ConfigRegistry registry) {
        this.registry = registry;
    }

    /**
     * 校验整份载荷。
     *
     * @param values 键 → 值（来自 JSON 对象；值为 String 或 null）
     * @return 字段级结果 + 首要原因码（供错误码映射使用）
     */
    public Result validate(Map<String, Object> values) {
        Map<String, List<String>> fieldErrors = new TreeMap<>();
        String primaryReason = null;

        if (values == null) {
            return new Result(true, Map.of(), null);
        }

        for (Map.Entry<String, Object> entry : values.entrySet()) {
            String key = entry.getKey();
            Object raw = entry.getValue();

            if (registry.isDeployProtected(key)) {
                addError(fieldErrors, key, "DEPLOY_PROTECTED");
                primaryReason = primaryReason == null ? "DEPLOY_PROTECTED" : primaryReason;
                continue;
            }
            if (isProviderNamespace(key)) {
                // WP-03 未实现 Provider：这类键不是「拼错了」，而是当前版本不支持。
                // 单独成码，避免将来 Provider 表建好时，历史草稿里的半成品引用被静默接受。
                addError(fieldErrors, key, "PROVIDER_NOT_SUPPORTED");
                primaryReason = primaryReason == null ? "PROVIDER_NOT_SUPPORTED" : primaryReason;
                continue;
            }
            ConfigKey definition = registry.find(key).orElse(null);
            if (definition == null) {
                addError(fieldErrors, key, "UNKNOWN_KEY");
                primaryReason = primaryReason == null ? "UNKNOWN_KEY" : primaryReason;
                continue;
            }
            if (raw != null && !(raw instanceof String)) {
                addError(fieldErrors, key, "TYPE_MISMATCH");
                primaryReason = primaryReason == null ? "TYPE_MISMATCH" : primaryReason;
                continue;
            }
            String value = (String) raw;
            List<String> problems = validateValue(definition, value);
            if (!problems.isEmpty()) {
                fieldErrors.put(key, problems);
                primaryReason = primaryReason == null ? problems.get(0) : primaryReason;
            }
        }

        return new Result(fieldErrors.isEmpty(), immutable(fieldErrors), primaryReason);
    }

    /** 单值校验；返回空列表表示通过。 */
    public List<String> validateValue(ConfigKey definition, String value) {
        List<String> problems = new ArrayList<>();
        if (definition.type() == ConfigType.STRING) {
            if (value == null) {
                problems.add("REQUIRED");
                return problems;
            }
            String trimmed = value.trim();
            int length = trimmed.codePointCount(0, trimmed.length());
            if (length < definition.minLength() || length > definition.maxLength()) {
                problems.add("LENGTH_OUT_OF_RANGE");
            }
            if (trimmed.codePoints().anyMatch(Character::isISOControl)) {
                problems.add("CONTROL_CHARACTER");
            }
            return problems;
        }

        // ASSET_REF：null 合法（使用内置资源）；其余必须是服务端签发的 Asset ID。
        if (value == null) {
            return problems;
        }
        if (!ASSET_ID.matcher(value).matches()) {
            problems.add("ASSET_REF_INVALID");
        }
        return problems;
    }

    /** 出现在载荷里的键是否全部可管理（用于把「未知键」映射成 40904 之前做快速判断）。 */
    public Set<String> unknownKeys(Map<String, Object> values) {
        if (values == null) {
            return Set.of();
        }
        return values.keySet().stream()
                .filter(key -> !registry.isDeployProtected(key))
                .filter(key -> !isProviderNamespace(key))
                .filter(key -> registry.find(key).isEmpty())
                .collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));
    }

    /**
     * Provider 命名空间（{@code provider} / {@code provider.*} / {@code providers*}）。
     *
     * <p>WP-03 尚未实现 Provider；这类键必须显式拒绝（40911），不能混进「未知键」，
     * 否则将来建好 Provider 表时，历史草稿里的半成品引用会被当成正常键接受。
     */
    public static boolean isProviderNamespace(String key) {
        if (key == null) {
            return false;
        }
        String lower = key.toLowerCase(java.util.Locale.ROOT);
        return lower.equals("provider") || lower.startsWith("provider.") || lower.startsWith("providers");
    }

    private void addError(Map<String, List<String>> fieldErrors, String key, String code) {
        fieldErrors.computeIfAbsent(key, ignored -> new ArrayList<>()).add(code);
    }

    private Map<String, List<String>> immutable(Map<String, List<String>> source) {
        Map<String, List<String>> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(key, List.copyOf(value)));
        return Map.copyOf(copy);
    }

    /** 校验结果。 */
    public record Result(boolean valid,
                         Map<String, List<String>> fieldErrors,
                         String primaryReason) {
    }
}
