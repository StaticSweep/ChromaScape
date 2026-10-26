package com.chromascape.web.aiplayer.routine.service;

import com.chromascape.web.aiplayer.routine.config.Sc2106ExecutionProperties;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Executes one registered, approved candidate through the Micro6 host (SC-2106 R9.1, R9.3).
 *
 * <p>The request is built only from the re-verified registration bundle: proposal and approval
 * digests, base commit and candidate hash come from the signed manifest, and the runner, adapter,
 * protocol, core entry, package, core and dependency digests from the signed execution closure. The
 * manifest and closure are named by content digest. Every bundle file is copied into the
 * content-addressed store the host resolves from, so the host verifies the same bytes that were
 * registered. The result is accepted only through {@link CandidateExecutionResultValidator}.
 *
 * <p>Jobs are held in memory only. Durable job status, restart reattachment and duplicate
 * suppression across restarts (R9.2, R9.5) depend on the SC-279 ownership store and are not
 * provided here; a duplicate job id is refused only while the first run is in this process.
 */
@Service
public class CandidateExecutionJobService {
  private final PatchCandidateWorkflowService workflow;
  private final Sc2106ExecutionProperties properties;
  private final CandidateHostLauncher host;
  private final Map<String, AtomicBoolean> active = new ConcurrentHashMap<>();

  @Autowired
  public CandidateExecutionJobService(
      PatchCandidateWorkflowService workflow, Sc2106ExecutionProperties properties) {
    this(workflow, properties, null);
  }

  /** Test seam: a supplied launcher replaces the configured host process. */
  CandidateExecutionJobService(
      PatchCandidateWorkflowService workflow,
      Sc2106ExecutionProperties properties,
      CandidateHostLauncher host) {
    this.workflow = workflow;
    this.properties = properties;
    this.host = host;
  }

  /**
   * What the caller chooses for one run. Everything about the candidate itself comes from the
   * registration, never from here.
   *
   * @param task {@code taskKey} and {@code settings}; the runner re-derives the task hash and
   *     refuses a mismatch before dispatch
   * @param start the start manifest and facts the runner opens the session from
   */
  public record JobSpec(
      String jobId,
      String idempotencyKey,
      String baselineId,
      String scenarioId,
      String checkpointId,
      String contentVersion,
      String cacheVersion,
      String profileId,
      String recordingId,
      String initialProvenance,
      Map<String, Object> task,
      String taskHash,
      Map<String, Object> start,
      String executionMode,
      String worldSeed,
      String taskSeed,
      String mouseSeed,
      Budgets budgets) {}

  /** The request budgets; {@code -1} means unbounded. */
  public record Budgets(
      long maxSteps,
      long maxTicks,
      long maxIntents,
      long maxWallTimeMillis,
      long maxMemoryBytes,
      long maxOutputBytes) {}

  /**
   * The finished job. {@code outcome} is the validated meaning of the result; {@code certifiable}
   * also requires the host's certification verdict and a certifiable worker.
   */
  public record ExecutionJob(
      String jobId,
      String attemptId,
      String requestDigest,
      Map<String, Object> request,
      CandidateExecutionResultValidator.Outcome outcome,
      List<String> reasons,
      boolean certifiable,
      Map<String, Object> result,
      Map<String, Object> hostOutcome) {}

