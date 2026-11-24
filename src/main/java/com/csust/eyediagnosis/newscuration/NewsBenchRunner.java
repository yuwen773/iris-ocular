package com.csust.eyediagnosis.newscuration;

import com.csust.eyediagnosis.newsingestion.NewsIngestionProperties;
import com.csust.eyediagnosis.newsingestion.NewsIngestionService;
import com.csust.eyediagnosis.newsingestion.NewsMaterialCanonicalizer;
import com.csust.eyediagnosis.newsingestion.NewsWireCodec;
import com.csust.eyediagnosis.newsingestion.NewsWireModels;
import com.csust.eyediagnosis.newsingestion.NewsWorkerIdentity;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Deterministic offline (and authorized live) NewsBench harness validating material triage,
 * verbatim grounding, license compliance, and prompt-injection resistance.
 */
@Component
public class NewsBenchRunner {

    private final JdbcTemplate jdbc;
    private final NewsWireCodec wireCodec;
    private final NewsEvidenceVerifier verifier;
    private final NewsCurationPromptBuilder promptBuilder;
    private final ObjectMapper mapper;

    @Autowired
    public NewsBenchRunner(JdbcTemplate jdbc,
                           NewsWireCodec wireCodec,
                           NewsEvidenceVerifier verifier,
                           NewsCurationPromptBuilder promptBuilder,
                           @Qualifier("newsProtocolObjectMapper") ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.wireCodec = wireCodec;
        this.verifier = verifier;
        this.promptBuilder = promptBuilder;
        this.mapper = mapper;
    }

    public static NewsBenchRunner offline(NewsCurationProperties properties, ObjectMapper mapper) {
        return new NewsBenchRunner(
                null,
                new NewsWireCodec(mapper),
                new NewsEvidenceVerifier(properties, mapper),
                new NewsCurationPromptBuilder(properties, mapper),
                mapper
        );
    }

    public NewsCurationModels.NewsBenchReport runOfflineFixture(Path datasetPath, String policyVersion) {
        DatasetSpec spec = loadAndValidateDataset(datasetPath);
        List<EvaluatedCase> evaluated = new ArrayList<>(spec.cases.size());

        for (DatasetCase c : spec.cases) {
            NewsIngestionService.VisibleRevision syntheticRev = new NewsIngestionService.VisibleRevision(
                    1L,
                    1L,
                    c.material.source(),
                    c.material.sourceRecordId(),
                    NewsMaterialCanonicalizer.contentDigest(c.material),
                    c.material.url(),
                    c.material.title(),
                    c.material.language(),
                    c.material.publishedOn(),
                    c.material.updatedOn(),
                    c.material.pmid(),
                    c.material.doi(),
                    c.material.abstractText(),
                    c.material.abstractLicense(),
                    NewsMaterialCanonicalizer.canonicalTrialJson(c.material.trial()),
                    NewsMaterialCanonicalizer.canonicalMaterialJson(c.material),
                    "newsbench-offline",
                    1L,
                    null
            );
            NewsCurationModels.EvidenceSnapshot snapshot = promptBuilder.buildSnapshot(syntheticRev);
            NewsCurationModels.VerificationResult verification = verifier.parseAndVerify(c.modelOutputJson, snapshot);
            evaluated.add(evaluateCase(c, verification));
        }
        return aggregateReport(spec, policyVersion, "OFFLINE_FIXTURE", evaluated, 0, 0L, 0L);
    }

