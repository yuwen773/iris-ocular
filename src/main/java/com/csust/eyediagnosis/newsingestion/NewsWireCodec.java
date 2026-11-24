package com.csust.eyediagnosis.newsingestion;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Strict, coercion-free parser, validator, and serializer for the News Engine v1 wire protocol.
 */
@Component
public class NewsWireCodec {

    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}$");
    private static final Pattern TOKEN_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{16,256}$");
    private static final Pattern PARTIAL_DATE_PATTERN = Pattern.compile("^[0-9]{4}(-[0-9]{2}(-[0-9]{2})?)?$");
    private static final Pattern ISO_DATE_PATTERN = Pattern.compile("^[0-9]{4}-[0-9]{2}-[0-9]{2}$");
    private static final Pattern PMID_PATTERN = Pattern.compile("^[1-9][0-9]{0,15}$");
    private static final DateTimeFormatter STRICT_ISO_DATE = DateTimeFormatter.ofPattern("uuuu-MM-dd")
            .withResolverStyle(ResolverStyle.STRICT);

    private static final Set<String> BATCH_KEYS = Set.of("generation", "leaseToken", "sequence", "records");
    private static final Set<String> LEASE_KEYS = Set.of("taskId", "generation", "leaseToken", "leaseExpiresAt", "window");
    private static final Set<String> WINDOW_KEYS = Set.of("source", "sourceConfigVersion", "query", "startOn", "endBefore");
    private static final Set<String> COMPLETE_KEYS = Set.of("generation", "leaseToken", "batchCount", "recordCount", "nextCursor");
    private static final Set<String> FAIL_KEYS = Set.of("generation", "leaseToken", "code", "retryable");
    private static final Set<String> MATERIAL_KEYS = Set.of(
            "source", "sourceRecordId", "url", "title", "language", "publishedOn",
            "updatedOn", "pmid", "doi", "abstract", "abstractLicense", "trial"
    );
    private static final Set<String> TRIAL_KEYS = Set.of("studyType", "overallStatus", "phases", "conditions");

    private final ObjectMapper mapper;

    public NewsWireCodec(@Qualifier("newsProtocolObjectMapper") ObjectMapper mapper) {
        this.mapper = mapper.copy().enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);
    }

    public static boolean isValidIdentifier(String value) {
        return value != null && IDENTIFIER_PATTERN.matcher(value).matches();
    }

    public NewsWireModels.Batch parseBatch(byte[] rawBody) {
        ObjectNode root = parseObject(rawBody, BATCH_KEYS);
        long generation = requirePositiveInt(root, "generation");
        String leaseToken = requireToken(root, "leaseToken");
        int sequence = requireIntInRange(root, "sequence", 1, NewsWireModels.MAX_WINDOW_BATCHES);
        JsonNode recordsNode = requireField(root, "records");
        if (!recordsNode.isArray() || recordsNode.isEmpty() || recordsNode.size() > NewsWireModels.MAX_BATCH_RECORDS) {
            throw NewsControlException.invalidRequest();
        }
        List<NewsWireModels.Material> records = new ArrayList<>(recordsNode.size());
        for (JsonNode item : recordsNode) {
            records.add(parseMaterialNode(item));
        }
        return new NewsWireModels.Batch(generation, leaseToken, sequence, List.copyOf(records));
    }

    public NewsWireModels.Complete parseComplete(byte[] rawBody) {
        ObjectNode root = parseObject(rawBody, COMPLETE_KEYS);
        long generation = requirePositiveInt(root, "generation");
        String leaseToken = requireToken(root, "leaseToken");
        int batchCount = requireIntInRange(root, "batchCount", 0, NewsWireModels.MAX_WINDOW_BATCHES);
        int recordCount = requireIntInRange(root, "recordCount", 0, NewsWireModels.MAX_WINDOW_RECORDS);
        if (recordCount < batchCount || recordCount > (long) batchCount * NewsWireModels.MAX_BATCH_RECORDS) {
            throw NewsControlException.invalidRequest();
        }
        LocalDate nextCursor = requireIsoDate(root, "nextCursor");
        return new NewsWireModels.Complete(generation, leaseToken, batchCount, recordCount, nextCursor);
    }

    public NewsWireModels.Fail parseFail(byte[] rawBody) {
        ObjectNode root = parseObject(rawBody, FAIL_KEYS);
        long generation = requirePositiveInt(root, "generation");
        String leaseToken = requireToken(root, "leaseToken");
        String codeText = requireString(root, "code", 1, 64);
        NewsWireModels.FailureCode code;
        try {
            code = NewsWireModels.FailureCode.valueOf(codeText);
        } catch (IllegalArgumentException e) {
            throw NewsControlException.invalidRequest();
        }
        JsonNode retryableNode = requireField(root, "retryable");
        if (!retryableNode.isBoolean()) {
            throw NewsControlException.invalidRequest();
        }
        return new NewsWireModels.Fail(generation, leaseToken, code, retryableNode.booleanValue());
    }

    public NewsWireModels.Lease parseLease(byte[] rawBody) {
        ObjectNode root = parseObject(rawBody, LEASE_KEYS);
        String taskId = requireIdentifier(root, "taskId");
        long generation = requirePositiveInt(root, "generation");
        String leaseToken = requireToken(root, "leaseToken");
        Instant leaseExpiresAt = requireUtcDateTime(root, "leaseExpiresAt");
        JsonNode windowNode = requireField(root, "window");
        if (!windowNode.isObject()) {
            throw NewsControlException.invalidRequest();
        }
        ObjectNode win = (ObjectNode) windowNode;
        checkAllowedKeys(win, WINDOW_KEYS);
        String source = requireString(win, "source", 1, 32);
        if (!NewsWireModels.SOURCES.contains(source)) {
            throw NewsControlException.invalidRequest();
        }
        long sourceConfigVersion = requirePositiveInt(win, "sourceConfigVersion");
        String query = requireNonBlankText(win, "query", 1, 1024);
        LocalDate startOn = requireIsoDate(win, "startOn");
        LocalDate endBefore = requireIsoDate(win, "endBefore");
        if (!startOn.isBefore(endBefore)) {
            throw NewsControlException.invalidRequest();
        }
        return new NewsWireModels.Lease(
                taskId,
                generation,
                leaseToken,
                leaseExpiresAt,
                new NewsWireModels.CollectionWindow(source, sourceConfigVersion, query.trim(), startOn, endBefore)
        );
    }

    public NewsWireModels.Material parseMaterial(byte[] rawBody) {
        ObjectNode root = parseObject(rawBody, MATERIAL_KEYS);
        return parseMaterialNode(root);
    }

    public NewsWireModels.Material parseBatchRecordCanonical(String canonicalJson) {
        try {
            JsonNode node = mapper.readTree(canonicalJson);
            return parseMaterialNode(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupt staged canonical material JSON", e);
        }
    }

    public String writeLease(NewsWireModels.Lease lease) {
        Map<String, Object> window = new LinkedHashMap<>();
        window.put("source", lease.window().source());
        window.put("sourceConfigVersion", lease.window().sourceConfigVersion());
        window.put("query", lease.window().query());
        window.put("startOn", lease.window().startOn().toString());
        window.put("endBefore", lease.window().endBefore().toString());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", lease.taskId());
        payload.put("generation", lease.generation());
        payload.put("leaseToken", lease.leaseToken());
        payload.put("leaseExpiresAt", formatUtcInstant(lease.leaseExpiresAt()));
        payload.put("window", window);
        return writeJson(payload);
    }

    public String writeBatchReceipt(NewsWireModels.BatchReceipt receipt) {
        return writeJson(receipt);
    }

    public String writeCompleteReceipt(NewsWireModels.CompleteReceipt receipt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("committedCursor", receipt.committedCursor().toString());
        return writeJson(payload);
    }

    public String writeFailureReceipt(NewsWireModels.FailureReceipt receipt) {
        return writeJson(receipt);
    }

    public String writeControlError(String code) {
        return writeJson(new NewsWireModels.ControlError(code));
    }

    private String writeJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize News Engine wire payload", e);
        }
    }

    private ObjectNode parseObject(byte[] rawBody, Set<String> allowedKeys) {
        if (rawBody == null || rawBody.length == 0 || rawBody.length > NewsWireModels.MAX_REQUEST_BYTES) {
            throw NewsControlException.invalidRequest();
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(rawBody))
                    .toString();
        } catch (CharacterCodingException e) {
            throw NewsControlException.invalidRequest();
        }
        JsonNode root;
        try {
            root = mapper.readTree(text);
        } catch (JsonProcessingException e) {
            throw NewsControlException.invalidRequest();
        }
        if (root == null || !root.isObject()) {
            throw NewsControlException.invalidRequest();
        }
        ObjectNode obj = (ObjectNode) root;
        checkAllowedKeys(obj, allowedKeys);
        return obj;
    }

    private NewsWireModels.Material parseMaterialNode(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw NewsControlException.invalidRequest();
        }
        ObjectNode obj = (ObjectNode) node;
        checkAllowedKeys(obj, MATERIAL_KEYS);

        String source = requireString(obj, "source", 1, 32);
        if (!NewsWireModels.SOURCES.contains(source)) {
            throw NewsControlException.invalidRequest();
        }
        String sourceRecordId = requireIdentifier(obj, "sourceRecordId");
        String url = requireHttpsUrl(obj, "url");
        String title = requireNonBlankText(obj, "title", 1, 2048);

        String language = optionalNonBlankText(obj, "language", 1, 80);
        String publishedOn = optionalPartialDate(obj, "publishedOn");
        LocalDate updatedOn = optionalIsoDate(obj, "updatedOn");
        String pmid = optionalPattern(obj, "pmid", PMID_PATTERN);
        String doi = optionalNonBlankText(obj, "doi", 1, 512);
        String abstractText = optionalNonBlankText(obj, "abstract", 1, 12_000);
        String abstractLicense = optionalString(obj, "abstractLicense", 1, 16);
        if (abstractLicense != null && !NewsWireModels.ABSTRACT_LICENSES.contains(abstractLicense)) {
            throw NewsControlException.invalidRequest();
        }
        if ((abstractText == null) != (abstractLicense == null)) {
            throw NewsControlException.invalidRequest();
        }

        NewsWireModels.TrialDetails trial = optionalTrial(obj.get("trial"));

        return new NewsWireModels.Material(
                source,
                sourceRecordId,
                url,
                title,
                language,
                publishedOn,
                updatedOn,
                pmid,
                doi,
                abstractText,
                abstractLicense,
                trial
        );
    }

    private NewsWireModels.TrialDetails optionalTrial(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            throw NewsControlException.invalidRequest();
        }
        ObjectNode obj = (ObjectNode) node;
        checkAllowedKeys(obj, TRIAL_KEYS);
        String studyType = optionalIdentifier(obj, "studyType");
        String overallStatus = optionalIdentifier(obj, "overallStatus");
        List<String> phases = optionalIdentifierArray(obj, "phases", 16);
        List<String> conditions = optionalTextArray(obj, "conditions", 100, 1, 2048);
        return new NewsWireModels.TrialDetails(studyType, overallStatus, phases, conditions);
    }

    private static void checkAllowedKeys(ObjectNode obj, Set<String> allowedKeys) {
        Iterator<String> names = obj.fieldNames();
        while (names.hasNext()) {
            if (!allowedKeys.contains(names.next())) {
                throw NewsControlException.invalidRequest();
            }
        }
    }

    private static JsonNode requireField(ObjectNode obj, String field) {
        JsonNode node = obj.get(field);
        if (node == null || node.isNull()) {
            throw NewsControlException.invalidRequest();
        }
        return node;
    }

    private static long requirePositiveInt(ObjectNode obj, String field) {
        return requireLongInRange(requireField(obj, field), 1L, NewsWireModels.MAX_SAFE_INT);
    }

    private static int requireIntInRange(ObjectNode obj, String field, int min, int max) {
        return (int) requireLongInRange(requireField(obj, field), min, max);
    }

    private static long requireLongInRange(JsonNode node, long min, long max) {
        if (!node.isIntegralNumber()) {
            throw NewsControlException.invalidRequest();
        }
        BigInteger big = node.bigIntegerValue();
        if (big.compareTo(BigInteger.valueOf(min)) < 0 || big.compareTo(BigInteger.valueOf(max)) > 0) {
            throw NewsControlException.invalidRequest();
        }
        return big.longValue();
    }

    private static String requireToken(ObjectNode obj, String field) {
        String value = requireString(obj, field, 16, 256);
        if (!TOKEN_PATTERN.matcher(value).matches()) {
            throw NewsControlException.invalidRequest();
        }
        return value;
    }

    private static String requireIdentifier(ObjectNode obj, String field) {
        String value = requireString(obj, field, 1, 128);
        if (!IDENTIFIER_PATTERN.matcher(value).matches()) {
            throw NewsControlException.invalidRequest();
        }
        return value;
    }

    private static String optionalIdentifier(ObjectNode obj, String field) {
        String value = optionalString(obj, field, 1, 128);
        if (value != null && !IDENTIFIER_PATTERN.matcher(value).matches()) {
            throw NewsControlException.invalidRequest();
        }
        return value;
    }

    private static String requireString(ObjectNode obj, String field, int minLen, int maxLen) {
        JsonNode node = requireField(obj, field);
        return checkStringNode(node, minLen, maxLen);
    }

    private static String optionalString(ObjectNode obj, String field, int minLen, int maxLen) {
        JsonNode node = obj.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        return checkStringNode(node, minLen, maxLen);
    }

    private static String requireNonBlankText(ObjectNode obj, String field, int minLen, int maxLen) {
        String value = requireString(obj, field, minLen, maxLen);
        if (value.isBlank()) {
            throw NewsControlException.invalidRequest();
        }
        return value;
    }

    private static String optionalNonBlankText(ObjectNode obj, String field, int minLen, int maxLen) {
        String value = optionalString(obj, field, minLen, maxLen);
        if (value != null && value.isBlank()) {
            throw NewsControlException.invalidRequest();
        }
        return value;
    }

    private static String optionalPattern(ObjectNode obj, String field, Pattern pattern) {
        String value = optionalString(obj, field, 1, 256);
        if (value != null && !pattern.matcher(value).matches()) {
            throw NewsControlException.invalidRequest();
        }
        return value;
    }

    private static String checkStringNode(JsonNode node, int minLen, int maxLen) {
        if (!node.isTextual()) {
            throw NewsControlException.invalidRequest();
        }
        String value = node.textValue();
        if (value.length() < minLen || value.length() > maxLen) {
            throw NewsControlException.invalidRequest();
        }
        return value;
    }

    private static String requireHttpsUrl(ObjectNode obj, String field) {
        String value = requireString(obj, field, 8, 512);
        if (!value.startsWith("https://")) {
            throw NewsControlException.invalidRequest();
        }
        try {
            URI uri = new URI(value);
            if (!"https".equals(uri.getScheme())
                    || uri.getHost() == null
                    || uri.getHost().isBlank()
                    || uri.getRawUserInfo() != null) {
                throw NewsControlException.invalidRequest();
            }
            return value;
        } catch (URISyntaxException e) {
            throw NewsControlException.invalidRequest();
        }
    }

    private static LocalDate requireIsoDate(ObjectNode obj, String field) {
        String value = requireString(obj, field, 10, 10);
        return parseStrictIsoDate(value);
    }

    private static LocalDate optionalIsoDate(ObjectNode obj, String field) {
        String value = optionalString(obj, field, 10, 10);
        return value == null ? null : parseStrictIsoDate(value);
    }

    private static LocalDate parseStrictIsoDate(String value) {
        if (!ISO_DATE_PATTERN.matcher(value).matches()) {
            throw NewsControlException.invalidRequest();
        }
        try {
            return LocalDate.parse(value, STRICT_ISO_DATE);
        } catch (DateTimeParseException e) {
            throw NewsControlException.invalidRequest();
        }
    }

    private static String optionalPartialDate(ObjectNode obj, String field) {
        String value = optionalString(obj, field, 4, 10);
        if (value == null) {
            return null;
        }
        if (!PARTIAL_DATE_PATTERN.matcher(value).matches()) {
            throw NewsControlException.invalidRequest();
        }
        String padded = switch (value.length()) {
            case 4 -> value + "-01-01";
            case 7 -> value + "-01";
            case 10 -> value;
            default -> throw NewsControlException.invalidRequest();
        };
        try {
            LocalDate.parse(padded, STRICT_ISO_DATE);
            return value;
        } catch (DateTimeParseException e) {
            throw NewsControlException.invalidRequest();
        }
    }

    private static List<String> optionalIdentifierArray(ObjectNode obj, String field, int maxItems) {
        JsonNode node = obj.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isArray() || node.size() > maxItems) {
            throw NewsControlException.invalidRequest();
        }
        List<String> values = new ArrayList<>(node.size());
        for (JsonNode item : node) {
            String value = checkStringNode(item, 1, 128);
            if (!IDENTIFIER_PATTERN.matcher(value).matches()) {
                throw NewsControlException.invalidRequest();
            }
            values.add(value);
        }
        return List.copyOf(values);
    }

    private static List<String> optionalTextArray(ObjectNode obj, String field, int maxItems, int minLen, int maxLen) {
        JsonNode node = obj.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isArray() || node.size() > maxItems) {
            throw NewsControlException.invalidRequest();
        }
        List<String> values = new ArrayList<>(node.size());
        for (JsonNode item : node) {
            values.add(checkStringNode(item, minLen, maxLen));
        }
        return List.copyOf(values);
    }

    private static Instant requireUtcDateTime(ObjectNode obj, String field) {
        String value = requireString(obj, field, 10, 64);
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException e) {
            throw NewsControlException.invalidRequest();
        }
    }

    private static String formatUtcInstant(Instant instant) {
        return DateTimeFormatter.ISO_INSTANT.format(instant);
    }
}
