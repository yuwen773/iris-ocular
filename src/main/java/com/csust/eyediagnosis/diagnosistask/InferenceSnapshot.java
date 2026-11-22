package com.csust.eyediagnosis.diagnosistask;

import com.csust.eyediagnosis.grpc.DiagnosisProto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@code t_diagnosis_task.inferenceSnapshot} 的唯一读写模块。
 *
 * <p>快照是一个「内容寻址的信封」：六个身份字段把推理结果绑定到任务、用户与对象版本，
 * 一个 base64 字段携带原始 protobuf。它不是患者结果，也不是正式判读记录——只是受控流程
 * （草稿、复核、解释、协作）共享的输入。
 *
 * <p><b>为什么收成一个 module</b>：写信封的人（任务执行器、测试播种器）和读信封的人
 * （草稿协调器、复核、解释、协作）此前各自知道 JSON 键名、base64 与 protobuf 解码顺序。
 * 信封因此成了隐式接口：加一个字段要 grep 五个包。现在键名、解码与身份核对只在这里；
 * 调用方只认 {@link #write}、{@link #parse}、{@link #matches} 和 {@link #digest}。
 *
 * <p><b>身份核对</b>：{@link #matches(Map)} 与被它替换的原实现逐字等价——快照里的六个字段
 * 必须与任务行同名字段的可读值完全一致，缺一个都算不匹配。这里不判断医学可用性
 * （拒识 / OOD / 概率区间），那是调用方的门禁。
 */
public final class InferenceSnapshot {

    /** 绑定推理输入的身份字段；顺序即写入顺序，便于排查与 diff。 */
    public static final List<String> IDENTITY_FIELDS = List.of(
            "taskId", "userId", "objectKey", "objectVersion", "imageDigest", "imageFormat");

    private static final String RESPONSE_FIELD = "responseProtobufBase64";
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 写入快照所需的身份信息。
     *
     * @param userId 任务所有者；写成数字节点，读取时按 {@code Objects.toString} 比较
     */
    public record Identity(String taskId, long userId, String objectKey, String objectVersion,
                           String imageDigest, String imageFormat) {
        public Identity {
            taskId = require(taskId, "taskId");
            objectKey = require(objectKey, "objectKey");
            objectVersion = require(objectVersion, "objectVersion");
            imageDigest = require(imageDigest, "imageDigest");
            imageFormat = require(imageFormat, "imageFormat");
        }

        private static String require(String value, String name) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("Inference snapshot identity is missing " + name);
            }
            return value;
        }
    }

    /** 快照无法解析时抛出。继承 {@link IllegalArgumentException}，既有 catch 口径不变。 */
    public static final class InvalidSnapshotException extends IllegalArgumentException {
        public InvalidSnapshotException(String message) {
            super(message);
        }

        public InvalidSnapshotException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final String raw;
    private final JsonNode root;
    private final DiagnosisProto.DiagnosisResponse response;

    private InferenceSnapshot(String raw, JsonNode root, DiagnosisProto.DiagnosisResponse response) {
        this.raw = raw;
        this.root = root;
        this.response = response;
    }

    /**
     * 信封的唯一 writer。字段顺序固定，便于比较与排查；内容相同的输入得到相同字节。
     */
    public static String write(Identity identity, DiagnosisProto.DiagnosisResponse response) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(response, "response");
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("taskId", identity.taskId());
        envelope.put("userId", identity.userId());
        envelope.put("objectKey", identity.objectKey());
        envelope.put("objectVersion", identity.objectVersion());
        envelope.put("imageDigest", identity.imageDigest());
        envelope.put("imageFormat", identity.imageFormat());
        envelope.put(RESPONSE_FIELD, Base64.getEncoder().encodeToString(response.toByteArray()));
        try {
            return JSON.writeValueAsString(envelope);
        } catch (Exception impossible) {
            throw new IllegalStateException("Unable to encode inference snapshot", impossible);
        }
    }

    /**
     * 解析存储的原始快照。JSON、payload 或 base64 非法都抛
     * {@link InvalidSnapshotException}，不返回一个「看起来干净」的半成品。
     *
     * <p>身份字段的完整性与匹配由 {@link #matches(Map)} 判断：只做解码的调用方
     * （解释、测试播种）此前就不核对身份，保持同一口径，不在这里替它们收紧。
     */
    public static InferenceSnapshot parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new InvalidSnapshotException("Inference snapshot is empty");
        }
        JsonNode root;
        try {
            root = JSON.readTree(raw);
        } catch (Exception e) {
            throw new InvalidSnapshotException("Inference snapshot is not valid JSON", e);
        }
        if (root == null || !root.isObject()) {
            throw new InvalidSnapshotException("Inference snapshot is not a JSON object");
        }
        JsonNode encoded = root.get(RESPONSE_FIELD);
        if (encoded == null || !encoded.isTextual()) {
            throw new InvalidSnapshotException("Inference snapshot is missing " + RESPONSE_FIELD);
        }
        DiagnosisProto.DiagnosisResponse response;
        try {
            response = DiagnosisProto.DiagnosisResponse.parseFrom(
                    Base64.getDecoder().decode(encoded.asText()));
        } catch (Exception e) {
            throw new InvalidSnapshotException("Inference payload is not valid protobuf", e);
        }
        return new InferenceSnapshot(raw, root, response);
    }

    /** 原始 JSON 字符串（即 {@code inferenceSnapshot} 列的值）。 */
    public String raw() {
        return raw;
    }

    /** 解码后的推理响应。调用方在此之上再做自己的医学门禁。 */
    public DiagnosisProto.DiagnosisResponse response() {
        return response;
    }

    /**
     * 读一个身份字段（{@link #IDENTITY_FIELDS} 之一）。
     *
     * <p>键名知识留在模块里：调用方传字段名，不解析 JSON。字段不在信封里时返回
     * {@code null}（旧测试夹具里存在只带 payload 的快照）。
     */
    public String identity(String field) {
        if (!IDENTITY_FIELDS.contains(field)) {
            throw new IllegalArgumentException("Unknown inference snapshot field: " + field);
        }
        JsonNode node = root.get(field);
        return node == null || node.isNull() ? null : node.asText();
    }

    /** 快照内容的 SHA-256（十六进制）。草稿 / 协作用它绑定「同一份输入」。 */
    public String digest() {
        return digest(raw);
    }

    /**
     * 快照内容摘要。与协作模块此前公开的 {@code digest} 逐字等价，
     * 供「只有原始字符串、没有解析结果」的调用方使用。
     */
    public static String digest(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 快照是否绑定到这一条任务行：六个身份字段都必须与行内的同名字段一致。
     *
     * <p>与旧的逐字段循环等价；任一侧缺失都算不匹配，不做宽松比较。
     */
    public boolean matches(Map<String, Object> taskRow) {
        if (taskRow == null) return false;
        for (String field : IDENTITY_FIELDS) {
            if (!matchesField(field, taskRow.get(field))) return false;
        }
        return true;
    }

    /** 与 {@link #matches(Map)} 同一口径的强类型入口。 */
    public boolean matches(Identity identity) {
        if (identity == null) return false;
        return matchesField("taskId", identity.taskId())
                && matchesField("userId", identity.userId())
                && matchesField("objectKey", identity.objectKey())
                && matchesField("objectVersion", identity.objectVersion())
                && matchesField("imageDigest", identity.imageDigest())
                && matchesField("imageFormat", identity.imageFormat());
    }

    private boolean matchesField(String field, Object expected) {
        JsonNode node = root.get(field);
        return node != null && node.asText().equals(Objects.toString(expected, ""));
    }
}
