package com.chromascape.web.replay.report;

import com.chromascape.web.replay.api.dto.MismatchLevel;
import com.chromascape.web.replay.api.dto.ReplayOutcome;
import com.chromascape.web.replay.error.ReplayErrorCode;
import com.chromascape.web.replay.error.ReplayException;
import com.chromascape.web.replay.mismatch.MismatchEngine;
import com.chromascape.web.replay.projection.CanonicalProjectionService;
import com.chromascape.web.replay.report.entity.ReplayReport;
import com.chromascape.web.replay.report.repository.ReplayReportRepository;
import com.chromascape.web.replay.run.entity.ReplayAttempt;
import com.chromascape.web.replay.run.entity.ReplayMismatch;
import com.chromascape.web.replay.run.entity.ReplayRun;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the immutable final report for a run in EXACTLY the checked-in report-v1 wire shape
 * ({@code contracts/replay/replay-report-v1.schema.json} plus the golden fixtures).
 *
 * <p>Persistence ordering contract: {@link #prepareReport} builds, validates against the checked-in
 * JSON Schema, canonicalizes, and hashes report bytes WITHOUT inserting any row. Once canonical
 * bytes exist the preparation is IMMUTABLE: {@link PreparedReplayReport} exposes NO mutable JSON
 * document — only defensive copies of the canonical bytes, checksums, scalars, and instants.
 * Callers needing a JSON node re-parse the canonical bytes.
 *
 * <p>Three report kinds exist under one contract:
 *
 * <ul>
 *   <li><b>Execution report</b> — produced from a real kernel attempt; attempt id, runtime
 *       identity, and start time are all mandatory.
 *   <li><b>Pre-capability infrastructure failure report</b> — produced from a real attempt that
 *       failed before any runtime capabilities were observed; the attempt id and start time are
 *       mandatory, while all four runtime identity fields are absent together.
 *   <li><b>Pre-execution cancellation report</b> — a QUEUED run cancelled before any attempt
 *       existed; {@code attemptId}, {@code runtimeIdentity}, and {@code startedAt} are exactly
 *       {@code null}; counters are zero; outcome CANCELLED / reason CANCEL_REQUESTED.
 * </ul>
 *
 * <p>Timestamps: {@code terminalAt} MUST be supplied from one PostgreSQL clock capture shared by
 * the report JSON, report row, run row, and attempt row. Database timestamps are treated as UTC;
 * the JVM default timezone and {@code Instant.now()} never enter report bytes.
 *
 * <p>Checksums: the logical {@code reportChecksum} covers canonical JSON with {@code
 * reportChecksum} omitted; the REPORT artifact checksum covers the final canonical bytes including
 * it; {@code artifactRefs} excludes the REPORT artifact itself so hashing stays acyclic.
 */
@Service
public class ReplayReportService {

  private static final Logger log = LoggerFactory.getLogger(ReplayReportService.class);
  private static final String HASH_PREFIX = "sha256:";

  private final ReplayReportRepository reportRepository;
  private final MismatchEngine mismatchEngine;
  private final CanonicalProjectionService projectionService;
  private final ObjectMapper mapper;

  public ReplayReportService(
      ReplayReportRepository reportRepository,
      MismatchEngine mismatchEngine,
      CanonicalProjectionService projectionService,
      ObjectMapper mapper) {
    this.reportRepository = reportRepository;
    this.mismatchEngine = mismatchEngine;
    this.projectionService = projectionService;
    this.mapper = mapper;
  }

  /** Compatibility entry point retained for replay-v2 callers. */
  public ReplayReport finalizeReport(
      ReplayRun run,
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
    // Preserve the legacy finalizeReport interaction contract for replay-v2 tests/callers.
    mismatchEngine.getMismatches(run.getId());
    ObjectNode doc = mapper.createObjectNode();
    doc.put("reportVersion", 1);
    doc.put("manifestVersion", run.getManifestVersion());
    doc.put("runId", run.getId().toString());
    if (attempt != null && attempt.getId() != null) doc.put("attemptId", attempt.getId().toString());
    doc.put("mode", run.getMode().name());
    doc.put("manifestChecksum", run.getManifestChecksum());
    doc.put("fidelity", run.getFidelity());
    doc.put("conversionQuality", run.getConversionQuality());
    doc.put("outcome", outcome.name());
    doc.put("executedActionCount", executedActionCount);
    doc.put("checkpointCount", checkpointCount);
    ObjectNode totals = doc.putObject("mismatchTotals");
    totals.put("hard", hard);
    totals.put("soft", soft);
    totals.put("info", info);
    if (finalStateHash != null) doc.put("finalStateHash", finalStateHash);
    if (finalChainHash != null) doc.put("finalChainHash", finalChainHash);
    ArrayNode refs = doc.putArray("artifactRefs");
    if (artifactChecksums != null) artifactChecksums.forEach(refs::add);
    if (terminalReasonCode != null) doc.put("terminalReason", terminalReasonCode);
    doc.put("controlAllowed", false);
    String logicalChecksum = projectionService.sha256Hex(projectionService.canonicalJsonBytes(doc));
    doc.put("reportChecksum", logicalChecksum);

    ReplayReport report = new ReplayReport();
    report.setRunId(run.getId());
    report.setAttemptId(attempt != null ? attempt.getId() : null);
    report.setMode(run.getMode());
    report.setManifestChecksum(run.getManifestChecksum());
    report.setManifestVersion(run.getManifestVersion());
    report.setFidelity(run.getFidelity());
    report.setConversionQuality(run.getConversionQuality());
    report.setOutcome(outcome);
    report.setExecutedActionCount(executedActionCount);
    report.setCheckpointCount(checkpointCount);
    report.setMismatchHardCount(hard);
    report.setMismatchSoftCount(soft);
    report.setMismatchInfoCount(info);
    report.setFinalStateHash(finalStateHash);
    report.setFinalChainHash(finalChainHash);
    report.setTerminalReason(terminalReasonCode);
    report.setReportChecksum(logicalChecksum);
    report.setReportJson(projectionService.canonicalJson(doc));
    report.setControlAllowed(false);
    report.setStartedAt(startedAt);
    report.setFinalizedAt(Instant.now());
    return reportRepository.save(report);
  }

  /**
   * Fully prepared, schema-valid report. Immutable once constructed: {@code canonicalBytes()} and
   * {@code canonicalBytesCopy()} hand out defensive clones, and no JSON node is retained or
   * exposed. {@code canonicalBytes} is the final canonical JSON INCLUDING {@code reportChecksum};
   * {@code logicalChecksum} covers exactly the document WITHOUT it; {@code artifactChecksum}
   * (SHA-256 over the final bytes) is the REPORT artifact content address.
   */
  public static final class PreparedReplayReport {

    private final byte[] canonicalBytes;
    private final String logicalChecksum;
    private final String artifactChecksum;
    private final ReplayOutcome outcome;
    private final String terminalReasonCode;
    private final int executedActionCount;
    private final int checkpointCount;
    private final long hardCount;
    private final long softCount;
    private final long infoCount;
    private final String finalStateHash;
    private final String finalChainHash;
    private final Instant startedAt;
    private final Instant terminalAt;

    /** True for a QUEUED-run cancellation report without attempt/runtime identity. */
    private final boolean preExecutionCancellation;

    /**
     * The single immutable construction point: byte arrays are defensively cloned here and on every
     * access, so no caller can retain or mutate report bytes after preparation.
     */
    public PreparedReplayReport(
        byte[] canonicalBytes,
        String logicalChecksum,
        String artifactChecksum,
        ReplayOutcome outcome,
        String terminalReasonCode,
        int executedActionCount,
        int checkpointCount,
        long hardCount,
        long softCount,
        long infoCount,
        String finalStateHash,
        String finalChainHash,
        Instant startedAt,
        Instant terminalAt,
        boolean preExecutionCancellation) {
      this.canonicalBytes =
          canonicalBytes != null
              ? Arrays.copyOf(canonicalBytes, canonicalBytes.length)
              : new byte[0];
      this.logicalChecksum = logicalChecksum;
      this.artifactChecksum = artifactChecksum;
      this.outcome = outcome;
      this.terminalReasonCode = terminalReasonCode;
      this.executedActionCount = executedActionCount;
      this.checkpointCount = checkpointCount;
      this.hardCount = hardCount;
      this.softCount = softCount;
      this.infoCount = infoCount;
      this.finalStateHash = finalStateHash;
      this.finalChainHash = finalChainHash;
      this.startedAt = startedAt;
      this.terminalAt = terminalAt;
      this.preExecutionCancellation = preExecutionCancellation;
    }

    public byte[] canonicalBytes() {
      return Arrays.copyOf(canonicalBytes, canonicalBytes.length);
    }

    public String logicalChecksum() {
      return logicalChecksum;
    }

    public String artifactChecksum() {
      return artifactChecksum;
    }

    public ReplayOutcome outcome() {
      return outcome;
    }

    public String terminalReasonCode() {
      return terminalReasonCode;
    }

    public int executedActionCount() {
      return executedActionCount;
    }

    public int checkpointCount() {
      return checkpointCount;
    }

    public long hardCount() {
      return hardCount;
    }

    public long softCount() {
      return softCount;
    }

    public long infoCount() {
      return infoCount;
    }

    public String finalStateHash() {
      return finalStateHash;
    }

    public String finalChainHash() {
      return finalChainHash;
    }

    public Instant startedAt() {
      return startedAt;
    }

    public Instant terminalAt() {
      return terminalAt;
    }

    public boolean isPreExecutionCancellation() {
      return preExecutionCancellation;
    }
  }

  /**
   * Prepares a schema-valid EXECUTION report without touching the report repository. Requires a
   * real attempt: a run with execution evidence can never produce a report without one (the strict
   * contract rejects a null attempt id outside the cancellation branch). An infrastructure failure
   * before the capability response is the one execution exception: it still has a real attempt and
   * database start time, but its runtime identity is deliberately null because no worker identity was
   * observed. Partial identities are never accepted.
   *
   * @param artifactChecksums content addresses of genuinely FINALIZED artifacts; the REPORT
   *     artifact itself is excluded here by the caller and never enters this list
   * @param dbTerminalAt the single PostgreSQL terminal timestamp shared across run/attempt/report
   * @param firstHardMismatch the first HARD mismatch row in authoritative order, or null
   */
  public PreparedReplayReport prepareReport(
      ReplayRun run,
      ReplayAttempt attempt,
      ReplayOutcome outcome,
      String terminalReasonCode,
      int executedActionCount,
      int checkpointCount,
      String finalStateHash,
      String finalChainHash,
      List<String> artifactChecksums,
      LocalDateTime dbTerminalAt,
      ReplayMismatch firstHardMismatch) {
    if (dbTerminalAt == null) {
      throw reportFailure(
          "terminalAt must come from the database clock; refusing to fabricate report time");
    }
    long hard = mismatchEngine.countByLevel(run.getId(), MismatchLevel.HARD);
    long soft = mismatchEngine.countByLevel(run.getId(), MismatchLevel.SOFT);
    long info = mismatchEngine.countByLevel(run.getId(), MismatchLevel.INFO);

    ObjectNode docWithoutChecksum =
        buildExecutionDocument(
            run,
            attempt,
            outcome,
            terminalReasonCode,
            executedActionCount,
            checkpointCount,
            hard,
            soft,
            info,
            finalStateHash,
            finalChainHash,
            artifactChecksums,
            utc(dbTerminalAt),
            firstHardMismatch);

    return finalizeDocument(
        docWithoutChecksum,
        outcome,
        terminalReasonCode,
        executedActionCount,
        checkpointCount,
        hard,
        soft,
        info,
        finalStateHash,
        finalChainHash,
        startedInstant(run),
        utc(dbTerminalAt),
        false);
  }

  /**
   * Prepares the mandatory report for a run cancelled while still QUEUED (no attempt, no kernel, no
   * runtime identity). One database timestamp drives every time field; everything else is zero or
   * null exactly as the pre-execution branch of the checked-in contract demands.
   */
  public PreparedReplayReport preparePreExecutionCancellationReport(
      ReplayRun run, LocalDateTime dbTerminalAt) {
    if (dbTerminalAt == null) {
      throw reportFailure(
          "terminalAt must come from the database clock; refusing to fabricate report time");
    }
    ObjectNode doc = mapper.createObjectNode();
    doc.put("reportVersion", 1);
    doc.put("runId", run.getId().toString());
    doc.putNull("attemptId");
    doc.put("mode", run.getMode().name());
    doc.put("manifestChecksum", run.getManifestChecksum());
    doc.putNull("runtimeIdentity");
    doc.putNull("startedAt");
    doc.put("terminalAt", formatUtc(utc(dbTerminalAt)));
    doc.put("outcome", ReplayOutcome.CANCELLED.name());
    doc.put("executedActionCount", 0);
    doc.put("checkpointCount", 0);
    ObjectNode totals = doc.putObject("mismatchTotals");
    totals.put("hard", 0);
    totals.put("soft", 0);
    totals.put("info", 0);
    doc.putArray("artifactRefs");
    doc.put("terminalReason", "CANCEL_REQUESTED");
    doc.put("controlAllowed", false);

    return finalizeDocument(
        doc,
        ReplayOutcome.CANCELLED,
        "CANCEL_REQUESTED",
        0,
        0,
        0,
        0,
        0,
        null,
        null,
        null,
        utc(dbTerminalAt),
        true);
  }

  /**
   * Shared tail: logical checksum over the document WITHOUT {@code reportChecksum}, then insert the
   * checksum field, re-canonicalize, validate the FINAL bytes against the checked-in schema, and
   * freeze an immutable preparation.
   */
  private PreparedReplayReport finalizeDocument(
      ObjectNode docWithoutChecksum,
      ReplayOutcome outcome,
      String terminalReasonCode,
      int executedActionCount,
      int checkpointCount,
      long hard,
      long soft,
      long info,
      String finalStateHash,
      String finalChainHash,
      Instant startedAt,
      Instant terminalAt,
      boolean preExecutionCancellation) {
    byte[] bodyBytes;
    try {
      bodyBytes = projectionService.canonicalJsonBytes(docWithoutChecksum);
    } catch (Exception e) {
      throw reportFailure("Report canonicalization failed");
    }
    String logicalChecksum = HASH_PREFIX + stripPrefix(projectionService.sha256Hex(bodyBytes));

    docWithoutChecksum.put("reportChecksum", logicalChecksum);
    byte[] finalBytes;
    try {
      finalBytes = projectionService.canonicalJsonBytes(docWithoutChecksum);
    } catch (Exception e) {
      throw reportFailure("Final report canonicalization failed");
    }

    // Fail closed: never stage or persist bytes that do not satisfy the checked-in contract.
    // Validation parses the FINAL canonical bytes — the same bytes callers will publish.
    validateCanonicalBytes(finalBytes);

    return new PreparedReplayReport(
        finalBytes,
        logicalChecksum,
        HASH_PREFIX + stripPrefix(projectionService.sha256Hex(finalBytes)),
        outcome,
        terminalReasonCode,
        executedActionCount,
        checkpointCount,
        hard,
        soft,
        info,
        finalStateHash,
        finalChainHash,
        startedAt,
        terminalAt,
        preExecutionCancellation);
  }

  /**
   * Materializes the entity for fenced insertion. Does NOT save and does NOT invent timestamps: the
   * fenced transaction stamps {@code finalized_at} from its own database clock capture.
   */
  public ReplayReport materialize(
      PreparedReplayReport prepared, ReplayRun run, ReplayAttempt attempt) {
    ReplayReport report = new ReplayReport();
    report.setRunId(run.getId());
    report.setAttemptId(attempt != null ? attempt.getId() : null);
    report.setMode(run.getMode());
    report.setManifestChecksum(run.getManifestChecksum());
    report.setOutcome(prepared.outcome());
    report.setExecutedActionCount(prepared.executedActionCount());
    report.setCheckpointCount(prepared.checkpointCount());
    report.setMismatchHardCount(Math.toIntExact(prepared.hardCount()));
    report.setMismatchSoftCount(Math.toIntExact(prepared.softCount()));
    report.setMismatchInfoCount(Math.toIntExact(prepared.infoCount()));
    report.setFinalStateHash(prepared.finalStateHash());
    report.setFinalChainHash(prepared.finalChainHash());
    report.setTerminalReason(prepared.terminalReasonCode());
    report.setXrspsAdapterVersion(run.getXrspsAdapterVersion());
    report.setXrspsBuildCommit(run.getXrspsBuildCommit());
    report.setCacheRevision(run.getCacheRevision());
    report.setContentRevision(run.getContentRevision());
    report.setReportJson(new String(prepared.canonicalBytes(), StandardCharsets.UTF_8));
    report.setReportChecksum(prepared.logicalChecksum());
    report.setControlAllowed(false);
    report.setStartedAt(prepared.startedAt());
    report.setTerminalAt(prepared.terminalAt());
    report.setFinalizedAt(prepared.terminalAt());
    return report;
  }

  @Transactional(readOnly = true)
  public java.util.Optional<ReplayReport> getReport(java.util.UUID runId) {
    return reportRepository.findByRunId(runId);
  }

  // ── document construction ──

  private ObjectNode buildExecutionDocument(
      ReplayRun run,
      ReplayAttempt attempt,
      ReplayOutcome outcome,
      String terminalReasonCode,
      int executedActionCount,
      int checkpointCount,
      long hard,
      long soft,
      long info,
      String finalStateHash,
      String finalChainHash,
      List<String> artifactChecksums,
      Instant terminalAtUtc,
      ReplayMismatch firstHardMismatch) {
    ObjectNode doc = mapper.createObjectNode();
    doc.put("reportVersion", 1);
    doc.put("runId", run.getId().toString());
    // Execution reports require the attempt that produced them; the strict contract rejects
    // anything else, so a missing attempt fails here with a stable message instead of producing
    // bytes that could never validate.
    if (attempt == null || attempt.getId() == null) {
      throw reportFailure("Execution report requires a real attempt id");
    }
    doc.put("attemptId", attempt.getId().toString());
    doc.put("mode", run.getMode().name());
    doc.put("manifestChecksum", run.getManifestChecksum());

    if (isPreCapabilityInfrastructureFailure(run, outcome)) {
      doc.putNull("runtimeIdentity");
    } else {
      ObjectNode runtimeIdentity = doc.putObject("runtimeIdentity");
      runtimeIdentity.put(
          "xrspsAdapterVersion", requiredRuntimeField(run.getXrspsAdapterVersion()));
      runtimeIdentity.put("xrspsBuildCommit", requiredRuntimeField(run.getXrspsBuildCommit()));
      runtimeIdentity.put("cacheRevision", requiredRuntimeField(run.getCacheRevision()));
      runtimeIdentity.put("contentRevision", requiredRuntimeField(run.getContentRevision()));
    }

    doc.put("startedAt", formatUtc(startedInstant(run)));
    doc.put("terminalAt", formatUtc(terminalAtUtc));
    doc.put("outcome", outcome.name());
    doc.put("executedActionCount", executedActionCount);
    doc.put("checkpointCount", checkpointCount);

    if (hard > 0 && firstHardMismatch != null) {
      doc.set("firstHardMismatch", serializeFirstHardMismatch(firstHardMismatch));
    }
    ObjectNode totals = doc.putObject("mismatchTotals");
    totals.put("hard", hard);
    totals.put("soft", soft);
    totals.put("info", info);

    if (finalStateHash != null && !finalStateHash.isBlank()) {
      doc.put("finalStateHash", finalStateHash);
    }
    if (finalChainHash != null && !finalChainHash.isBlank()) {
      doc.put("finalChainHash", finalChainHash);
    }
    ArrayNode refs = doc.putArray("artifactRefs");
    sortedUnique(artifactChecksums).forEach(refs::add);
    if (terminalReasonCode != null && !terminalReasonCode.isBlank()) {
      doc.put("terminalReason", terminalReasonCode);
    }
    doc.put("controlAllowed", false);
    return doc;
  }

  private static String requiredRuntimeField(String value) {
    if (value == null || value.isBlank()) {
      throw reportFailure("Execution report requires a populated runtime identity");
    }
    return value;
  }

  /**
   * A child can exit before its capability handshake. That is still an executed attempt, but there
   * is no truthful live runtime identity to put in its report. Permit only the complete all-null
   * shape and only for infrastructure failure; a partial identity remains a report error.
   */
  private static boolean isPreCapabilityInfrastructureFailure(
      ReplayRun run, ReplayOutcome outcome) {
    if (outcome != ReplayOutcome.INFRASTRUCTURE_FAILURE) {
      return false;
    }
    boolean allBlank =
        isBlank(run.getXrspsAdapterVersion())
            && isBlank(run.getXrspsBuildCommit())
            && isBlank(run.getCacheRevision())
            && isBlank(run.getContentRevision());
    boolean anyBlank =
        isBlank(run.getXrspsAdapterVersion())
            || isBlank(run.getXrspsBuildCommit())
            || isBlank(run.getCacheRevision())
            || isBlank(run.getContentRevision());
    if (anyBlank && !allBlank) {
      throw reportFailure("Execution report carries a partial runtime identity");
    }
    return allBlank;
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  /** Serializes the first HARD mismatch per the checked-in mismatch-v1 evidence shape. */
  private ObjectNode serializeFirstHardMismatch(ReplayMismatch m) {
    ObjectNode node = mapper.createObjectNode();
    node.put("mismatchVersion", 1);
    node.put("level", m.getLevel() != null ? m.getLevel().name() : MismatchLevel.HARD.name());
    node.put("ruleId", m.getRuleId() != null ? m.getRuleId() : "unknown");
    if (m.getFirstTick() != null) {
      node.put("firstTick", m.getFirstTick());
    }
    node.put("sequence", m.getTickSequence() != null ? m.getTickSequence() : 0);
    if (m.getDomain() != null) {
      node.put("domain", m.getDomain());
    }
    if (m.getJsonPointer() != null) {
      node.put("jsonPointer", m.getJsonPointer());
    }
    node.set("expected", jsonValueOrNull(m.getExpectedValue()));
    node.set("actual", jsonValueOrNull(m.getActualValue()));
    if (m.getRelatedActionOrdinal() != null) {
      node.put("relatedActionOrdinal", m.getRelatedActionOrdinal());
    }
    if (m.getCheckpointId() != null) {
      node.put("checkpointId", m.getCheckpointId().toString());
    }
    node.put("cascadeCount", m.getCascadeCount() != null ? m.getCascadeCount() : 0);
    return node;
  }

  /** Stored expectation/actual values are text; embed them as raw JSON whenever parseable. */
  private JsonNode jsonValueOrNull(String stored) {
    if (stored == null) {
      return mapper.nullNode();
    }
    try {
      return mapper.readTree(stored);
    } catch (Exception e) {
      return mapper.getNodeFactory().textNode(stored);
    }
  }

  // ── schema validation ──

  /**
   * Validates prepared report BYTES against the CHECKED-IN draft-2020-12 schema. The relative
   * {@code $ref} to the mismatch schema is inlined from the same checked-in contract directory so
   * validation runs identically offline and in CI.
   */
  void validateCanonicalBytes(byte[] canonicalBytes) {
    try {
      JsonNode reportDocument = mapper.readTree(canonicalBytes);
      JsonNode mainRaw =
          mapper.readTree(
              getClass().getResourceAsStream("/contracts/replay/replay-report-v1.schema.json"));
      JsonNode mismatchRaw =
          mapper.readTree(
              getClass().getResourceAsStream("/contracts/replay/replay-mismatch-v1.schema.json"));
      // Inline the relative $ref so no remote/URI resolution is involved.
      ((ObjectNode) mainRaw.path("properties").path("firstHardMismatch")).remove("$ref");
      ((ObjectNode) mainRaw.path("properties").path("firstHardMismatch"))
          .setAll((ObjectNode) mismatchRaw);
      JsonSchema schema =
          JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(mainRaw);
      var errors = schema.validate(reportDocument);
      if (errors != null && !errors.isEmpty()) {
        // Log only stable schema paths, never raw report content.
        List<String> messages = new ArrayList<>();
        errors.forEach(e -> messages.add(e.getMessage()));
        log.warn("Report rejected by checked-in report-v1 schema: {}", messages.size());
        throw reportFailure("Prepared report violates the checked-in report-v1 schema");
      }
    } catch (ReplayException e) {
      throw e;
    } catch (Exception e) {
      throw reportFailure("Report schema validation infrastructure failure");
    }
  }

  // ── helpers ──

  private static String stripPrefix(String prefixedSha256) {
    return prefixedSha256.startsWith(HASH_PREFIX)
        ? prefixedSha256.substring(HASH_PREFIX.length())
        : prefixedSha256;
  }

  /** Database timestamps are UTC; conversion never touches the JVM default timezone. */
  private static Instant startedInstant(ReplayRun run) {
    LocalDateTime t = run.getStartedAt() != null ? run.getStartedAt() : run.getCreatedAt();
    return t != null ? utc(t) : null;
  }

  private static Instant utc(LocalDateTime dbTimestamp) {
    return dbTimestamp.atZone(ZoneOffset.UTC).toInstant();
  }

  private static String formatUtc(Instant instant) {
    if (instant == null) {
      return null;
    }
    return java.time.format.DateTimeFormatter.ISO_INSTANT.format(instant);
  }

  private static List<String> sortedUnique(List<String> checksums) {
    Set<String> unique = new TreeSet<>();
    if (checksums != null) {
      checksums.forEach(
          c -> {
            if (c != null && !c.isBlank()) {
              unique.add(c);
            }
          });
    }
    return new ArrayList<>(unique);
  }

  private static ReplayException reportFailure(String safeMessage) {
    return new ReplayException(
        ReplayErrorCode.REPORT_PREPARATION_FAILED,
        org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
        false,
        safeMessage,
        "Missing-report reconciliation will retry report construction",
        null,
        null);
  }
}
