package com.csust.eyediagnosis.newsingestion;

import com.csust.eyediagnosis.config.JsonConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L1 wire contract and canonical-digest parity tests against {@code contracts/news/fixtures/v1*.json}.
 */
class NewsWireContractTest {

    private static final Path VALID_FIXTURES = Path.of("contracts/news/fixtures/v1.json");
    private static final Path INVALID_FIXTURES = Path.of("contracts/news/fixtures/v1.invalid.json");

    // Golden SHA-256 digests computed directly by Python iris_news_worker.contracts.Material.content_digest().
    private static final String PYTHON_FIXTURE_SHA256 =
            "6d62204768044319ff44bd58a7a0280a80e91417a6c4e28996416f3af97a24e9";
    private static final String PYTHON_RICH_MATERIAL_SHA256 =
            "e327bf40312cb8f1a2af206aca85437fe9dd488ca5ccfc9eb819a684f5aac492";

    private final ObjectMapper protocolMapper = JsonConfig.createNewsProtocolObjectMapper();
    private final NewsWireCodec codec = new NewsWireCodec(protocolMapper);

    @Test
    void newsProtocolMapperPreservesJsonIntegersWhileGlobalMapperStringifiesLongs() throws Exception {
        ObjectMapper globalMapper = new JsonConfig().jacksonObjectMapper(new Jackson2ObjectMapperBuilder());
        JsonNode globalTree = globalMapper.readTree(globalMapper.writeValueAsString(Map.of("generation", 42L)));
        assertTrue(globalTree.get("generation").isTextual(), "Global mapper stringifies Long for JS frontends");

        NewsWireModels.Lease lease = new NewsWireModels.Lease(
                "task-001",
                42L,
                "fixture_lease_token_01",
                Instant.parse("2030-01-02T12:00:00Z"),
                new NewsWireModels.CollectionWindow(
                        "pubmed", 7L, "glaucoma", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 15))
        );
        String json = codec.writeLease(lease);
        JsonNode tree = protocolMapper.readTree(json);
        assertTrue(tree.get("generation").isIntegralNumber(), "generation must be JSON integer: " + json);
        assertEquals(42L, tree.get("generation").longValue());
        assertTrue(tree.get("window").get("sourceConfigVersion").isIntegralNumber(),
                "sourceConfigVersion must be JSON integer: " + json);
        assertEquals(7L, tree.get("window").get("sourceConfigVersion").longValue());
        assertTrue(tree.get("leaseExpiresAt").textValue().endsWith("Z"),
                "leaseExpiresAt must serialize in UTC Z format: " + json);
    }

    @Test
    void roundTripsAllValidFixturesFromContractSuite() throws Exception {
        JsonNode fixtures = protocolMapper.readTree(Files.readString(VALID_FIXTURES, StandardCharsets.UTF_8));

        NewsWireModels.Lease lease = codec.parseLease(protocolMapper.writeValueAsBytes(fixtures.get("Lease")));
        assertEquals("task-001", lease.taskId());
        assertEquals(1L, lease.generation());
        assertEquals("clinicaltrials-gov", lease.window().source());
        assertEquals(LocalDate.of(2026, 10, 1), lease.window().startOn());
        assertEquals(LocalDate.of(2026, 10, 15), lease.window().endBefore());
        JsonNode serializedLease = protocolMapper.readTree(codec.writeLease(lease));
        assertEquals(fixtures.get("Lease"), serializedLease);

        NewsWireModels.Batch batch = codec.parseBatch(protocolMapper.writeValueAsBytes(fixtures.get("Batch")));
        assertEquals(1L, batch.generation());
        assertEquals(1, batch.sequence());
        assertEquals(1, batch.records().size());
        NewsWireModels.Material record = batch.records().getFirst();
        assertEquals("NCT00000001", record.sourceRecordId());
        assertEquals("2026-09", record.publishedOn());
        assertEquals(LocalDate.of(2026, 10, 9), record.updatedOn());
        assertNotNull(record.trial());
        assertEquals(List.of("PHASE2"), record.trial().phases());

        JsonNode batchReceipt = protocolMapper.readTree(
                codec.writeBatchReceipt(new NewsWireModels.BatchReceipt(1, 1)));
        assertEquals(fixtures.get("BatchReceipt"), batchReceipt);
        assertTrue(batchReceipt.get("sequence").isIntegralNumber());
        assertTrue(batchReceipt.get("recordCount").isIntegralNumber());

        NewsWireModels.Complete complete = codec.parseComplete(
                protocolMapper.writeValueAsBytes(fixtures.get("Complete")));
        assertEquals(1, complete.batchCount());
        assertEquals(1, complete.recordCount());
        assertEquals(LocalDate.of(2026, 10, 15), complete.nextCursor());
        JsonNode completeReceipt = protocolMapper.readTree(
                codec.writeCompleteReceipt(new NewsWireModels.CompleteReceipt(complete.nextCursor())));
        assertEquals(fixtures.get("CompleteReceipt"), completeReceipt);

        NewsWireModels.Fail fail = codec.parseFail(protocolMapper.writeValueAsBytes(fixtures.get("Fail")));
        assertEquals(NewsWireModels.FailureCode.BAD_SOURCE_DATA, fail.code());
        assertFalse(fail.retryable());
        JsonNode failureReceipt = protocolMapper.readTree(
                codec.writeFailureReceipt(new NewsWireModels.FailureReceipt("FAILED")));
        assertEquals(fixtures.get("FailureReceipt"), failureReceipt);

        JsonNode controlError = protocolMapper.readTree(codec.writeControlError("STALE_LEASE"));
        assertEquals(fixtures.get("ControlError"), controlError);
    }

    @Test
    void rejectsEveryNegativeCaseInContractInvalidFixtures() throws Exception {
        JsonNode valid = protocolMapper.readTree(Files.readString(VALID_FIXTURES, StandardCharsets.UTF_8));
        JsonNode invalids = protocolMapper.readTree(Files.readString(INVALID_FIXTURES, StandardCharsets.UTF_8));
        assertTrue(invalids.isArray() && invalids.size() >= 5, "Expected all contract negative fixtures");

        for (JsonNode testCase : invalids) {
            String name = testCase.get("name").textValue();
            String model = testCase.get("model").textValue();
            ObjectNode patch = (ObjectNode) testCase.get("patch");

            ObjectNode base = switch (model) {
                case "Lease" -> valid.get("Lease").deepCopy();
                case "Complete" -> valid.get("Complete").deepCopy();
                case "Material" -> valid.get("Batch").get("records").get(0).deepCopy();
                default -> throw new IllegalStateException("Unexpected fixture model: " + model);
            };
            patch.fields().forEachRemaining(entry -> base.set(entry.getKey(), entry.getValue()));
            byte[] payload = protocolMapper.writeValueAsBytes(base);

            NewsControlException ex = assertThrows(NewsControlException.class, () -> {
                switch (model) {
                    case "Lease" -> codec.parseLease(payload);
                    case "Complete" -> codec.parseComplete(payload);
                    case "Material" -> codec.parseMaterial(payload);
                    default -> throw new IllegalStateException(model);
                }
            }, "Negative fixture must fail validation: " + name);
            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus(), "Wrong status for " + name);
            assertEquals("INVALID_REQUEST", ex.getCode(), "Wrong code for " + name);
        }
    }

    @Test
    void rejectsStrictProtocolViolationsWithoutTypeCoercion() {
        // Extra unknown property
        assertInvalidRequest(() -> codec.parseComplete(
                """
                {"generation":1,"leaseToken":"fixture_lease_token_01","batchCount":0,"recordCount":0,"nextCursor":"2026-10-15","extra":true}
                """.getBytes(StandardCharsets.UTF_8)));

        // Duplicate JSON key
        assertInvalidRequest(() -> codec.parseComplete(
                """
                {"generation":1,"generation":2,"leaseToken":"fixture_lease_token_01","batchCount":0,"recordCount":0,"nextCursor":"2026-10-15"}
                """.getBytes(StandardCharsets.UTF_8)));

        // Trailing JSON tokens
        assertInvalidRequest(() -> codec.parseComplete(
                """
                {"generation":1,"leaseToken":"fixture_lease_token_01","batchCount":0,"recordCount":0,"nextCursor":"2026-10-15"}{"extra":1}
                """.getBytes(StandardCharsets.UTF_8)));

        // Stringified integer for generation
        assertInvalidRequest(() -> codec.parseComplete(
                """
                {"generation":"1","leaseToken":"fixture_lease_token_01","batchCount":0,"recordCount":0,"nextCursor":"2026-10-15"}
                """.getBytes(StandardCharsets.UTF_8)));

        // Float for integer generation
        assertInvalidRequest(() -> codec.parseComplete(
                """
                {"generation":1.0,"leaseToken":"fixture_lease_token_01","batchCount":0,"recordCount":0,"nextCursor":"2026-10-15"}
                """.getBytes(StandardCharsets.UTF_8)));

        // Boolean for integer generation
        assertInvalidRequest(() -> codec.parseComplete(
                """
                {"generation":true,"leaseToken":"fixture_lease_token_01","batchCount":0,"recordCount":0,"nextCursor":"2026-10-15"}
                """.getBytes(StandardCharsets.UTF_8)));

        // Impossible calendar dates
        assertInvalidRequest(() -> codec.parseComplete(
                """
                {"generation":1,"leaseToken":"fixture_lease_token_01","batchCount":0,"recordCount":0,"nextCursor":"2026-02-30"}
                """.getBytes(StandardCharsets.UTF_8)));
        assertInvalidRequest(() -> codec.parseMaterial(
                """
                {"source":"pubmed","sourceRecordId":"PMID1","url":"https://pubmed.ncbi.nlm.nih.gov/1/","title":"Ok","publishedOn":"2026-02-30"}
                """.getBytes(StandardCharsets.UTF_8)));

        // Credentialed URL & non-HTTPS URL
        assertInvalidRequest(() -> codec.parseMaterial(
                """
                {"source":"pubmed","sourceRecordId":"PMID1","url":"https://user:pass@pubmed.ncbi.nlm.nih.gov/1/","title":"Ok"}
                """.getBytes(StandardCharsets.UTF_8)));
        assertInvalidRequest(() -> codec.parseMaterial(
                """
                {"source":"pubmed","sourceRecordId":"PMID1","url":"http://pubmed.ncbi.nlm.nih.gov/1/","title":"Ok"}
                """.getBytes(StandardCharsets.UTF_8)));

        // Blank text & unpaired abstract/license
        assertInvalidRequest(() -> codec.parseMaterial(
                """
                {"source":"pubmed","sourceRecordId":"PMID1","url":"https://pubmed.ncbi.nlm.nih.gov/1/","title":"   "}
                """.getBytes(StandardCharsets.UTF_8)));
        assertInvalidRequest(() -> codec.parseMaterial(
                """
                {"source":"pubmed","sourceRecordId":"PMID1","url":"https://pubmed.ncbi.nlm.nih.gov/1/","title":"Ok","abstractLicense":"CC-BY"}
                """.getBytes(StandardCharsets.UTF_8)));

        // Empty batch records
        assertInvalidRequest(() -> codec.parseBatch(
                """
                {"generation":1,"leaseToken":"fixture_lease_token_01","sequence":1,"records":[]}
                """.getBytes(StandardCharsets.UTF_8)));

        // Oversized payload > 2 MiB and malformed UTF-8
        assertInvalidRequest(() -> codec.parseComplete(new byte[NewsWireModels.MAX_REQUEST_BYTES + 1]));
        assertInvalidRequest(() -> codec.parseComplete(new byte[]{(byte) 0xFF, (byte) 0xFE, (byte) 0xFD}));
    }

    @Test
    void canonicalMaterialDigestMatchesPythonGoldenSha256BitForBit() throws Exception {
        JsonNode fixtures = protocolMapper.readTree(Files.readString(VALID_FIXTURES, StandardCharsets.UTF_8));
        NewsWireModels.Material fixtureMaterial = codec.parseMaterial(
                protocolMapper.writeValueAsBytes(fixtures.get("Batch").get("records").get(0)));

        String fixtureCanonical = NewsMaterialCanonicalizer.canonicalMaterialJson(fixtureMaterial);
        assertEquals(
                "{\"publishedOn\":\"2026-09\",\"source\":\"clinicaltrials-gov\",\"sourceRecordId\":\"NCT00000001\","
                        + "\"title\":\"Synthetic glaucoma example\","
                        + "\"trial\":{\"conditions\":[\"Glaucoma\"],\"overallStatus\":\"RECRUITING\","
                        + "\"phases\":[\"PHASE2\"],\"studyType\":\"INTERVENTIONAL\"},"
                        + "\"updatedOn\":\"2026-10-09\",\"url\":\"https://clinicaltrials.gov/study/NCT00000001\"}",
                fixtureCanonical);
        assertEquals(PYTHON_FIXTURE_SHA256, NewsMaterialCanonicalizer.contentDigest(fixtureMaterial));

        NewsWireModels.Material richMaterial = new NewsWireModels.Material(
                "clinicaltrials-gov",
                "NCT09998888",
                "https://clinicaltrials.gov/study/NCT09998888?tab=results&view=1",
                "眼科 AI 临床试验 — Phase II \"Alpha\"\nline2\t\\slash\u0007",
                "zh",
                "2026-04-15",
                LocalDate.of(2026, 4, 20),
                "12345678",
                "10.1000/eye.2026.01",
                "Open-access summary with Unicode 视网膜病变 and quotes \"CC-BY\".",
                "CC-BY",
                new NewsWireModels.TrialDetails(
                        "INTERVENTIONAL",
                        "RECRUITING",
                        List.of("PHASE2", "PHASE3"),
                        List.of("Diabetic Retinopathy", "视网膜病变")
                )
        );
        assertEquals(PYTHON_RICH_MATERIAL_SHA256, NewsMaterialCanonicalizer.contentDigest(richMaterial));
    }

    private static void assertInvalidRequest(Runnable action) {
        NewsControlException ex = assertThrows(NewsControlException.class, action::run);
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertEquals("INVALID_REQUEST", ex.getCode());
    }
}
