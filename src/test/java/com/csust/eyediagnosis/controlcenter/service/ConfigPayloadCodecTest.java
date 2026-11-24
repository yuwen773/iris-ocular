package com.csust.eyediagnosis.controlcenter.service;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 载荷规范化与摘要：键序稳定是幂等与回滚溯源的前提。 */
class ConfigPayloadCodecTest {

    private final ConfigPayloadCodec codec = new ConfigPayloadCodec();

    @Test
    void canonicalOrderIsIndependentOfInsertionOrder() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("brand.siteName", "Iris");
        first.put("brand.shortName", "Iris");

        Map<String, Object> second = new LinkedHashMap<>();
        second.put("brand.shortName", "Iris");
        second.put("brand.siteName", "Iris");

        assertEquals(codec.canonical(first), codec.canonical(second));
        assertEquals("{\"brand.shortName\":\"Iris\",\"brand.siteName\":\"Iris\"}", codec.canonical(first));
    }

    @Test
    void digestIsStableAndSensitiveToValueChanges() {
        Map<String, Object> values = Map.of("brand.siteName", "Iris");
        String digest = codec.digest(codec.canonical(values));

        assertEquals(64, digest.length());
        assertEquals(digest, codec.digest(codec.canonical(Map.of("brand.siteName", "Iris"))));
        assertNotEquals(digest, codec.digest(codec.canonical(Map.of("brand.siteName", "Iris 2"))));
    }

    @Test
    void emptyPayloadHasStableRepresentation() {
        assertEquals("{}", codec.canonical(Map.of()));
        assertEquals("{}", codec.canonical(null));
        assertEquals("44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a",
                codec.digest("{}"), "空载荷摘要必须固定，可用于空版本比对");
    }

    @Test
    void parseRoundTripsAndRejectsNonObject() {
        Map<String, Object> values = codec.parse("{\"brand.siteName\":\"Iris\",\"brand.logo.light\":null}");
        assertEquals("Iris", values.get("brand.siteName"));
        assertEquals(null, values.get("brand.logo.light"));

        assertEquals(Map.of(), codec.parse(null));
        assertEquals(Map.of(), codec.parse("  "));
        assertThrows(IllegalArgumentException.class, () -> codec.parse("[1,2,3]"));
        assertThrows(IllegalArgumentException.class, () -> codec.parse("{not json"));
    }

    @Test
    void requestDigestChangesWithMethodPathOrBody() {
        Map<String, Object> body = Map.of("expectedRevision", 3);
        String base = codec.requestDigest("PUT", "/admin/control-center/drafts/1", body);

        assertNotEquals(base, codec.requestDigest("POST", "/admin/control-center/drafts/1", body));
        assertNotEquals(base, codec.requestDigest("PUT", "/admin/control-center/drafts/2", body));
        assertNotEquals(base, codec.requestDigest("PUT", "/admin/control-center/drafts/1",
                Map.of("expectedRevision", 4)));
        assertEquals(base, codec.requestDigest("put", "/admin/control-center/drafts/1", body),
                "method 大小写不应改变摘要");
    }

    @Test
    void overlayFillsMissingKeysFromDefaults() {
        Map<String, String> defaults = new LinkedHashMap<>();
        defaults.put("brand.siteName", "Iris");
        defaults.put("brand.logo.light", null);

        Map<String, Object> merged = codec.overlay(defaults, Map.of("brand.siteName", "Iris 2"));

        assertEquals("Iris 2", merged.get("brand.siteName"));
        assertEquals(null, merged.get("brand.logo.light"));
    }
}
