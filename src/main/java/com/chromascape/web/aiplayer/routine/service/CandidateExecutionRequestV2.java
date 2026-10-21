package com.chromascape.web.aiplayer.routine.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Producer side of {@code sc814-candidate-execution-request/v2} (SC-2106 R1.8/R9.1).
 *
 * <p>The request is immutable once built and names exactly one approved, registered candidate: its
 * proposal and approval digests, base commit, candidate hash, the signed manifest and execution
 * closure by content digest, the runner/adapter/protocol identities and the closure's package, core
 * and dependency digests. The rules mirror Micro6's {@code CandidateExecutionRequest}, and the
 * digest is {@link Sc2106CanonicalJson#sha256} of {@link #canonicalBody()}, so the consumer can
 * recompute it from the wire map.
 */
public final class CandidateExecutionRequestV2 {
  public static final String SCHEMA = "sc814-candidate-execution-request/v2";
  public static final int SCHEMA_VERSION = 2;
  public static final String CAUSAL_CANDIDATE = "CAUSAL_CANDIDATE";
  public static final String FIXTURE_TEST = "FIXTURE_TEST";

  private static final Pattern DIGEST = Pattern.compile("sha256:[0-9a-f]{64}");
  private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{7,64}");

  private final Map<String, Object> body;

  private CandidateExecutionRequestV2(Builder b) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("schemaVersion", SCHEMA_VERSION);
    map.put("requestId", required(b.requestId, "requestId"));
    map.put("idempotencyKey", required(b.idempotencyKey, "idempotencyKey"));
    map.put("certificationRunId", required(b.certificationRunId, "certificationRunId"));
    map.put("attemptId", required(b.attemptId, "attemptId"));
    map.put("jobId", required(b.jobId, "jobId"));
    map.put("proposalDigest", digest(b.proposalDigest, "proposalDigest"));
    map.put("approvalDigest", digest(b.approvalDigest, "approvalDigest"));
    String baseCommit = required(b.baseCommit, "baseCommit");
    if (!COMMIT.matcher(baseCommit).matches()) {
      throw invalid("baseCommit must be a lowercase git object id");
    }
    map.put("baseCommit", baseCommit);
    map.put("candidateDigest", digest(b.candidateDigest, "candidateDigest"));
    map.put("packageDigest", digest(b.packageDigest, "packageDigest"));
    map.put("coreDigest", digest(b.coreDigest, "coreDigest"));
    map.put("signedManifestRef", digest(b.signedManifestRef, "signedManifestRef"));
    map.put("executionClosureRef", digest(b.executionClosureRef, "executionClosureRef"));
    map.put("verifierPolicyVersion", required(b.verifierPolicyVersion, "verifierPolicyVersion"));
    map.put("taskCoreEntry", required(b.taskCoreEntry, "taskCoreEntry"));
    map.put("runnerVersion", required(b.runnerVersion, "runnerVersion"));
    map.put("adapterVersion", required(b.adapterVersion, "adapterVersion"));
    map.put("protocolVersion", required(b.protocolVersion, "protocolVersion"));
    List<String> closure = new ArrayList<>();
    Set<String> unique = new HashSet<>();
    for (String dependency : b.dependencyClosure) {
      String value = digest(dependency, "dependencyClosure");
      if (!unique.add(value)) {
        throw invalid("dependency closure contains a duplicate digest");
      }
      closure.add(value);
    }
    map.put("dependencyClosure", List.copyOf(closure));
    map.put("baselineId", required(b.baselineId, "baselineId"));
    map.put("scenarioId", required(b.scenarioId, "scenarioId"));
    map.put(
        "checkpointId", b.checkpointId == null || b.checkpointId.isEmpty() ? null : b.checkpointId);
    map.put("contentVersion", required(b.contentVersion, "contentVersion"));
    map.put("cacheVersion", required(b.cacheVersion, "cacheVersion"));
    map.put("profileId", required(b.profileId, "profileId"));
    map.put("recordingId", required(b.recordingId, "recordingId"));
    map.put("initialProvenance", required(b.initialProvenance, "initialProvenance"));
    map.put("taskHash", digest(b.taskHash, "taskHash"));
    map.put("settingsHash", digest(b.settingsHash, "settingsHash"));
    String mode = required(b.executionMode, "executionMode");
    if (!CAUSAL_CANDIDATE.equals(mode) && !FIXTURE_TEST.equals(mode)) {
      throw invalid("unsupported execution mode " + mode);
    }
    map.put("executionMode", mode);
    map.put("worldSeed", required(b.worldSeed, "worldSeed"));
    map.put("taskSeed", required(b.taskSeed, "taskSeed"));
    map.put("mouseSeed", required(b.mouseSeed, "mouseSeed"));
    map.put("maxSteps", budget(b.maxSteps, "maxSteps"));
    map.put("maxTicks", budget(b.maxTicks, "maxTicks"));
    map.put("maxIntents", budget(b.maxIntents, "maxIntents"));
    map.put("maxWallTimeMillis", budget(b.maxWallTimeMillis, "maxWallTimeMillis"));
    map.put("maxMemoryBytes", budget(b.maxMemoryBytes, "maxMemoryBytes"));
    map.put("maxOutputBytes", budget(b.maxOutputBytes, "maxOutputBytes"));
    if (b.maxSteps < 0 && b.maxTicks < 0 && b.maxWallTimeMillis < 0) {
      throw invalid("at least one of maxSteps, maxTicks or maxWallTimeMillis is required");
    }
    map.put("cancellationPolicy", required(b.cancellationPolicy, "cancellationPolicy"));
    map.put("offlineAuthority", true);
    this.body = Collections.unmodifiableMap(map);
  }

  public static Builder builder() {
    return new Builder();
  }

  /** The fields the digest covers, in a fresh mutable map. */
  public Map<String, Object> canonicalBody() {
    return new LinkedHashMap<>(body);
  }

  public String requestDigest() {
    return Sc2106CanonicalJson.sha256(body);
  }

  /** The wire form: the canonical body plus its {@code requestDigest}. */
  public Map<String, Object> toMap() {
    Map<String, Object> map = canonicalBody();
    map.put("requestDigest", requestDigest());
    return map;
  }

  public String text(String field) {
    Object value = body.get(field);
    return value == null ? null : value.toString();
  }

  /** A budget field; {@code -1} means unbounded. */
  public long budgetOf(String field) {
    return (Long) body.get(field);
  }

  @SuppressWarnings("unchecked")
  public List<String> dependencyClosure() {
    return (List<String>) body.get("dependencyClosure");
  }

  private static String required(String value, String field) {
    if (value == null || value.isEmpty()) {
      throw invalid(field + " is required");
    }
    return value;
  }

  private static String digest(String value, String field) {
    if (value == null || !DIGEST.matcher(value).matches()) {
      throw invalid(field + " must be a sha256:<64 lowercase hex> digest");
    }
    return value;
  }

  private static long budget(long value, String field) {
    if (value < -1L) {
      throw invalid(field + " must be -1 or nonnegative");
    }
    return value;
  }

  private static IllegalArgumentException invalid(String detail) {
    return new IllegalArgumentException("REQUEST_INVALID: " + detail);
  }

  /** Collects the fields; {@link #build()} validates them all. */
  public static final class Builder {
    private String requestId;
    private String idempotencyKey;
    private String certificationRunId;
    private String attemptId;
    private String jobId;
    private String proposalDigest;
    private String approvalDigest;
    private String baseCommit;
    private String candidateDigest;
    private String packageDigest;
    private String coreDigest;
    private String signedManifestRef;
    private String executionClosureRef;
    private String verifierPolicyVersion;
    private String taskCoreEntry;
    private String runnerVersion;
    private String adapterVersion;
    private String protocolVersion;
    private List<String> dependencyClosure = List.of();
    private String baselineId;
    private String scenarioId;
    private String checkpointId;
    private String contentVersion;
    private String cacheVersion;
    private String profileId;
    private String recordingId;
    private String initialProvenance;
    private String taskHash;
    private String settingsHash;
    private String executionMode = CAUSAL_CANDIDATE;
    private String worldSeed;
    private String taskSeed;
    private String mouseSeed;
    private long maxSteps = -1L;
    private long maxTicks = -1L;
    private long maxIntents = -1L;
    private long maxWallTimeMillis = -1L;
    private long maxMemoryBytes = -1L;
    private long maxOutputBytes = -1L;
    private String cancellationPolicy = "stop-and-preserve";

    private Builder() {}

    public Builder requestId(String value) {
      this.requestId = value;
      return this;
    }

    public Builder idempotencyKey(String value) {
      this.idempotencyKey = value;
      return this;
    }

    public Builder certificationRunId(String value) {
      this.certificationRunId = value;
      return this;
    }

    public Builder attemptId(String value) {
      this.attemptId = value;
      return this;
    }

    public Builder jobId(String value) {
      this.jobId = value;
      return this;
    }

    public Builder proposalDigest(String value) {
      this.proposalDigest = value;
      return this;
    }

    public Builder approvalDigest(String value) {
      this.approvalDigest = value;
      return this;
    }

    public Builder baseCommit(String value) {
      this.baseCommit = value;
      return this;
    }

    public Builder candidateDigest(String value) {
      this.candidateDigest = value;
      return this;
    }

    public Builder packageDigest(String value) {
      this.packageDigest = value;
      return this;
    }

    public Builder coreDigest(String value) {
      this.coreDigest = value;
      return this;
    }

    public Builder signedManifestRef(String value) {
      this.signedManifestRef = value;
      return this;
    }

    public Builder executionClosureRef(String value) {
      this.executionClosureRef = value;
      return this;
    }

    public Builder verifierPolicyVersion(String value) {
      this.verifierPolicyVersion = value;
      return this;
    }

    public Builder taskCoreEntry(String value) {
      this.taskCoreEntry = value;
      return this;
    }

    public Builder runnerVersion(String value) {
      this.runnerVersion = value;
      return this;
    }

    public Builder adapterVersion(String value) {
      this.adapterVersion = value;
      return this;
    }

    public Builder protocolVersion(String value) {
      this.protocolVersion = value;
      return this;
    }

    public Builder dependencyClosure(List<String> value) {
      this.dependencyClosure = value == null ? List.of() : List.copyOf(value);
      return this;
    }

    public Builder baselineId(String value) {
      this.baselineId = value;
      return this;
    }

    public Builder scenarioId(String value) {
      this.scenarioId = value;
      return this;
    }

    public Builder checkpointId(String value) {
      this.checkpointId = value;
      return this;
    }

    public Builder contentVersion(String value) {
      this.contentVersion = value;
      return this;
    }

    public Builder cacheVersion(String value) {
      this.cacheVersion = value;
      return this;
    }

    public Builder profileId(String value) {
      this.profileId = value;
      return this;
    }

    public Builder recordingId(String value) {
      this.recordingId = value;
      return this;
    }

    public Builder initialProvenance(String value) {
      this.initialProvenance = value;
      return this;
    }

    public Builder taskHash(String value) {
      this.taskHash = value;
      return this;
    }

    public Builder settingsHash(String value) {
      this.settingsHash = value;
      return this;
    }

    public Builder executionMode(String value) {
      this.executionMode = value;
      return this;
    }

    public Builder worldSeed(String value) {
      this.worldSeed = value;
      return this;
    }

    public Builder taskSeed(String value) {
      this.taskSeed = value;
      return this;
    }

    public Builder mouseSeed(String value) {
      this.mouseSeed = value;
      return this;
    }

    public Builder maxSteps(long value) {
      this.maxSteps = value;
      return this;
    }

    public Builder maxTicks(long value) {
      this.maxTicks = value;
      return this;
    }

    public Builder maxIntents(long value) {
      this.maxIntents = value;
      return this;
    }

    public Builder maxWallTimeMillis(long value) {
      this.maxWallTimeMillis = value;
      return this;
    }

    public Builder maxMemoryBytes(long value) {
      this.maxMemoryBytes = value;
      return this;
    }

    public Builder maxOutputBytes(long value) {
      this.maxOutputBytes = value;
      return this;
    }

    public Builder cancellationPolicy(String value) {
      this.cancellationPolicy = value;
      return this;
    }

    public CandidateExecutionRequestV2 build() {
      return new CandidateExecutionRequestV2(this);
    }
  }
}
