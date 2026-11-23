package com.csust.eyediagnosis.agent.service;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 图片引用（后端签发、算法侧校验）的签名契约。
 *
 * <p>与 {@code algorithm/mcp_server/tests/test_image_ref.py} 共用同一组测试向量：
 * 两侧签名格式一旦不一致，算法侧就会验不过——这里必须先红。
 */
class ImageReferenceSignerTest {

    private static ImageReferenceSigner signer(String secret) {
        ImageReferenceSigner signer = new ImageReferenceSigner();
        ReflectionTestUtils.setField(signer, "secret", secret);
        ReflectionTestUtils.setField(signer, "ttlSeconds", 300);
        return signer;
    }

    @Test
    void signatureMatchesTheSharedTestVector() {
        // 与算法侧 test_image_ref.py 的向量一致：payload 是目标地址的 base64url（无填充）。
        String payload = "aHR0cHM6Ly9leGFtcGxlLmNvbS9hLnBuZw";
        long expiresAt = 1700000000L;
        String expected = "476df5cf1cc2911083dbfd00268be413cedae2b9ccae296a0c8f293720757c5d";

        assertEquals(expected, signer("test-secret").signPayload(payload, expiresAt));
    }

    @Test
    void signedReferenceCarriesPayloadExpiryAndSignature() {
        String reference = signer("s3cret").sign("https://example.com/a.png");

        assertTrue(reference.startsWith(ImageReferenceSigner.SCHEME));
        String body = reference.substring(ImageReferenceSigner.SCHEME.length());
        String[] parts = body.split("\\?", 2);
        assertEquals(2, parts.length);
        assertFalse(parts[0].contains("="), "载荷是 base64url 且不带填充");
        assertTrue(parts[1].contains("exp="));
        assertTrue(parts[1].contains("sig="));
    }

    @Test
    void missingSecretFailsInsteadOfIssuingUnverifiableReferences() {
        ImageReferenceSigner signer = signer("");
        assertFalse(signer.enabled());
        assertThrows(IllegalStateException.class, () -> signer.sign("https://example.com/a.png"));
    }

    @Test
    void nonHttpTargetIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> signer("s3cret").sign("file:///etc/passwd"));
    }
}