    public NewsCurationModels.NewsBenchReport runEndToEndWithMySql(
            Path datasetPath,
            String policyVersion,
            NewsIngestionService ingestionService,
            NewsCurationService curationService,
            FixtureModelClientRegistry clientRegistry,
            LocalDate budgetDate,
            String budgetScope) {
        Objects.requireNonNull(jdbc, "JdbcTemplate is required for MySQL-backed NewsBench run");
        DatasetSpec spec = loadAndValidateDataset(datasetPath);
        List<EvaluatedCase> evaluated = new ArrayList<>(spec.cases.size());

        NewsWorkerIdentity benchWorker = new NewsWorkerIdentity("newsbench-worker", NewsIngestionProperties.ALL_SOURCES);
        long revOffset = 0;
        for (DatasetCase c : spec.cases) {
            revOffset++;
            String source = c.material.source();
            long configVer = 9000L + revOffset;
            String taskId = "bench-task-" + spec.datasetVersion + "-" + revOffset;
            LocalDate start = LocalDate.of(2026, 10, 1);
            LocalDate end = LocalDate.of(2026, 10, 2);

            ingestionService.enqueueWindow(taskId, source, configVer, "newsbench", start, end, 3);
            NewsWireModels.Lease lease = ingestionService.claim(benchWorker)
                    .orElseThrow(() -> new IllegalStateException("Failed to claim bench task: " + taskId));
            byte[] batchBody = buildBatchPayload(lease.generation(), lease.leaseToken(), 1, c.materialNode);
            ingestionService.submitBatch(benchWorker, lease.taskId(), lease.generation() + ":1", batchBody);
            byte[] completeBody = buildCompletePayload(lease.generation(), lease.leaseToken(), 1, 1, end);
            ingestionService.complete(benchWorker, lease.taskId(), lease.generation() + ":complete", completeBody);

            List<NewsIngestionService.VisibleRevision> revs =
                    ingestionService.listVisibleRevisions(source, c.material.sourceRecordId());
            NewsIngestionService.VisibleRevision rev = revs.getLast();

            clientRegistry.registerNext(c.modelOutputJson, 600, 220, 4000L);
            NewsCurationModels.CurationResult result = curationService.curateRevision(
                    rev.revisionId(), policyVersion, budgetDate, budgetScope);

            evaluated.add(evaluateCase(c, new NewsCurationModels.VerificationResult(
                    result.violations().isEmpty(),
                    result.status(),
                    null,
                    result.violations()
            )));
        }

        NewsCurationModels.BudgetSnapshot budget = curationService.getBudget(budgetDate, budgetScope).orElse(null);
        int calls = budget == null ? 0 : budget.usedCalls();
        long tokens = budget == null ? 0L : budget.usedTokens();
        long cost = budget == null ? 0L : budget.usedCostMicros();

        NewsCurationModels.NewsBenchReport report = aggregateReport(
                spec, policyVersion, "OFFLINE_FIXTURE", evaluated, calls, tokens, cost);
        persistReport(report);
        return report;
    }