  /** Builds the request, stages the candidate and runs it to a validated outcome. */
  public ExecutionJob execute(String proposalId, JobSpec spec) {
    if (properties == null || !properties.isEnabled()) {
      throw failure(
          HttpStatus.FORBIDDEN,
          "CANDIDATE_EXECUTION_DISABLED",
          "candidate execution is disabled (sc2106.execution.enabled=false)");
    }
    if (spec == null || spec.jobId() == null || spec.jobId().isBlank()) {
      throw failure(HttpStatus.UNPROCESSABLE_ENTITY, "EXECUTION_JOB_INVALID", "jobId is required");
    }
    if (causal(spec) && !PINNED_PROFILE.matcher(String.valueOf(spec.profileId())).matches()) {
      throw failure(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "EXECUTION_PROFILE_UNPINNED",
          "a causal run needs profileId as a pinned SC-1914 profile reference"
              + " <profileId>@<revision>#sha256:<hex>, got "
              + spec.profileId());
    }
    AtomicBoolean cancel = new AtomicBoolean();
    if (active.putIfAbsent(spec.jobId(), cancel) != null) {
      throw failure(HttpStatus.CONFLICT, "EXECUTION_JOB_ACTIVE", "job is already running");
    }
    try {
      PatchCandidateWorkflowService.RegisteredCandidate candidate =
          workflow.registeredCandidate(proposalId);
      Staged staged = stage(candidate);
      String attemptId = "attempt-" + UUID.randomUUID();
      CandidateExecutionRequestV2 request = request(staged, spec, attemptId);
      Path runDirectory = directory(properties.getRunRoot(), "runRoot").resolve(attemptId);
      Files.createDirectory(runDirectory);
      Map<String, Object> job = job(request, spec, runDirectory);
      CandidateHostLauncher.HostOutcome outcome =
          launcher().run(job, properties.getHostTimeout(), cancel);
      return interpret(spec.jobId(), attemptId, request, outcome);
    } catch (IOException e) {
      throw failure(HttpStatus.INTERNAL_SERVER_ERROR, "EXECUTION_STAGING_FAILED", e.getMessage());
    } catch (IllegalArgumentException e) {
      throw failure(HttpStatus.UNPROCESSABLE_ENTITY, "EXECUTION_REQUEST_INVALID", e.getMessage());
    } finally {
      active.remove(spec.jobId());
    }
  }

  /**
   * A content-addressed SC-1914 profile reference. It travels in the request's {@code profileId},
   * so the request digest covers the exact profile revision and digest; the runner and the worker
   * both re-check it.
   */
  static final Pattern PINNED_PROFILE =
      Pattern.compile("[a-z0-9][a-z0-9._-]{0,127}@[1-9][0-9]{0,8}#sha256:[0-9a-f]{64}");

  private static boolean causal(JobSpec spec) {
    return spec.executionMode() == null || "CAUSAL_CANDIDATE".equals(spec.executionMode());
  }

  /** Compares a baseline and a candidate job only when their conditions are equivalent. */
  public CandidateExecutionComparison.Comparison compare(
      ExecutionJob baseline, ExecutionJob candidate) {
    return CandidateExecutionComparison.compare(baseline, candidate);
  }

  /** Asks a running job to stop; false when no such job is running here. */
  public boolean cancel(String jobId) {
    AtomicBoolean cancel = active.get(jobId);
    if (cancel == null) {
      return false;
    }
    cancel.set(true);
    return true;
  }

  /** The verified bundle bytes, the parsed signed documents and their content digests. */
  record Staged(JsonNode manifest, JsonNode closure, String manifestRef, String closureRef) {}

  /**
   * Copies every bundle file into the content-addressed store. An existing entry is reused only
   * when its bytes hash to its name.
   */
  Staged stage(PatchCandidateWorkflowService.RegisteredCandidate candidate) throws IOException {
    Path bundle = candidate.bundle();
    Path store = directory(properties.getStoreRoot(), "storeRoot").resolve("sha256");
    Files.createDirectories(store);
    byte[] manifestBytes = Files.readAllBytes(bundle.resolve("manifest.json"));
    byte[] closureBytes = Files.readAllBytes(bundle.resolve("execution-closure.json"));
    JsonNode closure = Sc967CanonicalJson.parse(closureBytes);
    List<String> files = new ArrayList<>();
    files.add("certification.json");
    files.add(candidate.jarName());
    files.add(candidate.sbomName());
    files.add(closure.path("package").path("name").asText());
    files.add(closure.path("core").path("name").asText());
    for (JsonNode dependency : closure.path("dependencies")) {
      files.add(dependency.path("name").asText());
    }
    String manifestRef = put(store, manifestBytes);
    String closureRef = put(store, closureBytes);
    for (String name : files) {
      Path file = bundle.resolve(name).normalize();
      if (name.isBlank() || !file.getParent().equals(bundle.normalize())) {
        throw new IOException("BUNDLE_ENTRY_INVALID: " + name);
      }
      put(store, Files.readAllBytes(file));
    }
    return new Staged(Sc967CanonicalJson.parse(manifestBytes), closure, manifestRef, closureRef);
  }

