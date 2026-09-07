package com.chromascape.web.aiplayer.contract;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Base64;
import java.util.Objects;
import java.util.regex.Pattern;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record CurrentSliceIdentity(
    @JsonProperty("schema") String schema,
    @JsonProperty("task") Task task,
    @JsonProperty("run") Run run,
    @JsonProperty("client") Client client,
    @JsonProperty("capture") Capture capture,
    @JsonProperty("source") Source source,
    @JsonProperty("graph") Graph graph,
    @JsonProperty("model") Model model,
    @JsonProperty("candidate") Candidate candidate,
    @JsonProperty("control_allowed") boolean controlAllowed) {

  private static final Pattern HASH = Pattern.compile("sha256:[0-9a-f]{64}");
  private static final Pattern SNAPSHOT = Pattern.compile("srcsnap_[0-9a-f]{24}");
  private static final Pattern GRAPH = Pattern.compile("graph_[0-9a-f]{24}");
  private static final Pattern MODEL = Pattern.compile("model_[0-9a-f]{24}");
  private static final Pattern CANDIDATE = Pattern.compile("cand_[0-9a-f]{24}");

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Task(
      @JsonProperty("task_key") String taskKey,
      @JsonProperty("settings_hash") String settingsHash,
      @JsonProperty("profile_id") String profileId,
      @JsonProperty("profile_hash") String profileHash,
      @JsonProperty("catalog_hash") String catalogHash) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Run(
      @JsonProperty("run_id") String runId,
      @JsonProperty("attempt_id") String attemptId,
      @JsonProperty("entry_id") Integer entryId,
      @JsonProperty("executor_instance_id") String executorInstanceId) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Client(
      @JsonProperty("client_id") String clientId,
      @JsonProperty("client_kind") String clientKind,
      @JsonProperty("client_build_id") String clientBuildId,
      @JsonProperty("artifact_digest") String artifactDigest,
      @JsonProperty("adapter_contract") String adapterContract,
      @JsonProperty("domain") String domain) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Capture(
      @JsonProperty("session_id") String sessionId,
      @JsonProperty("recording_id") String recordingId,
      @JsonProperty("recording_hash") String recordingHash) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Source(
      @JsonProperty("source_snapshot_id") String sourceSnapshotId,
      @JsonProperty("source_manifest_hash") String sourceManifestHash,
      @JsonProperty("repository_id") String repositoryId,
      @JsonProperty("commit_id") String commitId,
      @JsonProperty("tree_id") String treeId,
      @JsonProperty("build_id") String buildId,
      @JsonProperty("artifact_digest") String artifactDigest,
      @JsonProperty("signature_verified") Boolean signatureVerified) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Graph(
      @JsonProperty("graph_id") String graphId, @JsonProperty("graph_hash") String graphHash) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Model(
      @JsonProperty("model_id") String modelId, @JsonProperty("model_hash") String modelHash) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Candidate(
      @JsonProperty("candidate_id") String candidateId,
      @JsonProperty("candidate_hash") String candidateHash,
      @JsonProperty("certification_hash") String certificationHash,
      @JsonProperty("signature") String signature) {}

  public void validate() {
    if (!"current-slice-identity/v1".equals(schema))
      fail(
          CurrentSliceFailureCode.IDENTITY_FIELD_MISSING,
          "schema must be current-slice-identity/v1");
    if (task == null)
      fail(CurrentSliceFailureCode.IDENTITY_GROUP_MISSING, "task group is required");
    required(task.taskKey(), "task.task_key");
    hash(task.settingsHash(), "task.settings_hash");
    required(task.profileId(), "task.profile_id");
    hash(task.profileHash(), "task.profile_hash");
    hash(task.catalogHash(), "task.catalog_hash");
    if (client == null)
      fail(CurrentSliceFailureCode.IDENTITY_GROUP_MISSING, "client group is required");
    required(client.clientId(), "client.client_id");
    required(client.clientKind(), "client.client_kind");
    required(client.clientBuildId(), "client.client_build_id");
    hash(client.artifactDigest(), "client.artifact_digest");
    required(client.adapterContract(), "client.adapter_contract");
    required(client.domain(), "client.domain");
    // RuneLite capture runs may be created before a run id is allocated.  Micro6-managed
    // identities are the stricter form and require the executor-bound run group below.
    if (run == null && !"RUNELITE_CAPTURE".equals(client.clientKind())) {
      fail(CurrentSliceFailureCode.IDENTITY_GROUP_MISSING, "run group is required");
    }
    if (run != null) required(run.runId(), "run.run_id");
    if ("MICRO6_MANAGED".equals(client.clientKind())) {
      if (run == null)
        fail(CurrentSliceFailureCode.IDENTITY_GROUP_MISSING, "run group is required for Micro6");
      required(run.attemptId(), "run.attempt_id");
      if (run.entryId() == null || run.entryId() < 1)
        fail(
            CurrentSliceFailureCode.IDENTITY_FIELD_MISSING,
            "run.entry_id must be in 1..2147483647");
      required(run.executorInstanceId(), "run.executor_instance_id");
      exact(client.adapterContract(), "micro6-builder/v2", "client.adapter_contract");
      exact(client.domain(), "MICRO6", "client.domain");
    } else if ("RUNELITE_CAPTURE".equals(client.clientKind())) {
      if (run != null
          && (run.attemptId() != null || run.entryId() != null || run.executorInstanceId() != null))
        fail(
            CurrentSliceFailureCode.CLIENT_CAPABILITY_INVALID,
            "RuneLite capture cannot carry Micro6 executor fields");
      exact(client.adapterContract(), "runelite-capture/v1", "client.adapter_contract");
      exact(client.domain(), "RUNELITE", "client.domain");
    } else fail(CurrentSliceFailureCode.CLIENT_CAPABILITY_INVALID, "unsupported client_kind");
    if (capture != null) {
      required(capture.sessionId(), "capture.session_id");
      required(capture.recordingId(), "capture.recording_id");
      hash(capture.recordingHash(), "capture.recording_hash");
    }
    if (source != null) {
      if (!matches(SNAPSHOT, source.sourceSnapshotId()))
        fail(CurrentSliceFailureCode.HASH_FORMAT_INVALID, "invalid source_snapshot_id");
      hash(source.sourceManifestHash(), "source.source_manifest_hash");
      required(source.repositoryId(), "source.repository_id");
      required(source.commitId(), "source.commit_id");
      required(source.treeId(), "source.tree_id");
      required(source.buildId(), "source.build_id");
      hash(source.artifactDigest(), "source.artifact_digest");
      if (!source.repositoryId().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,199}")) {
        fail(CurrentSliceFailureCode.IDENTITY_FIELD_MISSING, "source.repository_id is invalid");
      }
      if (!source.commitId().matches("[0-9a-f]{7,64}")
          || !source.treeId().matches("[0-9a-f]{7,64}")) {
        fail(CurrentSliceFailureCode.HASH_FORMAT_INVALID, "source commit/tree identity is invalid");
      }
      if (!Boolean.TRUE.equals(source.signatureVerified())) {
        fail(
            CurrentSliceFailureCode.IDENTITY_FIELD_MISSING,
            "source.signature_verified must be true");
      }
    }
    if (graph != null) {
      if (!matches(GRAPH, graph.graphId()))
        fail(CurrentSliceFailureCode.HASH_FORMAT_INVALID, "invalid graph_id");
      hash(graph.graphHash(), "graph.graph_hash");
    }
    if (model != null) {
      if (!matches(MODEL, model.modelId()))
        fail(CurrentSliceFailureCode.HASH_FORMAT_INVALID, "invalid model_id");
      hash(model.modelHash(), "model.model_hash");
    }
    if (candidate != null) {
      if (!matches(CANDIDATE, candidate.candidateId()))
        fail(CurrentSliceFailureCode.HASH_FORMAT_INVALID, "invalid candidate_id");
      hash(candidate.candidateHash(), "candidate.candidate_hash");
      hash(candidate.certificationHash(), "candidate.certification_hash");
      required(candidate.signature(), "candidate.signature");
      try {
        Base64.getDecoder().decode(candidate.signature());
      } catch (IllegalArgumentException e) {
        fail(CurrentSliceFailureCode.HASH_FORMAT_INVALID, "candidate.signature is not base64");
      }
    }
    if (controlAllowed)
      fail(CurrentSliceFailureCode.CONTROL_FORBIDDEN, "control_allowed must be false");
  }

  /**
   * Checks progressive identity enrichment. Every group already present upstream must be byte/value
   * equal downstream; downstream may add capture, graph, model, or candidate groups.
   */
  public void requireSameIdentity(CurrentSliceIdentity downstream) {
    validate();
    if (downstream == null)
      fail(CurrentSliceFailureCode.IDENTITY_MISMATCH, "downstream identity is missing");
    downstream.validate();
    if (!Objects.equals(schema, downstream.schema)
        || !Objects.equals(task, downstream.task)
        || !sameIfPresent(run, downstream.run)
        || !Objects.equals(client, downstream.client)
        || !sameIfPresent(capture, downstream.capture)
        || !sameSourceIfPresent(source, downstream.source)
        || !sameIfPresent(graph, downstream.graph)
        || !sameIfPresent(model, downstream.model)
        || !sameIfPresent(candidate, downstream.candidate)) {
      fail(CurrentSliceFailureCode.IDENTITY_MISMATCH, "identity groups do not match");
    }
  }

  private static boolean sameIfPresent(Object upstream, Object downstream) {
    return upstream == null || Objects.equals(upstream, downstream);
  }

  /** Source provenance may be progressively enriched after a run is created, but never changed. */
  private static boolean sameSourceIfPresent(Source upstream, Source downstream) {
    if (upstream == null) return true;
    if (downstream == null) return false;
    return sameField(upstream.sourceSnapshotId(), downstream.sourceSnapshotId())
        && sameField(upstream.sourceManifestHash(), downstream.sourceManifestHash())
        && sameField(upstream.repositoryId(), downstream.repositoryId())
        && sameField(upstream.commitId(), downstream.commitId())
        && sameField(upstream.treeId(), downstream.treeId())
        && sameField(upstream.buildId(), downstream.buildId())
        && sameField(upstream.artifactDigest(), downstream.artifactDigest())
        && (upstream.signatureVerified() == null
            || Objects.equals(upstream.signatureVerified(), downstream.signatureVerified()));
  }

  private static boolean sameField(String upstream, String downstream) {
    return upstream == null || Objects.equals(upstream, downstream);
  }

  private static boolean matches(Pattern pattern, String value) {
    return value != null && pattern.matcher(value).matches();
  }

  private static void required(String value, String field) {
    if (value == null || value.isBlank())
      fail(CurrentSliceFailureCode.IDENTITY_FIELD_MISSING, field + " is required");
  }

  private static void hash(String value, String field) {
    if (!matches(HASH, value))
      fail(
          CurrentSliceFailureCode.HASH_FORMAT_INVALID,
          field + " must be sha256:<64 lowercase hex>");
  }

  private static void exact(String actual, String expected, String field) {
    if (!expected.equals(actual))
      fail(CurrentSliceFailureCode.CLIENT_CAPABILITY_INVALID, field + " must be " + expected);
  }

  private static void fail(CurrentSliceFailureCode code, String message) {
    throw new CurrentSliceContractException(code, message);
  }
}