    private void persistReport(NewsCurationModels.NewsBenchReport report) {
        try {
            String reportJson = mapper.writeValueAsString(report);
            jdbc.update("""
                    INSERT INTO t_news_bench_run
                        (runId, datasetVersion, datasetSha256, policyVersion, mode,
                         totalCases, passedCases, precisionBps, recallBps,
                         evidenceCatchBps, licenseComplianceBps, injectionCatchBps,
                         totalCalls, totalTokens, totalCostMicros, gatePassed, reportJson)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    report.runId(), report.datasetVersion(), report.datasetSha256(),
                    report.policyVersion(), report.mode(), report.totalCases(), report.passedCases(),
                    report.precisionBps(), report.recallBps(), report.evidenceCatchBps(),
                    report.licenseComplianceBps(), report.injectionCatchBps(),
                    report.totalCalls(), report.totalTokens(), report.totalCostMicros(),
                    report.gatePassed() ? 1 : 0, reportJson);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize NewsBench report", e);
        }
    }

    private DatasetSpec loadAndValidateDataset(Path datasetPath) {
        try {
            byte[] raw = Files.readAllBytes(datasetPath);
            String sha = NewsMaterialCanonicalizer.sha256Hex(raw);
            JsonNode root = mapper.readTree(raw);
            String version = root.get("datasetVersion").textValue();
            List<DatasetCase> cases = new ArrayList<>();
            for (JsonNode node : root.get("cases")) {
                String caseId = node.get("caseId").textValue();
                String kind = node.get("kind").textValue();
                String expectedStatus = node.get("expectedStatus").textValue();
                String expectedViolationCode = node.hasNonNull("expectedViolationCode")
                        ? node.get("expectedViolationCode").textValue() : null;
                JsonNode materialNode = node.get("material");
                NewsWireModels.Material material = wireCodec.parseMaterial(mapper.writeValueAsBytes(materialNode));
                JsonNode outputNode = node.get("modelOutput");
                String modelOutputJson = outputNode != null && outputNode.isTextual()
                        ? outputNode.textValue()
                        : mapper.writeValueAsString(outputNode);
                cases.add(new DatasetCase(
                        caseId, kind, expectedStatus, expectedViolationCode, materialNode, material, modelOutputJson));
            }
            return new DatasetSpec(version, sha, List.copyOf(cases));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load NewsBench dataset: " + datasetPath, e);
        }
    }

    private EvaluatedCase evaluateCase(DatasetCase c, NewsCurationModels.VerificationResult actual) {
        String actualStatus = actual.effectiveStatus().name();
        boolean statusMatches = c.expectedStatus.equals(actualStatus);
        boolean violationMatches = true;
        if (c.expectedViolationCode != null) {
            violationMatches = actual.violations().stream()
                    .anyMatch(v -> c.expectedViolationCode.equals(v.code()));
        }
        boolean passed = statusMatches && violationMatches;
        String detail = passed ? "ok" : ("expectedStatus=" + c.expectedStatus + ", actualStatus=" + actualStatus
                + ", expectedViolation=" + c.expectedViolationCode + ", violations=" + actual.violations());
        return new EvaluatedCase(
                c,
                new NewsCurationModels.NewsBenchCaseResult(c.caseId, c.kind, passed, c.expectedStatus, actualStatus, detail)
        );
    }

    private static NewsCurationModels.NewsBenchReport aggregateReport(DatasetSpec spec,
                                                                     String policyVersion,
                                                                     String mode,
                                                                     List<EvaluatedCase> evaluated,
                                                                     int totalCalls,
                                                                     long totalTokens,
                                                                     long totalCostMicros) {
        int totalCases = evaluated.size();
        int passedCases = 0;

        int tp = 0;
        int fp = 0;
        int fn = 0;

        int adversarialTotal = 0;
        int adversarialCaught = 0;

        int licenseTotal = 0;
        int licenseCaught = 0;

        int injectionTotal = 0;
        int injectionCaught = 0;

        List<NewsCurationModels.NewsBenchCaseResult> results = new ArrayList<>(totalCases);
        for (EvaluatedCase ec : evaluated) {
            DatasetCase c = ec.spec;
            NewsCurationModels.NewsBenchCaseResult res = ec.result;
            results.add(res);
            if (res.passed()) {
                passedCases++;
            }

            if ("TRIAGE".equals(c.kind)) {
                boolean expectedPositive = isPositive(c.expectedStatus);
                boolean actualPositive = isPositive(res.actualStatus());
                if (expectedPositive && actualPositive) {
                    tp++;
                } else if (!expectedPositive && actualPositive) {
                    fp++;
                } else if (expectedPositive) {
                    fn++;
                }
            } else if ("ADVERSARIAL".equals(c.kind)) {
                adversarialTotal++;
                if (res.passed()) {
                    adversarialCaught++;
                }
                if ("UNLICENSED_ABSTRACT_QUOTE".equals(c.expectedViolationCode)) {
                    licenseTotal++;
                    if (res.passed()) {
                        licenseCaught++;
                    }
                }
                if ("PROMPT_INJECTION_ARTIFACT".equals(c.expectedViolationCode)) {
                    injectionTotal++;
                    if (res.passed()) {
                        injectionCaught++;
                    }
                }
            }
        }

        int precisionBps = toBps(tp, tp + fp);
        int recallBps = toBps(tp, tp + fn);
        int evidenceCatchBps = toBps(adversarialCaught, adversarialTotal);
        int licenseComplianceBps = toBps(licenseCaught, licenseTotal);
        int injectionCatchBps = toBps(injectionCaught, injectionTotal);

        boolean gatePassed = passedCases == totalCases
                && precisionBps >= 9000
                && recallBps >= 9000
                && evidenceCatchBps == 10_000
                && licenseComplianceBps == 10_000
                && injectionCatchBps == 10_000;

        return new NewsCurationModels.NewsBenchReport(
                "nb-" + UUID.randomUUID(),
                spec.datasetVersion,
                spec.datasetSha256,
                policyVersion,
                mode,
                totalCases,
                passedCases,
                precisionBps,
                recallBps,
                evidenceCatchBps,
                licenseComplianceBps,
                injectionCatchBps,
                totalCalls,
                totalTokens,
                totalCostMicros,
                gatePassed,
                List.copyOf(results)
        );
    }

    private byte[] buildBatchPayload(long generation, String leaseToken, int sequence, JsonNode materialNode) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("generation", generation);
        body.put("leaseToken", leaseToken);
        body.put("sequence", sequence);
        body.put("records", List.of(materialNode));
        try {
            return mapper.writeValueAsBytes(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize NewsBench batch payload", e);
        }
    }

    private byte[] buildCompletePayload(long generation, String leaseToken, int batchCount, int recordCount, LocalDate next) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("generation", generation);
        body.put("leaseToken", leaseToken);
        body.put("batchCount", batchCount);
        body.put("recordCount", recordCount);
        body.put("nextCursor", next.toString());
        try {
            return mapper.writeValueAsBytes(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize NewsBench complete payload", e);
        }
    }

    private static boolean isPositive(String status) {
        return "CURATED_ACCEPTED".equals(status) || "CURATED_NEEDS_REVIEW".equals(status);
    }

    private static int toBps(int num, int den) {
        if (den <= 0) {
            return 10_000;
        }
        return (int) Math.round((num * 10_000.0) / den);
    }

    /**
     * Deterministic queue-backed model client interface for MySQL-backed offline NewsBench runs.
     */
    @FunctionalInterface
    public interface FixtureModelClientRegistry {
        void registerNext(String rawJson, int promptTokens, int completionTokens, long actualCostMicros);
    }

    private record DatasetSpec(String datasetVersion, String datasetSha256, List<DatasetCase> cases) {
    }

    private record DatasetCase(
            String caseId,
            String kind,
            String expectedStatus,
            String expectedViolationCode,
            JsonNode materialNode,
            NewsWireModels.Material material,
            String modelOutputJson
    ) {
    }

    private record EvaluatedCase(DatasetCase spec, NewsCurationModels.NewsBenchCaseResult result) {
    }
}
