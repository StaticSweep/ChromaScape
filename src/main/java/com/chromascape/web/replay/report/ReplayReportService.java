package com.chromascape.web.replay.report;

import com.chromascape.web.replay.api.dto.MismatchLevel;
import com.chromascape.web.replay.api.dto.ReplayOutcome;
import com.chromascape.web.replay.mismatch.MismatchEngine;
import com.chromascape.web.replay.projection.CanonicalProjectionService;
import com.chromascape.web.replay.report.entity.ReplayReport;
import com.chromascape.web.replay.report.repository.ReplayReportRepository;
import com.chromascape.web.replay.run.entity.ReplayAttempt;
import com.chromascape.web.replay.run.entity.ReplayMismatch;
import com.chromascape.web.replay.run.entity.ReplayRun;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Builds the immutable final report for a run. The report document is
 * canonical JSON covering attempt, counts, hashes, artifacts, and pinned
 * runtime versions; the report checksum covers exactly that document and
 * fails closed if hashing is unavailable.
 */
@Service
@Transactional
public class ReplayReportService {

    private final ReplayReportRepository reportRepository;
    private final MismatchEngine mismatchEngine;
    private final CanonicalProjectionService projectionService;
    private final ObjectMapper mapper;

    public ReplayReportService(ReplayReportRepository reportRepository,
                               MismatchEngine mismatchEngine,
                               CanonicalProjectionService projectionService,
                               ObjectMapper mapper) {
        this.reportRepository = reportRepository;
        this.mismatchEngine = mismatchEngine;
        this.projectionService = projectionService;
        this.mapper = mapper;
    }

    /**
     * Creates the report row. Must be called after artifacts are finalized.
     * The run must not be marked SUCCEEDED until this method returns
     * successfully.
     */
    public ReplayReport finalizeReport(ReplayRun run,
                                       ReplayAttempt attempt,
                                       ReplayOutcome outcome,
                                       String terminalReasonCode,
                                       int executedActionCount,
                                       int checkpointCount,
                                       String finalStateHash,
                                       String finalChainHash,
                                       List<String> artifactChecksums,
                                       Instant startedAt) {
        long hard = mismatchEngine.countByLevel(run.getId(), MismatchLevel.HARD);
        long soft = mismatchEngine.countByLevel(run.getId(), MismatchLevel.SOFT);
        long info = mismatchEngine.countByLevel(run.getId(), MismatchLevel.INFO);

        Instant terminalAt = run.getTerminalAt() != null
                ? java.sql.Timestamp.valueOf(run.getTerminalAt()).toInstant() : Instant.now();
        ObjectNode doc = buildReportDocument(run, attempt, outcome, terminalReasonCode,
                executedActionCount, checkpointCount, hard, soft, info,
                finalStateHash, finalChainHash, artifactChecksums, startedAt, terminalAt);

        String canonical;
        try {
            canonical = projectionService.canonicalJson(doc);
        } catch (Exception e) {
            throw new IllegalStateException("Report canonicalization failed", e);
        }
        // Fail closed: never persist an unhashed checksum.
        String checksum = "sha256:" + sha256Hex(canonical.getBytes(StandardCharsets.UTF_8));
        doc.put("reportChecksum", checksum);
        canonical = projectionService.canonicalJson(doc);

        ReplayReport report = new ReplayReport();
        report.setRunId(run.getId());
        report.setAttemptId(attempt != null ? attempt.getId() : null);
        report.setMode(run.getMode());
        report.setManifestChecksum(run.getManifestChecksum());
        report.setManifestVersion(run.getManifestVersion());
        report.setFidelity(run.getFidelity());
        report.setConversionQuality(run.getConversionQuality());
        report.setActionCoveragePct(run.getActionCoveragePct());
        report.setGapCount(run.getGapCount());
        report.setApproximationCount(run.getApproximationCount());
        report.setViewerTraceChecksum(run.getViewerTraceChecksum());
        report.setViewerTraceFrameCount(run.getViewerTraceFrameCount());
        report.setOutcome(outcome);
        report.setExecutedActionCount(executedActionCount);
        report.setCheckpointCount(checkpointCount);
        report.setMismatchHardCount(hard);
        report.setMismatchSoftCount(soft);
        report.setMismatchInfoCount(info);
        report.setFinalStateHash(finalStateHash);
        report.setFinalChainHash(finalChainHash);
        report.setTerminalReason(terminalReasonCode);
        report.setXrspsAdapterVersion(run.getXrspsAdapterVersion());
        report.setXrspsBuildCommit(run.getXrspsBuildCommit());
        report.setCacheRevision(run.getCacheRevision());
        report.setContentRevision(run.getContentRevision());
        report.setReportJson(canonical);
        report.setReportChecksum(checksum);
        report.setControlAllowed(false);
        report.setStartedAt(startedAt);
        report.setTerminalAt(terminalAt);
        report.setFinalizedAt(Instant.now());
        return reportRepository.save(report);
    }

