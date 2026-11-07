package com.chromascape.web.recording.provenance;

import com.chromascape.web.aiplayer.catalog.CatalogStore;
import com.chromascape.web.debug.tickdebugger.entity.DebugSession;
import com.chromascape.web.debug.tickdebugger.entity.DebugSessionLifecycleState;
import com.chromascape.web.debug.tickdebugger.entity.DebugSessionRepository;
import com.chromascape.web.integration.micro6.service.Micro6CanonicalHash;
import com.chromascape.web.recording.entity.RecordingSession;
import com.chromascape.web.recording.entity.RecordingSessionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** One durable capture context consumed by training, replay and read-only scoring. */
@Service
public class BehaviorRunContextService {
  private static final int MAX_DIAGNOSTIC_FIELDS = 16;
  private static final int MAX_DIAGNOSTIC_DEPTH = 3;
  private final CatalogStore profiles;
  private final RecordingSessionRepository recordings;
  private final DebugSessionRepository sessions;
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;

  public BehaviorRunContextService(
      CatalogStore profiles,
      RecordingSessionRepository recordings,
      DebugSessionRepository sessions,
      JdbcTemplate jdbc,
      ObjectMapper json) {
    this.profiles = profiles;
    this.recordings = recordings;
    this.sessions = sessions;
    this.jdbc = jdbc;
    this.json = json;
  }

  /** Resolve and freeze a historical profile before any recorder side effects. */
  public ObjectNode snapshot(JsonNode reference, RecordingProvenance provenance, String account) {
    if (reference == null || reference.isNull()) return null;
    if (!reference.isObject()) fail("INVALID_RUN_CONTEXT");
    for (String field :
        new String[] {
          "runId", "allocationId", "executorId", "clientInstanceId", "behaviorProfileId"
        }) required(reference, field);
    try {
      if (!UUID.fromString(reference.path("runId").asText())
          .toString()
          .equals(reference.path("runId").asText())) fail("INVALID_RUN_ID");
    } catch (Exception e) {
      fail("INVALID_RUN_ID");
    }
    if (!reference.path("behaviorProfileVersion").isIntegralNumber()
        || !reference.path("behaviorProfileVersion").canConvertToInt()
        || reference.path("behaviorProfileVersion").asInt() < 1) fail("INVALID_PROFILE_VERSION");
    BehaviorSemanticContract.requireDigest(required(reference, "semanticContractDigest"));
    if (account == null || account.isBlank()) fail("ACCOUNT_REQUIRED");
    JsonNode profile =
        profiles.profile(
            reference.path("behaviorProfileId").asText(),
            reference.path("behaviorProfileVersion").asInt());
    if (!profile.path("task_key").asText().equals(provenance.taskKey())
        || provenance.taskArgs() == null
        || !profile.path("settings_hash").asText().equals(provenance.settingsHash()))
      fail("PROFILE_SETTINGS_MISMATCH");
    if (!profile
        .path("settings_hash")
        .asText()
        .equals(Micro6CanonicalHash.sha256WithPrefix(profile.path("settings_json"))))
      fail("PROFILE_HASH_MISMATCH");
    ObjectNode context = json.createObjectNode().put("schemaVersion", 1);
    for (String field :
        new String[] {
          "runId",
          "allocationId",
          "executorId",
          "clientInstanceId",
          "behaviorProfileId",
          "behaviorProfileVersion",
          "semanticContractDigest"
        }) context.set(field, reference.get(field).deepCopy());
    String origin = reference.path("dataOrigin").asText("UNKNOWN");
    if (!java.util.Set.of("HUMAN", "PROVIDER", "SCRIPT", "UNKNOWN").contains(origin))
      fail("INVALID_DATA_ORIGIN");
    context.put("dataOrigin", origin);
    if (origin.equals("PROVIDER")) {
      ObjectNode provider = context.putObject("providerAttribution");
      JsonNode attribution = reference.path("providerAttribution");
      for (String field :
          new String[] {"provider", "model", "approvalScope", "eligibilityContextDigest"})
        provider.put(field, required(attribution, field));
    }
    context
        .put("accountName", account)
        .put("taskKey", provenance.taskKey())
        .put("settingsHash", provenance.settingsHash());
    context.set("settings", provenance.taskArgs());
    context.set("profileSnapshot", profile.deepCopy());
    freezePublishedGraph(reference, profile, context);
    context
        .put("catalogHash", profile.path("catalog_hash").asText())
        .put("catalogVersion", profile.path("catalog_version").asText());
    context.set("requirements", profile.path("requirements_json").deepCopy());
    context.set("categoryMetadata", profile.path("task_snapshot").deepCopy());
    context.set("privacy", reference.path("privacy").deepCopy());
    if (!context.path("privacy").isObject()
        || !context.path("privacy").path("policyVersion").isTextual()
        || !context.path("privacy").path("redactedFields").isArray())
      fail("PRIVACY_POLICY_REQUIRED");
    RecordingProvenance.requireNoSecrets(context);
    if (context.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 2_000_000)
      fail("RUN_CONTEXT_TOO_LARGE");
    return context;
  }