  CandidateExecutionRequestV2 request(Staged staged, JobSpec spec, String attemptId) {
    JsonNode manifest = staged.manifest();
    JsonNode closure = staged.closure();
    List<String> dependencies = new ArrayList<>();
    for (JsonNode dependency : closure.path("dependencies")) {
      dependencies.add(dependency.path("sha256").asText());
    }
    Map<String, Object> settings = settings(spec.task());
    Budgets budgets = spec.budgets();
    return CandidateExecutionRequestV2.builder()
        .requestId("request-" + attemptId.substring("attempt-".length()))
        .idempotencyKey(spec.idempotencyKey())
        .certificationRunId(spec.jobId())
        .attemptId(attemptId)
        .jobId(spec.jobId())
        .proposalDigest(manifest.path("proposal_hash").asText(null))
        .approvalDigest(manifest.path("approval_hash").asText(null))
        .baseCommit(manifest.path("base_commit").asText(null))
        .candidateDigest(manifest.path("candidate").path("candidate_hash").asText(null))
        .packageDigest(closure.path("package").path("sha256").asText(null))
        .coreDigest(closure.path("core").path("sha256").asText(null))
        .signedManifestRef(staged.manifestRef())
        .executionClosureRef(staged.closureRef())
        .verifierPolicyVersion(properties.getVerifierPolicyVersion())
        .taskCoreEntry(closure.path("task_core_entry").asText(null))
        .runnerVersion(closure.path("runner_version").asText(null))
        .adapterVersion(closure.path("adapter_version").asText(null))
        .protocolVersion(closure.path("protocol_version").asText(null))
        .dependencyClosure(dependencies)
        .baselineId(spec.baselineId())
        .scenarioId(spec.scenarioId())
        .checkpointId(spec.checkpointId())
        .contentVersion(spec.contentVersion())
        .cacheVersion(spec.cacheVersion())
        .profileId(spec.profileId())
        .recordingId(spec.recordingId())
        .initialProvenance(spec.initialProvenance())
        .taskHash(spec.taskHash())
        .settingsHash(Sc2106CanonicalJson.sha256(settings))
        .executionMode(
            spec.executionMode() == null
                ? CandidateExecutionRequestV2.CAUSAL_CANDIDATE
                : spec.executionMode())
        .worldSeed(spec.worldSeed())
        .taskSeed(spec.taskSeed())
        .mouseSeed(spec.mouseSeed())
        .maxSteps(budgets == null ? -1L : budgets.maxSteps())
        .maxTicks(budgets == null ? -1L : budgets.maxTicks())
        .maxIntents(budgets == null ? -1L : budgets.maxIntents())
        .maxWallTimeMillis(budgets == null ? -1L : budgets.maxWallTimeMillis())
        .maxMemoryBytes(budgets == null ? -1L : budgets.maxMemoryBytes())
        .maxOutputBytes(budgets == null ? -1L : budgets.maxOutputBytes())
        .build();
  }

  private Map<String, Object> job(
      CandidateExecutionRequestV2 request, JobSpec spec, Path runDirectory) throws IOException {
    Map<String, Object> worker = new LinkedHashMap<>();
    worker.put("command", properties.getWorkerCommand());
    worker.put(
        "directory",
        properties.getWorkerDirectory().isBlank() ? null : properties.getWorkerDirectory());
    worker.put("developmentIdentity", properties.isDevelopmentWorker());
    Map<String, Object> job = new LinkedHashMap<>();
    job.put("schema", "sc2106-host-job/v1");
    job.put("request", request.toMap());
    job.put("task", spec.task());
    job.put("start", spec.start());
    job.put("worker", worker);
    job.put("storeRoot", directory(properties.getStoreRoot(), "storeRoot").toString());
    job.put("workspaceRoot", directory(properties.getWorkspaceRoot(), "workspaceRoot").toString());
    job.put("workingDirectory", runDirectory.toString());
    job.put("environment", properties.getChildEnvironment());
    return job;
  }