    private ObjectNode buildReportDocument(ReplayRun run, ReplayAttempt attempt,
                                           ReplayOutcome outcome, String terminalReasonCode,
                                           int executedActionCount, int checkpointCount,
                                           long hard, long soft, long info,
                                           String finalStateHash, String finalChainHash,
                                           List<String> artifactChecksums,
                                           Instant startedAt, Instant terminalAt) {
        ObjectNode doc = mapper.createObjectNode();
        doc.put("reportVersion", 1);
        doc.put("manifestVersion", run.getManifestVersion());
        doc.put("runId", run.getId().toString());
        if (attempt != null) {
            doc.put("attemptId", attempt.getId().toString());
            doc.put("attemptNo", attempt.getAttemptNo());
            doc.put("fencingGeneration", attempt.getFencingGeneration());
        }
        doc.put("mode", run.getMode().name());
        doc.put("manifestChecksum", run.getManifestChecksum());
        doc.put("fidelity", run.getFidelity());
        doc.put("conversionQuality", run.getConversionQuality());
        doc.put("actionCoveragePct", run.getActionCoveragePct());
        doc.put("gapCount", run.getGapCount());
        doc.put("approximationCount", run.getApproximationCount());
        doc.put("outcome", outcome.name());
        if (terminalReasonCode != null) {
            doc.put("terminalReason", terminalReasonCode);
        }
        doc.put("executedActionCount", executedActionCount);
        doc.put("checkpointCount", checkpointCount);
        doc.put("mismatchHardCount", hard);
        doc.put("mismatchSoftCount", soft);
        doc.put("mismatchInfoCount", info);
        ObjectNode totals = doc.putObject("mismatchTotals");
        totals.put("hard", hard);
        totals.put("soft", soft);
        totals.put("info", info);
        ReplayMismatch firstHard = mismatchEngine.getMismatches(run.getId()).stream()
                .filter(mismatch -> mismatch.getLevel() == MismatchLevel.HARD)
                .findFirst()
                .orElse(null);
        if (firstHard != null) {
            doc.set("firstHardMismatch", mismatchNode(firstHard));
        }
        if (finalStateHash != null) {
            doc.put("finalStateHash", finalStateHash);
        }
        if (finalChainHash != null) {
            doc.put("finalChainHash", finalChainHash);
        }
        if (run.getViewerTraceChecksum() != null) {
            doc.put("viewerTraceChecksum", run.getViewerTraceChecksum());
        }
        if (run.getViewerTraceFrameCount() != null) {
            doc.put("viewerTraceFrameCount", run.getViewerTraceFrameCount());
        }
        ArrayNode artifacts = doc.putArray("artifactRefs");
        if (artifactChecksums != null) {
            artifactChecksums.forEach(artifacts::add);
        }
        ObjectNode runtime = doc.putObject("runtimeIdentity");
        runtime.put("xrspsAdapterVersion", run.getXrspsAdapterVersion());
        runtime.put("xrspsBuildCommit", run.getXrspsBuildCommit());
        runtime.put("cacheRevision", run.getCacheRevision());
        runtime.put("contentRevision", run.getContentRevision());
        if (startedAt != null) doc.put("startedAt", startedAt.toString());
        if (terminalAt != null) doc.put("terminalAt", terminalAt.toString());
        doc.put("controlAllowed", false);
        return doc;
    }

    private ObjectNode mismatchNode(ReplayMismatch mismatch) {
        ObjectNode node = mapper.createObjectNode();
        node.put("mismatchVersion", 1);
        node.put("level", mismatch.getLevel().name());
        node.put("ruleId", mismatch.getRuleId());
        if (mismatch.getFirstTick() != null) node.put("firstTick", mismatch.getFirstTick());
        if (mismatch.getTickSequence() != null) node.put("sequence", mismatch.getTickSequence());
        node.put("domain", mismatch.getDomain());
        if (mismatch.getJsonPointer() != null) node.put("jsonPointer", mismatch.getJsonPointer());
        node.set("expected", parseValue(mismatch.getExpectedValue()));
        node.set("actual", parseValue(mismatch.getActualValue()));
        if (mismatch.getRelatedActionOrdinal() != null) {
            node.put("relatedActionOrdinal", mismatch.getRelatedActionOrdinal());
        }
        if (mismatch.getCheckpointId() != null) node.put("checkpointId", mismatch.getCheckpointId().toString());
        if (mismatch.getCausalParentId() != null) node.put("causalParentId", mismatch.getCausalParentId().toString());
        if (mismatch.getCascadeCount() != null) node.put("cascadeCount", mismatch.getCascadeCount());
        return node;
    }

    private com.fasterxml.jackson.databind.JsonNode parseValue(String value) {
        if (value == null) return mapper.nullNode();
        try {
            com.fasterxml.jackson.databind.JsonNode parsed = mapper.readTree(value);
            return parsed == null ? mapper.nullNode() : parsed;
        } catch (Exception ignored) {
            return mapper.getNodeFactory().textNode(value);
        }
    }

    @Transactional(readOnly = true)
    public java.util.Optional<ReplayReport> getReport(UUID runId) {
        return reportRepository.findByRunId(runId);
    }

    private static String sha256Hex(byte[] input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(input);
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable; refusing to finalize report without integrity checksum", e);
        }
    }
}