  /** Freeze an explicitly selected historical publication, never whatever graph is latest now. */
  private void freezePublishedGraph(JsonNode reference, JsonNode profile, ObjectNode context) {
    if (!reference.has("graphId")
        && !reference.has("graphVersion")
        && !reference.has("graphRevision")) return;
    String graphId = required(reference, "graphId");
    String revision = required(reference, "graphRevision");
    if (!revision.matches("sha256:[0-9a-f]{64}")
        || !reference.path("graphVersion").isIntegralNumber()
        || !reference.path("graphVersion").canConvertToLong()
        || reference.path("graphVersion").asLong() < 1) fail("INVALID_GRAPH_REFERENCE");
    java.util.List<Map<String, Object>> rows;
    try {
      rows =
          jdbc.queryForList(
              "SELECT graph_hash,definition_schema,definition_json FROM behavior_graph_publications"
                  + " WHERE graph_id=? AND graph_version=?",
              graphId,
              reference.path("graphVersion").asLong());
    } catch (org.springframework.dao.DataAccessException e) {
      throw new ProvenanceException("GRAPH_SNAPSHOT_UNAVAILABLE");
    }
    if (rows.size() != 1) fail("GRAPH_PUBLICATION_NOT_FOUND");
    Map<String, Object> row = rows.get(0);
    if (!revision.equals(row.get("graph_hash"))
        || !"behavior-graph-definition/v2".equals(row.get("definition_schema")))
      fail("GRAPH_REVISION_MISMATCH");
    JsonNode definition;
    try {
      definition = json.readTree(String.valueOf(row.get("definition_json")));
      new com.chromascape.web.aiplayer.routine.service.GraphSchemaValidator()
          .validateDefinitionV2(definition);
    } catch (java.io.IOException | IllegalArgumentException e) {
      throw new ProvenanceException("INVALID_GRAPH_PUBLICATION");
    }
    if (!graphId.equals(definition.path("graph_id").asText())
        || !revision.equals(definition.path("graph_hash").asText())
        || !revision.equals(
            new com.chromascape.web.aiplayer.routine.service.BehaviorGraphV2Canonicalizer()
                .graphHash(definition))) fail("GRAPH_REVISION_MISMATCH");
    if (!profile.path("task_key").asText().equals(definition.path("task").path("task_key").asText())
        || !profile
            .path("profile_id")
            .asText()
            .equals(definition.path("profile").path("profile_id").asText())
        || !profile
            .path("profile_version")
            .asText()
            .equals(definition.path("profile").path("profile_version").asText()))
      fail("GRAPH_PROFILE_MISMATCH");
    context
        .put("graphId", graphId)
        .put("graphVersion", reference.path("graphVersion").asLong())
        .put("graphRevision", revision)
        .put("graphSchemaVersion", "behavior-graph-definition/v2");
    context.set("graphSnapshot", definition.deepCopy());
  }

