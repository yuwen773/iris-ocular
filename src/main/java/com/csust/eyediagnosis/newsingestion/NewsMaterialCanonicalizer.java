package com.csust.eyediagnosis.newsingestion;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * Deterministic canonical JSON projector matching Python {@code Material.content_digest()}.
 *
 * <p>Null optional fields are omitted recursively, object keys are emitted in strict ASCII
 * lexicographical order, separators have no extra whitespace, and non-ASCII characters are
 * written directly as UTF-8 (matching {@code json.dumps(..., sort_keys=True, separators=(",", ":"),
 * ensure_ascii=False)}).
 */
public final class NewsMaterialCanonicalizer {

    private NewsMaterialCanonicalizer() {
    }

    public static String canonicalMaterialJson(NewsWireModels.Material m) {
        StringBuilder sb = new StringBuilder(512);
        sb.append('{');
        boolean first = true;
        // Exact ASCII lexicographical key order of Material wire fields:
        // abstract, abstractLicense, doi, language, pmid, publishedOn,
        // source, sourceRecordId, title, trial, updatedOn, url
        first = appendStringField(sb, "abstract", m.abstractText(), first);
        first = appendStringField(sb, "abstractLicense", m.abstractLicense(), first);
        first = appendStringField(sb, "doi", m.doi(), first);
        first = appendStringField(sb, "language", m.language(), first);
        first = appendStringField(sb, "pmid", m.pmid(), first);
        first = appendStringField(sb, "publishedOn", m.publishedOn(), first);
        first = appendStringField(sb, "source", m.source(), first);
        first = appendStringField(sb, "sourceRecordId", m.sourceRecordId(), first);
        first = appendStringField(sb, "title", m.title(), first);
        if (m.trial() != null) {
            first = appendKey(sb, "trial", first);
            appendTrialJson(sb, m.trial());
        }
        if (m.updatedOn() != null) {
            first = appendStringField(sb, "updatedOn", m.updatedOn().toString(), first);
        }
        appendStringField(sb, "url", m.url(), first);
        sb.append('}');
        return sb.toString();
    }

    public static String canonicalTrialJson(NewsWireModels.TrialDetails trial) {
        if (trial == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(128);
        appendTrialJson(sb, trial);
        return sb.toString();
    }

    public static String contentDigest(NewsWireModels.Material material) {
        return sha256Hex(canonicalMaterialJson(material).getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static void appendTrialJson(StringBuilder sb, NewsWireModels.TrialDetails t) {
        sb.append('{');
        boolean first = true;
        // Exact ASCII lexicographical key order of TrialDetails wire fields:
        // conditions, overallStatus, phases, studyType
        if (t.conditions() != null) {
            first = appendKey(sb, "conditions", first);
            appendStringArray(sb, t.conditions());
        }
        first = appendStringField(sb, "overallStatus", t.overallStatus(), first);
        if (t.phases() != null) {
            first = appendKey(sb, "phases", first);
            appendStringArray(sb, t.phases());
        }
        appendStringField(sb, "studyType", t.studyType(), first);
        sb.append('}');
    }

    private static void appendStringArray(StringBuilder sb, List<String> items) {
        sb.append('[');
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            appendJsonString(sb, items.get(i));
        }
        sb.append(']');
    }

    private static boolean appendStringField(StringBuilder sb, String key, String value, boolean first) {
        if (value == null) {
            return first;
        }
        boolean nextFirst = appendKey(sb, key, first);
        appendJsonString(sb, value);
        return nextFirst;
    }

    private static boolean appendKey(StringBuilder sb, String key, boolean first) {
        if (!first) {
            sb.append(',');
        }
        appendJsonString(sb, key);
        sb.append(':');
        return false;
    }

    /**
     * Escapes strings identically to Python {@code json.dumps(..., ensure_ascii=False)}.
     */
    private static void appendJsonString(StringBuilder sb, String value) {
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