  static ExecutionJob interpret(
      String jobId,
      String attemptId,
      CandidateExecutionRequestV2 request,
      CandidateHostLauncher.HostOutcome outcome) {
    Map<String, Object> document = outcome.document();
    if (document == null) {
      // A host that never received the job ran nothing; one that was lost may have.
      return new ExecutionJob(
          jobId,
          attemptId,
          request.requestDigest(),
          request.toMap(),
          outcome.lost()
              ? CandidateExecutionResultValidator.Outcome.UNCERTAIN
              : CandidateExecutionResultValidator.Outcome.FAILED,
          List.of(String.valueOf(outcome.detail())),
          false,
          null,
          null);
    }
    if (!"sc2106-host-outcome/v1".equals(document.get("schema"))) {
      return new ExecutionJob(
          jobId,
          attemptId,
          request.requestDigest(),
          request.toMap(),
          CandidateExecutionResultValidator.Outcome.UNCERTAIN,
          List.of("HOST_OUTCOME_SCHEMA_UNKNOWN"),
          false,
          null,
          document);
    }
    if (document.get("rejected") instanceof Map<?, ?> rejected) {
      // The host refuses a job before resolving it, so nothing was launched.
      return new ExecutionJob(
          jobId,
          attemptId,
          request.requestDigest(),
          request.toMap(),
          CandidateExecutionResultValidator.Outcome.FAILED,
          List.of("HOST_REJECTED:" + rejected.get("failureCode")),
          false,
          null,
          document);
    }
    @SuppressWarnings("unchecked")
    Map<String, Object> result =
        document.get("result") instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    CandidateExecutionResultValidator.Verdict verdict =
        CandidateExecutionResultValidator.validate(result, request);
    List<String> reasons = new ArrayList<>(verdict.reasons());
    boolean hostCertifiable = false;
    if (document.get("certification") instanceof Map<?, ?> certification) {
      hostCertifiable = Boolean.TRUE.equals(certification.get("certifiable"));
      if (certification.get("reasons") instanceof List<?> hostReasons) {
        for (Object reason : hostReasons) {
          String text = "HOST_CERTIFICATION:" + reason;
          if (!reasons.contains(text)) {
            reasons.add(text);
          }
        }
      }
    }
    return new ExecutionJob(
        jobId,
        attemptId,
        request.requestDigest(),
        request.toMap(),
        verdict.outcome(),
        reasons,
        verdict.certifiable() && hostCertifiable,
        result,
        document);
  }

  private CandidateHostLauncher launcher() throws IOException {
    if (host != null) {
      return host;
    }
    return new ProcessCandidateHostLauncher(
        properties.getHostCommand(),
        properties.getHostEnvironment(),
        directory(properties.getRunRoot(), "runRoot"));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> settings(Map<String, Object> task) {
    if (task == null || !(task.get("settings") instanceof Map<?, ?> settings)) {
      throw new IllegalArgumentException("REQUEST_INVALID: task.settings is required");
    }
    return (Map<String, Object>) settings;
  }

  /** Writes {@code bytes} under its SHA-256 name; returns {@code sha256:<hex>}. */
  static String put(Path store, byte[] bytes) throws IOException {
    String hex = HexFormat.of().formatHex(Sc967CanonicalJson.digest(bytes));
    Path target = store.resolve(hex);
    if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
      Path temporary = Files.createTempFile(store, ".staging-", ".tmp");
      try {
        Files.write(temporary, bytes);
        try {
          Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
          Files.move(temporary, target);
        }
      } catch (FileAlreadyExistsException e) {
        // Another job stored the same bytes first; it is verified below like any existing entry.
      } finally {
        Files.deleteIfExists(temporary);
      }
    }
    if (Files.isSymbolicLink(target)
        || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
        || !hex.equals(
            HexFormat.of().formatHex(Sc967CanonicalJson.digest(Files.readAllBytes(target))))) {
      throw new IOException("STORE_ENTRY_CORRUPT: " + hex);
    }
    return "sha256:" + hex;
  }

  private static Path directory(String configured, String name) throws IOException {
    if (configured == null || configured.isBlank()) {
      throw new IOException("sc2106.execution." + name + " is not configured");
    }
    Path path = Path.of(configured).toAbsolutePath().normalize();
    Files.createDirectories(path);
    return path;
  }

  private static PatchCandidateWorkflowService.PatchWorkflowException failure(
      HttpStatus status, String code, String message) {
    return new PatchCandidateWorkflowService.PatchWorkflowException(status, code, message);
  }
}