  public ObjectNode bind(ObjectNode snapshot, String recordingId, String sessionId) {
    if (snapshot == null) return null;
    ObjectNode out = snapshot.deepCopy();
    out.put("recordingId", recordingId).put("debugSessionId", sessionId);
    out.put("contextHash", Micro6CanonicalHash.sha256WithPrefix(out));
    return out;
  }

  /**
   * Whether a freshly bound context and a stored one are the same immutable capture.
   *
   * <p>The identity of a capture context is its canonical digest, and canonical JSON normalizes
   * numbers ({@code 1} and {@code 1.0} hash identically). Comparing raw trees instead would refuse
   * a start whose only difference is that the executor round-tripped the reference through floating
   * point, while every consumer downstream still sees one identity. No value is ever treated as
   * equal unless the canonical digests agree, and a stored digest that no longer verifies is never
   * accepted.
   */
  public boolean sameProvenance(JsonNode expected, JsonNode stored) {
    if (expected == null || stored == null) return expected == stored;
    if (!(expected instanceof ObjectNode expectedContext)
        || !(stored instanceof ObjectNode storedContext)) return false;
    if (!digestVerifies(expectedContext) || !digestVerifies(storedContext)) return false;
    return expectedContext
        .path("contextHash")
        .asText()
        .equals(storedContext.path("contextHash").asText());
  }

  /**
   * Names the differing fields of two contexts for a bounded refusal diagnostic. Only field paths
   * are returned, never their values.
   */
  public List<String> differingPaths(JsonNode expected, JsonNode stored) {
    List<String> differing = new ArrayList<>();
    collectDifferences(expected, stored, "", differing, 0);
    return differing;
  }

  private static boolean digestVerifies(ObjectNode context) {
    String hash = context.path("contextHash").asText();
    if (hash.isBlank()) return false;
    ObjectNode content = context.deepCopy();
    content.remove("contextHash");
    return hash.equals(Micro6CanonicalHash.sha256WithPrefix(content));
  }

  private static void collectDifferences(
      JsonNode expected, JsonNode stored, String path, List<String> out, int depth) {
    if (out.size() >= MAX_DIAGNOSTIC_FIELDS) return;
    if (expected != null && stored != null && expected.isObject() && stored.isObject()) {
      if (depth >= MAX_DIAGNOSTIC_DEPTH) {
        if (!equivalent(expected, stored)) out.add(label(path));
        return;
      }
      java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
      expected.fieldNames().forEachRemaining(names::add);
      stored.fieldNames().forEachRemaining(names::add);
      for (String name : names) {
        if (out.size() >= MAX_DIAGNOSTIC_FIELDS) return;
        collectDifferences(
            expected.get(name),
            stored.get(name),
            path.isEmpty() ? name : path + "." + name,
            out,
            depth + 1);
      }
      return;
    }
    if (!equivalent(expected, stored)) out.add(label(path));
  }

  /** Canonical equality for the diagnostic: numbers by value, arrays by canonical digest. */
  private static boolean equivalent(JsonNode expected, JsonNode stored) {
    if (expected == null || stored == null) return expected == stored;
    if (expected.isNumber() && stored.isNumber())
      return expected.decimalValue().compareTo(stored.decimalValue()) == 0;
    if (expected.isArray() && stored.isArray())
      return Micro6CanonicalHash.sha256WithPrefix(expected)
          .equals(Micro6CanonicalHash.sha256WithPrefix(stored));
    return expected.equals(stored);
  }

  private static String label(String path) {
    return path.isEmpty() ? "$" : path;
  }

  public JsonNode context(String recordingId) {
    return recording(recordingId).getRunContext();
  }

  public RecordingSession recording(String recordingId) {
    return recordings
        .findByRecordingId(recordingId)
        .orElseThrow(() -> new ProvenanceException("RECORDING_NOT_FOUND"));
  }

