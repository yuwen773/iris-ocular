package com.csust.eyediagnosis.diagnosistask;

import com.csust.eyediagnosis.grpc.DiagnosisProto;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InferenceSnapshotTest {

    private static final InferenceSnapshot.Identity IDENTITY = new InferenceSnapshot.Identity(
            "task-1", 42L, "diagnosis-task/task-1.png", "v1", "a".repeat(64), "png");

    private static DiagnosisProto.DiagnosisResponse response() {
        return DiagnosisProto.DiagnosisResponse.newBuilder()
                .setPredictedLabel("amd")
                .setProbability(72.5)
                .setRejected(false)
                .setModelVersion("efficientnet-b4@1.0.0")
                .setPreprocessVersion("v1")
                .build();
    }

    private static Map<String, Object> row() {
        Map<String, Object> task = new HashMap<>();
        task.put("taskId", "task-1");
        task.put("userId", 42L);
        task.put("objectKey", "diagnosis-task/task-1.png");
        task.put("objectVersion", "v1");
        task.put("imageDigest", "a".repeat(64));
        task.put("imageFormat", "png");
        return task;
    }

    @Test
    void writeThenParseRoundTripsTheResponseAndIdentity() {
        String raw = InferenceSnapshot.write(IDENTITY, response());
        InferenceSnapshot snapshot = InferenceSnapshot.parse(raw);

        assertEquals("amd", snapshot.response().getPredictedLabel());
        assertEquals(72.5, snapshot.response().getProbability());
        assertTrue(snapshot.matches(row()));
        assertTrue(snapshot.matches(IDENTITY));
        assertEquals(raw, snapshot.raw());
        assertEquals(InferenceSnapshot.digest(raw), snapshot.digest());
    }

    @Test
    void writeIsDeterministicSoDigestsAreStable() {
        String first = InferenceSnapshot.write(IDENTITY, response());
        String second = InferenceSnapshot.write(IDENTITY, response());
        assertEquals(first, second);
        assertEquals(InferenceSnapshot.digest(first), InferenceSnapshot.digest(second));
        assertTrue(first.contains("\"responseProtobufBase64\""));
    }

    @Test
    void matchesRejectsEachIdentityMismatchAndMissingField() {
        InferenceSnapshot snapshot = InferenceSnapshot.parse(InferenceSnapshot.write(IDENTITY, response()));

        for (String field : InferenceSnapshot.IDENTITY_FIELDS) {
            Map<String, Object> wrong = row();
            wrong.put(field, "something-else");
            assertFalse(snapshot.matches(wrong), field + " mismatch must not match");

            Map<String, Object> missing = row();
            missing.remove(field);
            assertFalse(snapshot.matches(missing), "missing " + field + " must not match");
        }
        assertFalse(snapshot.matches((Map<String, Object>) null));
        assertFalse(snapshot.matches((InferenceSnapshot.Identity) null));
    }

    @Test
    void matchesComparesNumericUserIdByItsPrintedValue() {
        String raw = InferenceSnapshot.write(IDENTITY, response());
        InferenceSnapshot snapshot = InferenceSnapshot.parse(raw);

        Map<String, Object> asInteger = row();
        asInteger.put("userId", 42);
        assertTrue(snapshot.matches(asInteger));

        Map<String, Object> asString = row();
        asString.put("userId", "42");
        assertTrue(snapshot.matches(asString));
    }

    @Test
    void parseRejectsUnreadableEnvelopes() {
        assertThrows(InferenceSnapshot.InvalidSnapshotException.class, () -> InferenceSnapshot.parse(null));
        assertThrows(InferenceSnapshot.InvalidSnapshotException.class, () -> InferenceSnapshot.parse(""));
        assertThrows(InferenceSnapshot.InvalidSnapshotException.class, () -> InferenceSnapshot.parse("not json"));
        assertThrows(InferenceSnapshot.InvalidSnapshotException.class, () -> InferenceSnapshot.parse("[]"));
        assertThrows(InferenceSnapshot.InvalidSnapshotException.class, () -> InferenceSnapshot.parse("{}"));
        assertThrows(InferenceSnapshot.InvalidSnapshotException.class,
                () -> InferenceSnapshot.parse("{\"responseProtobufBase64\":\"%%%not-base64%%%\"}"));
        assertThrows(InferenceSnapshot.InvalidSnapshotException.class,
                () -> InferenceSnapshot.parse("{\"responseProtobufBase64\":\"" + Base64.getEncoder().encodeToString(new byte[]{1, 2, 3}) + "\"}"));
    }

    @Test
    void invalidSnapshotExceptionStaysAnIllegalArgumentForExistingCatchClauses() {
        assertTrue(IllegalArgumentException.class.isAssignableFrom(InferenceSnapshot.InvalidSnapshotException.class));
    }

    @Test
    void parseStillDecodesPayloadOnlySnapshotsThatOlderCallersTolerated() {
        // 解释 / 测试播种路径此前只解码 payload、不核对身份；parse 不替它们收紧。
        String payloadOnly = "{\"responseProtobufBase64\":\""
                + Base64.getEncoder().encodeToString(response().toByteArray()) + "\"}";
        InferenceSnapshot parsed = InferenceSnapshot.parse(payloadOnly);
        assertEquals("amd", parsed.response().getPredictedLabel());
        assertNull(parsed.identity("taskId"));
        assertFalse(parsed.matches(row()));
    }

    @Test
    void writeRequiresCompleteIdentity() {
        assertThrows(IllegalArgumentException.class, () -> new InferenceSnapshot.Identity(null, 1, "k", "v1", "d", "png"));
        assertThrows(IllegalArgumentException.class, () -> new InferenceSnapshot.Identity("t", 1, "", "v1", "d", "png"));
        assertThrows(IllegalArgumentException.class, () -> new InferenceSnapshot.Identity("t", 1, "k", null, "d", "png"));
        assertThrows(IllegalArgumentException.class, () -> new InferenceSnapshot.Identity("t", 1, "k", "v1", " ", "png"));
        assertThrows(IllegalArgumentException.class, () -> new InferenceSnapshot.Identity("t", 1, "k", "v1", "d", ""));
    }

    @Test
    void identityFieldsAreReadWithoutLeakingJsonKeysToCallers() {
        InferenceSnapshot snapshot = InferenceSnapshot.parse(InferenceSnapshot.write(IDENTITY, response()));

        assertEquals("task-1", snapshot.identity("taskId"));
        assertEquals("42", snapshot.identity("userId"));
        assertEquals("diagnosis-task/task-1.png", snapshot.identity("objectKey"));
        assertEquals("v1", snapshot.identity("objectVersion"));
        assertEquals("a".repeat(64), snapshot.identity("imageDigest"));
        assertEquals("png", snapshot.identity("imageFormat"));
        assertThrows(IllegalArgumentException.class, () -> snapshot.identity("responseProtobufBase64"));
    }

    @Test
    void digestPinsTheSha256HexContractSharedWithDoctorReviewAndCollaboration() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                InferenceSnapshot.digest("abc"));
    }
}