  /** Inferred legacy provenance is explicitly audited and never relabeled as captured. */
  @Transactional
  public JsonNode assign(String recordingId, JsonNode reference, String actor, String reason) {
    if (actor == null
        || actor.isBlank()
        || reason == null
        || reason.isBlank()
        || reason.length() > 500) fail("AUDIT_REASON_REQUIRED");
    jdbc.queryForObject(
        "SELECT pg_advisory_xact_lock(hashtextextended(?,609))", Object.class, recordingId);
    RecordingSession row = recording(recordingId);
    if (row.getRunContext() != null || row.getTaskKey() != null || row.getTaskArgs() != null)
      fail("IMMUTABLE_PROVENANCE");
    DebugSession session =
        sessions
            .findByRecordingId(recordingId)
            .orElseThrow(() -> new ProvenanceException("DEBUG_SESSION_REQUIRED"));
    if (session.getLifecycleStateOrDefault() != DebugSessionLifecycleState.FINALIZED)
      fail("SESSION_NOT_FINALIZED");
    JsonNode profile =
        profiles.profile(
            required(reference, "behaviorProfileId"),
            reference.path("behaviorProfileVersion").asInt());
    var provenance =
        RecordingProvenance.capture(
            profile.path("task_key").asText(), profile.get("settings_json"));
    ObjectNode context =
        bind(
            snapshot(reference, provenance, row.getAccountName()),
            recordingId,
            session.getSessionId());
    row.captureProvenance(provenance);
    row.bindRunContext(context, "INFERRED");
    jdbc.update(
        "INSERT INTO"
            + " recording_provenance_audit(recording_id,actor,reason,assigned_at,context_hash,context)"
            + " VALUES(?,?,?,?,?,?::jsonb)",
        recordingId,
        actor,
        reason,
        java.sql.Timestamp.from(Instant.now()),
        context.path("contextHash").asText(),
        context.toString());
    recordings.save(row);
    return context;
  }

  public JsonNode requireTrainingContext(String recordingId) {
    RecordingSession row = recording(recordingId);
    JsonNode context = row.getRunContext();
    if (context == null) fail("RUN_CONTEXT_REQUIRED");
    DebugSession session =
        sessions
            .findByRecordingId(recordingId)
            .orElseThrow(() -> new ProvenanceException("DEBUG_SESSION_REQUIRED"));
    if (session.getLifecycleStateOrDefault() != DebugSessionLifecycleState.FINALIZED
        || session.getEndTick() == null) fail("SESSION_NOT_FINALIZED");
    BehaviorSemanticContract.requireDigest(context.path("semanticContractDigest").asText());
    if (!context.path("settingsHash").asText().equals(row.getSettingsHash()))
      fail("CONTEXT_SETTINGS_MISMATCH");
    ObjectNode content = (ObjectNode) context.deepCopy();
    String hash = content.path("contextHash").asText();
    content.remove("contextHash");
    if (!hash.equals(Micro6CanonicalHash.sha256WithPrefix(content))) fail("CONTEXT_HASH_MISMATCH");
    var quality =
        jdbc.queryForList(
            "SELECT quality::text FROM recording_capture_quality WHERE session_id=? AND"
                + " finalized=true",
            String.class,
            session.getSessionId());
    if (quality.isEmpty()) fail("CAPTURE_QUALITY_UNKNOWN");
    try {
      CanonicalTickFrame.requireComplete(json.readTree(quality.get(0)));
    } catch (java.io.IOException e) {
      fail("INVALID_CAPTURE_QUALITY");
    }
    return context;
  }

  public Map<String, Object> eligibility(String recordingId) {
    try {
      requireTrainingContext(recordingId);
      return Map.of("eligible", true, "code", "ELIGIBLE");
    } catch (ProvenanceException e) {
      return Map.of("eligible", false, "code", e.getCode());
    }
  }

  private static String required(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null
        || !value.isTextual()
        || value.asText().isBlank()
        || value.asText().length() > 500) fail("INVALID_RUN_CONTEXT_" + field);
    return value.asText();
  }

  private static void fail(String code) {
    throw new ProvenanceException(code);
  }
}
