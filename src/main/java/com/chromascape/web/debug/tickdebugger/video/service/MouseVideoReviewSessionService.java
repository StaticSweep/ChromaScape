package com.chromascape.web.debug.tickdebugger.video.service;

import com.chromascape.web.debug.tickdebugger.video.MouseVideoException;
import com.chromascape.web.debug.tickdebugger.video.MouseVideoProperties;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoAnalysisRepository;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoSource;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoSourceRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owner/actor-bound review protocol. All exposure/session mutations share a group advisory lock.
 */
@Service
public class MouseVideoReviewSessionService {
  private final Map<String, java.util.concurrent.atomic.AtomicLong> counters =
      new LinkedHashMap<>();

  private void event(String name) {
    counters.get(name).incrementAndGet();
  }

  public Map<String, Long> counters() {
    Map<String, Long> result = new LinkedHashMap<>();
    counters.forEach((key, value) -> result.put(key, value.get()));
    return result;
  }

  private final JdbcTemplate db;
  private final MouseVideoSourceRepository sources;
  private final MouseVideoAnalysisRepository analyses;
  private final MouseVideoFrameIndexService indices;
  private final MouseVideoReviewService reviews;
  private final MouseVideoCoordinatePlane planes;
  private final MouseVideoProperties properties;
  private final ObjectMapper mapper;

  public MouseVideoReviewSessionService(
      JdbcTemplate db,
      MouseVideoSourceRepository sources,
      MouseVideoAnalysisRepository analyses,
      MouseVideoFrameIndexService indices,
      MouseVideoReviewService reviews,
      MouseVideoCoordinatePlane planes,
      MouseVideoProperties properties,
      ObjectMapper mapper) {
    for (String name :
        List.of(
            "created",
            "refused",
            "conflicted",
            "saved",
            "retried",
            "sealed",
            "exposed",
            "abandoned")) counters.put(name, new java.util.concurrent.atomic.AtomicLong());
    this.db = db;
    this.sources = sources;
    this.analyses = analyses;
    this.indices = indices;
    this.reviews = reviews;
    this.planes = planes;
    this.properties = properties;
    this.mapper = mapper;
  }

  private record Policy(
      String digest, String group, String split, boolean enrolled, JsonNode manifest) {}

  /** A protocol read/write response plus whether the operation created new durable state. */
  public record Result(Map<String, Object> body, boolean created) {}

  private MouseVideoException invalid(String message) {
    event("refused");
    return new MouseVideoException("INVALID_REQUEST", message);
  }

  private MouseVideoException conflict(String message) {
    event("conflicted");
    return new MouseVideoException("CONFLICT", message);
  }

  private MouseVideoException refused(String message) {
    event("refused");
    return new MouseVideoException("FORBIDDEN", message);
  }

  private MouseVideoException missing() {
    return new MouseVideoException("NOT_FOUND", "Review context is not available");
  }

  public static String digest(String value) {
    try {
      return "sha256:"
          + HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private String json(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (Exception failure) {
      throw invalid("Invalid review JSON");
    }
  }

  private JsonNode parse(String value) {
    try {
      return mapper.readTree(value);
    } catch (Exception failure) {
      throw invalid("Invalid review JSON");
    }
  }

  private MouseVideoSource source(String owner, UUID sourceId) {
    return sources.findByIdAndOwner(sourceId, owner).orElseThrow(this::missing);
  }

  private Policy policy(MouseVideoSource source) {
    String configured = properties.getCorpusManifest();
    if (configured == null || configured.isBlank())
      return new Policy(
          "unassigned",
          (source.getOriginalChecksum() == null
              ? source.getId().toString()
              : source.getOriginalChecksum()),
          "unassigned",
          false,
          mapper.createArrayNode());
    try {
      Path file = Path.of(configured);
      if (!Files.isRegularFile(file) || Files.size(file) > 1024 * 1024)
        throw refused("Corpus policy unavailable");
      String bytes = Files.readString(file);
      JsonNode manifest = parse(bytes);
      if (!"review-corpus/v1".equals(manifest.path("schema").asText())
          || !manifest.path("sources").isArray()
          || manifest.path("sources").size() > 1024) throw refused("Corpus policy unavailable");
      Set<String> seen = new HashSet<>();
      JsonNode match = null;
      for (JsonNode item : manifest.path("sources")) {
        String original = item.path("originalDigest").asText();
        if (!original.matches("sha256:[a-f0-9]{64}")
            || !seen.add(original)
            || !Set.of("dev", "heldout").contains(item.path("split").asText())
            || item.path("originGroupId").asText().isBlank()
            || item.path("originGroupId").asText().length() > 240)
          throw refused("Corpus policy unavailable");
        if (original.equals(source.getOriginalChecksum())) match = item;
      }
      if (match == null)
        return new Policy(
            digest(bytes),
            (source.getOriginalChecksum() == null
                ? source.getId().toString()
                : source.getOriginalChecksum()),
            "unassigned",
            false,
            manifest.path("sources"));
      String group = match.path("originGroupId").asText(), split = match.path("split").asText();
      for (JsonNode item : manifest.path("sources"))
        if (group.equals(item.path("originGroupId").asText())
            && !split.equals(item.path("split").asText()))
          throw refused("Corpus group split is inconsistent");
      if (db.queryForObject(
              "select count(*) from mouse_video_corpus_memberships where owner=? and (original_digest=? or origin_group_id=?) and split<>?",
              Long.class,
              source.getOwner(),
              source.getOriginalChecksum(),
              group,
              split)
          > 0) throw refused("Corpus split is immutable; enroll fresh source groups");
      db.update(
          "insert into mouse_video_corpus_memberships(owner,source_id,original_digest,manifest_digest,origin_group_id,split) values(?,?,?,?,?,?) on conflict do nothing",
          source.getOwner(),
          source.getId(),
          source.getOriginalChecksum(),
          digest(bytes),
          group,
          split);
      return new Policy(digest(bytes), group, split, true, manifest.path("sources"));
    } catch (MouseVideoException failure) {
      throw failure;
    } catch (Exception failure) {
      throw refused("Corpus policy unavailable");
    }
  }

  private void lock(String owner, String actor, Policy p) {
    db.queryForList(
        "select pg_advisory_xact_lock(hashtextextended(?,0))",
        owner + ":" + actor + ":" + p.group());
  }

  private boolean exposed(String owner, String actor, Policy p) {
    if (db.queryForObject(
            "select count(*) from mouse_video_review_exposures where owner=? and actor=? and origin_group_id=?",
            Long.class,
            owner,
            actor,
            p.group())
        > 0) return true;
    for (JsonNode member : p.manifest()) {
      if (p.group().equals(member.path("originGroupId").asText())
          && db.queryForObject(
                  "select count(*) from mouse_video_review_exposures where owner=? and actor=? and original_digest=?",
                  Long.class,
                  owner,
                  actor,
                  member.path("originalDigest").asText())
              > 0) return true;
    }
    return false;
  }

  private void recordExposure(
      String owner, String actor, MouseVideoSource source, Policy p, String kind) {
    db.update(
        "insert into mouse_video_review_exposures(id,owner,actor,origin_group_id,original_digest,source_id,exposure_kind) values(?,?,?,?,?,?,?) on conflict do nothing",
        UUID.randomUUID(),
        owner,
        actor,
        p.group(),
        source.getOriginalChecksum() == null
            ? "unknown:" + source.getId()
            : source.getOriginalChecksum(),
        source.getId(),
        kind);
  }

  private boolean evaluated(String owner, String actor, Policy policy) {
    if (!policy.enrolled()) return false;
    for (JsonNode member : policy.manifest()) {
      if (!policy.group().equals(member.path("originGroupId").asText())) continue;
      var ranges =
          db.queryForList(
              "select start_ordinal,end_ordinal,frame_count,frame_index_checksum from mouse_video_review_sessions where owner=? and actor=? and original_digest=? and policy_digest=? and mode='WITHOUT_SUGGESTIONS' and state='SEALED' order by start_ordinal",
              owner,
              actor,
              member.path("originalDigest").asText(),
              policy.digest());
      long next = 0, total = -1;
      String checksum = null;
      for (var range : ranges) {
        if (checksum == null) {
          checksum = (String) range.get("frame_index_checksum");
          total = ((Number) range.get("frame_count")).longValue();
        }
        if (!checksum.equals(range.get("frame_index_checksum"))
            || ((Number) range.get("start_ordinal")).longValue() > next) return false;
        next = Math.max(next, ((Number) range.get("end_ordinal")).longValue() + 1);
      }
      if (total < 1 || next < total) return false;
    }
    return true;
  }

  /** Commits exposure before any caller obtains prediction bytes. Denial rolls back no evidence. */
  @Transactional
  public void expose(String owner, String actor, UUID sourceId, String kind) {
    MouseVideoSource source = source(owner, sourceId);
    Policy p = policy(source);
    lock(owner, actor, p);
    if ("heldout".equals(p.split()) && !evaluated(owner, actor, p))
      throw refused(
          "Independent labels for this source group must be sealed before prediction access");
    if (db.queryForObject(
            "select count(*) from mouse_video_review_sessions where owner=? and actor=? and origin_group_id=? and mode='WITHOUT_SUGGESTIONS' and state='OPEN'",
            Long.class,
            owner,
            actor,
            p.group())
        > 0) throw refused("An open blind review prevents prediction access");
    recordExposure(owner, actor, source, p, kind);
    event("exposed");
  }

  /**
   * Redact derived analysis summaries before a held-out annotator has sealed its full source group.
   */
  @Transactional
  public Map<String, Object> protectCatalog(
      String owner, String actor, UUID sourceId, Map<String, Object> body) {
    Policy p = policy(source(owner, sourceId));
    lock(owner, actor, p);
    if (!"heldout".equals(p.split()) || evaluated(owner, actor, p)) return body;
    body.remove("coverage");
    var baseline =
        db.queryForList(
            "select id from mouse_video_analyses where owner=? and source_id=? and algorithm_id='probe/v1' and status='COMPLETE' order by created_at desc limit 1",
            owner,
            sourceId);
    String baselineId = baseline.isEmpty() ? null : baseline.get(0).get("id").toString();
    body.put("selectedAnalysisId", baselineId);
    if (body.get("analyses") instanceof List<?> list)
      body.put(
          "analyses",
          list.stream()
              .filter(
                  item ->
                      item instanceof Map<?, ?> map && "probe/v1".equals(map.get("algorithmId")))
              .toList());
    if (body.get("artifacts") instanceof List<?> list)
      body.put(
          "artifacts",
          list.stream()
              .filter(
                  item ->
                      item instanceof Map<?, ?> map
                          && Set.of("ORIGINAL", "PROXY", "THUMBNAIL", "FRAME_INDEX")
                              .contains(map.get("role"))
                          && (map.get("analysisId") == null
                              || Objects.equals(baselineId, map.get("analysisId"))))
              .toList());
    body.put("reviewPolicyRequired", true);
    return body;
  }

  @Transactional
  public void exposeExport(String owner, String actor, UUID exportId) {
    var rows =
        db.queryForList(
            "select source_id from mouse_video_exports where id=? and owner=?", exportId, owner);
    if (rows.isEmpty()) throw missing();
    expose(owner, actor, (UUID) rows.get(0).get("source_id"), "EXPORT");
  }

  @Transactional
  public Map<String, Object> policy(String owner, String actor, UUID sourceId, UUID analysisId) {
    MouseVideoSource source = source(owner, sourceId);
    Policy p = policy(source);
    lock(owner, actor, p);
    var index = indices.requireIndex(owner, sourceId, analysisId);
    var analysis = analyses.findByIdAndOwner(analysisId, owner).orElseThrow(this::missing);
    boolean legacy =
        db.queryForObject(
                "select count(*) from mouse_video_review_revisions where source_id=? and actor=? and review_session_id is null",
                Long.class,
                sourceId,
                actor)
            > 0;
    boolean blind =
        p.enrolled()
            && !exposed(owner, actor, p)
            && !legacy
            && "probe/v1".equals(analysis.getAlgorithmId());
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("contract", "review-policy/v1");
    out.put("policyDigest", p.digest());
    out.put("enrolled", p.enrolled());
    out.put("split", p.split());
    out.put("forcedBlind", "heldout".equals(p.split()));
    out.put("frameIndexChecksum", index.checksum());
    out.put("frameCount", index.frameCount());
    out.put("maxRangeFrames", 2000);
    out.put(
        "eligibleModes",
        blind
            ? ("heldout".equals(p.split())
                ? List.of("WITHOUT_SUGGESTIONS")
                : List.of("WITHOUT_SUGGESTIONS", "DETECTOR_ASSISTED"))
            : ("heldout".equals(p.split()) ? List.of() : List.of("DETECTOR_ASSISTED")));
    out.put("predictionAccess", !"heldout".equals(p.split()) || evaluated(owner, actor, p));
    planes
        .forAnalysis(analysis)
        .ifPresent(
            plane -> {
              out.put("coordinatePlane", plane.document());
              out.put("coordinatePlaneDigest", plane.digest());
            });
    out.put(
        "sessions",
        db.queryForList(
            "select id,mode,state,start_ordinal as \"startOrdinal\",end_ordinal as \"endOrdinal\" from mouse_video_review_sessions where owner=? and actor=? and source_id=? and analysis_id=? and policy_digest=? order by start_ordinal,created_at limit 256",
            owner,
            actor,
            sourceId,
            analysisId,
            p.digest()));
    return out;
  }

  private void keys(JsonNode node, Set<String> allowed) {
    if (!node.isObject()) throw invalid("Expected a review object");
    node.fieldNames()
        .forEachRemaining(
            key -> {
              if (!allowed.contains(key)) throw invalid("Unknown review field: " + key);
            });
  }

  private long integer(JsonNode node, String key) {
    JsonNode value = node.get(key);
    if (value == null
        || !value.isIntegralNumber()
        || !value.canConvertToLong()
        || value.longValue() < 0) throw invalid("Invalid " + key);
    return value.longValue();
  }

  private UUID uuid(JsonNode node, String key) {
    try {
      return UUID.fromString(node.path(key).asText());
    } catch (Exception failure) {
      throw invalid("Invalid " + key);
    }
  }

  /** Compatibility wrapper; controllers use {@link #createResult} to distinguish 201 from 200. */
  @Transactional
  public Map<String, Object> create(String owner, String actor, UUID sourceId, JsonNode body) {
    return createResult(owner, actor, sourceId, body).body();
  }

  @Transactional
  public Result createResult(String owner, String actor, UUID sourceId, JsonNode body) {
    keys(
        body,
        Set.of(
            "requestId",
            "analysisId",
            "frameIndexChecksum",
            "mode",
            "policyDigest",
            "startOrdinal",
            "endOrdinal",
            "annotationAttestation"));
    UUID requestId = uuid(body, "requestId"), analysisId = uuid(body, "analysisId");
    // Serialize against legacy review writes before acquiring the actor/group protocol lock.
    sources.findForUpdateByIdAndOwner(sourceId, owner).orElseThrow(this::missing);
    MouseVideoSource source = source(owner, sourceId);
    Policy p = policy(source);
    lock(owner, actor, p);
    String requestDigest = digest(json(body));
    var existing =
        db.queryForList(
            "select * from mouse_video_review_sessions where owner=? and actor=? and request_id=?",
            owner,
            actor,
            requestId.toString());
    if (!existing.isEmpty()) {
      if (!requestDigest.equals(existing.get(0).get("request_digest"))
          || !sourceId.equals(existing.get(0).get("source_id")))
        throw conflict("Session request identifier has different content");
      // An identical retry must expose exactly the same pinned context as the original
      // creation, including the persisted coordinate-plane document. `select *` does not
      // project the ::text JSON the view reads, so reuse the shared row/get projection.
      return new Result(get(owner, actor, (UUID) existing.get(0).get("id")), false);
    }
    var index = indices.requireIndex(owner, sourceId, analysisId);
    var analysis = analyses.findByIdAndOwner(analysisId, owner).orElseThrow(this::missing);
    var plane = planes.forAnalysis(analysis).orElse(null);
    if (!index.complete()
        || !index.checksum().equals(body.path("frameIndexChecksum").asText())
        || !p.digest().equals(body.path("policyDigest").asText()))
      throw conflict("Review policy or index identity changed");
    String mode = body.path("mode").asText();
    if (!Set.of("WITHOUT_SUGGESTIONS", "DETECTOR_ASSISTED").contains(mode))
      throw invalid("Unsupported review mode");
    var eligible = (List<?>) policy(owner, actor, sourceId, analysisId).get("eligibleModes");
    if (!eligible.contains(mode))
      throw refused("Review mode is not eligible for this actor and source");
    if (!body.path("annotationAttestation").isBoolean()
        || !body.path("annotationAttestation").booleanValue())
      throw invalid("Annotation protocol attestation is required");
    long start = integer(body, "startOrdinal"), end = integer(body, "endOrdinal");
    if (end < start || end >= index.frameCount() || end - start >= 2000)
      throw invalid("Review range must contain 1–2000 indexed frames");
    if (db.queryForObject(
            "select count(*) from mouse_video_review_sessions where owner=? and actor=? and source_id=? and analysis_id=? and policy_digest=? and state in ('OPEN','SEALED') and start_ordinal<=? and end_ordinal>=?",
            Long.class,
            owner,
            actor,
            sourceId,
            analysisId,
            p.digest(),
            end,
            start)
        > 0) throw conflict("An existing session owns this review range; resume it");
    if (p.enrolled()) {
      db.update(
          "insert into mouse_video_corpus_memberships(owner,source_id,original_digest,manifest_digest,origin_group_id,split) values(?,?,?,?,?,?) on conflict do nothing",
          owner,
          sourceId,
          source.getOriginalChecksum(),
          p.digest(),
          p.group(),
          p.split());
    }
    if (db.queryForObject(
            "select count(*) from mouse_video_review_sessions where owner=? and actor=? and source_id=?",
            Long.class,
            owner,
            actor,
            sourceId)
        >= 256) throw invalid("Source review-session limit reached");
    UUID id = UUID.randomUUID();
    db.update(
        "insert into mouse_video_review_sessions(id,source_id,analysis_id,owner,actor,request_id,request_digest,original_digest,frame_index_checksum,origin_group_id,policy_digest,split,mode,start_ordinal,end_ordinal,frame_count,annotation_attestation,coordinate_plane_identity,coordinate_plane_digest) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,true,?::jsonb,?)",
        id,
        sourceId,
        analysisId,
        owner,
        actor,
        requestId.toString(),
        requestDigest,
        source.getOriginalChecksum(),
        index.checksum(),
        p.group(),
        p.digest(),
        p.split(),
        mode,
        start,
        end,
        index.frameCount(),
        plane == null ? null : json(plane.document()),
        plane == null ? null : plane.digest());
    if (mode.equals("DETECTOR_ASSISTED"))
      recordExposure(owner, actor, source, p, "ASSISTED_SESSION");
    event("created");
    return new Result(get(owner, actor, id), true);
  }

  private Map<String, Object> row(String owner, String actor, UUID id) {
    var rows =
        db.queryForList(
            "select s.*, s.coordinate_plane_identity::text as coordinate_plane_json from mouse_video_review_sessions s where s.id=? and s.owner=? and s.actor=?",
            id,
            owner,
            actor);
    if (rows.isEmpty()) throw missing();
    return rows.get(0);
  }

  private Map<String, Object> view(Map<String, Object> row) {
    Map<String, Object> out = new LinkedHashMap<>();
    for (String key :
        List.of(
            "id",
            "source_id",
            "analysis_id",
            "original_digest",
            "frame_index_checksum",
            "origin_group_id",
            "policy_digest",
            "split",
            "mode",
            "state",
            "start_ordinal",
            "end_ordinal",
            "frame_count",
            "protocol_version",
            "latest_revision_id")) {
      String[] parts = key.split("_");
      String name = parts[0];
      for (int i = 1; i < parts.length; i++)
        name += parts[i].substring(0, 1).toUpperCase(Locale.ROOT) + parts[i].substring(1);
      Object value = row.get(key);
      out.put(name, value instanceof UUID ? value.toString() : value);
    }
    Object plane = row.get("coordinate_plane_json");
    out.put("coordinatePlane", plane == null ? null : parse((String) plane));
    out.put("coordinatePlaneDigest", row.get("coordinate_plane_digest"));
    return out;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> get(String owner, String actor, UUID id) {
    return view(row(owner, actor, id));
  }

  @Transactional(readOnly = true)
  public Map<String, Object> history(String owner, String actor, UUID id) {
    return history(owner, actor, id, null, 20);
  }

  @Transactional(readOnly = true)
  public Map<String, Object> history(String owner, String actor, UUID id, UUID before, int limit) {
    var session = row(owner, actor, id);
    int cap = Math.max(1, Math.min(20, limit));
    String sql = "select id from mouse_video_review_revisions where review_session_id=?";
    List<Object> args = new ArrayList<>();
    args.add(id);
    if (before != null) {
      if (db.queryForObject(
              "select count(*) from mouse_video_review_revisions where review_session_id=? and id=?",
              Long.class,
              id,
              before)
          != 1) throw missing();
      sql +=
          " and (created_at,id)<(select created_at,id from mouse_video_review_revisions where id=?)";
      args.add(before);
    }
    sql += " order by created_at desc,id desc limit ?";
    args.add(cap + 1);
    var ids = db.queryForList(sql, args.toArray());
    List<Object> items = new ArrayList<>();
    int bytes = 0;
    UUID last = null;
    boolean more = false;
    for (var item : ids) {
      if (items.size() == cap) {
        more = true;
        break;
      }
      var review =
          decorate(
              reviews.getSnapshot(owner, (UUID) session.get("source_id"), (UUID) item.get("id")));
      int encoded = json(review).getBytes(StandardCharsets.UTF_8).length;
      if (bytes + encoded > 1024 * 1024 - 1024) {
        if (items.isEmpty()) throw invalid("Revision exceeds history page bound");
        more = true;
        break;
      }
      bytes += encoded;
      items.add(review);
      last = (UUID) item.get("id");
    }
    Collections.reverse(items);
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("items", items);
    out.put("contract", "review-provenance/v1");
    out.put("nextCursor", more ? last.toString() : null);
    return out;
  }

  private Map<String, Object> decorate(Map<String, Object> review) {
    var data =
        db.queryForMap(
            "select review_session_id,provenance_json::text,client_edit_version,snapshot_digest,applied_source_revision,created_at,corrections_json::text from mouse_video_review_revisions where id=?",
            UUID.fromString((String) review.get("id")));
    Map<String, Object> out = new LinkedHashMap<>(review);
    out.put("corrections", parse((String) data.get("corrections_json")));
    out.put("createdAt", ((java.sql.Timestamp) data.get("created_at")).toInstant().toString());
    out.put("sessionId", data.get("review_session_id").toString());
    out.put("revisionId", review.get("id"));
    out.put("provenance", parse((String) data.get("provenance_json")));
    out.put("acknowledgedClientEditVersion", data.get("client_edit_version"));
    out.put("snapshotDigest", data.get("snapshot_digest"));
    out.put("appliedSourceRevision", data.get("applied_source_revision"));
    return out;
  }

  private Map<String, Object> immutableAcknowledgement(Map<String, Object> revision) {
    revision.remove("supersededBy");
    revision.put("state", "REVIEWED");
    return revision;
  }

  /** Compatibility wrapper; controllers use {@link #submitResult} for 201 versus 200. */
  @Transactional
  public Map<String, Object> submit(String owner, String actor, UUID id, JsonNode request) {
    return submitResult(owner, actor, id, request).body();
  }

  @Transactional
  public Result submitResult(String owner, String actor, UUID id, JsonNode request) {
    keys(request, Set.of("snapshotJson"));
    String wire = request.path("snapshotJson").asText();
    if (wire.getBytes(StandardCharsets.UTF_8).length > 600 * 1024)
      throw invalid("Review snapshot exceeds request bound");
    JsonNode body = parse(wire);
    keys(
        body,
        Set.of(
            "requestId",
            "analysisId",
            "expectedBaseRevision",
            "clientEditVersion",
            "reason",
            "sourceUse",
            "corrections",
            "origins"));
    UUID requestId = uuid(body, "requestId");
    var session = row(owner, actor, id);
    UUID sourceId = (UUID) session.get("source_id");
    sources.findForUpdateByIdAndOwner(sourceId, owner).orElseThrow(this::missing);
    MouseVideoSource source = source(owner, sourceId);
    Policy p = policy(source);
    lock(owner, actor, p);
    session = row(owner, actor, id);
    String snapshotDigest = digest(wire);
    var retry =
        db.queryForList(
            "select id,review_session_id,snapshot_digest from mouse_video_review_revisions where source_id=? and request_id=?",
            sourceId,
            requestId.toString());
    if (!retry.isEmpty()) {
      var existing = retry.get(0);
      if (!id.equals(existing.get("review_session_id"))
          || !snapshotDigest.equals(existing.get("snapshot_digest")))
        throw conflict("Review request identifier has different snapshot content");
      event("retried");
      return new Result(
          immutableAcknowledgement(
              decorate(reviews.getSnapshot(owner, sourceId, (UUID) existing.get("id")))),
          false);
    }
    validateCurrent(session, source, p);
    if (!"OPEN".equals(session.get("state"))) throw conflict("Review session is no longer open");
    if (!session.get("analysis_id").equals(uuid(body, "analysisId")))
      throw conflict("Snapshot analysis differs from session");
    long version = integer(body, "clientEditVersion"), base = integer(body, "expectedBaseRevision");
    var last =
        db.queryForList(
            "select client_edit_version from mouse_video_review_revisions where id=?",
            session.get("latest_revision_id"));
    if (!last.isEmpty() && version <= ((Number) last.get(0).get("client_edit_version")).longValue())
      throw conflict("Client edit version must advance");
    JsonNode corrections = body.path("corrections"), origins = body.path("origins");
    if (!corrections.isArray() || !origins.isObject())
      throw invalid("Corrections and exact label origins are required");
    Set<String> keys = new HashSet<>();
    boolean blind = "WITHOUT_SUGGESTIONS".equals(session.get("mode"));
    if (blind && exposed(owner, actor, p))
      throw refused("Prior prediction exposure prevents independent review");
    for (JsonNode correction : corrections) {
      if (!"CURSOR_VISIBILITY".equals(correction.path("kind").asText())
          || correction.has("provenance"))
        throw invalid("Session cursor corrections use the unmodified corrections/v1 shape");
      long ordinal = integer(correction, "frameOrdinal");
      if (ordinal < ((Number) session.get("start_ordinal")).longValue()
          || ordinal > ((Number) session.get("end_ordinal")).longValue())
        throw invalid("Correction is outside the session range");
      String key = Long.toString(ordinal);
      if (!keys.add(key)) throw invalid("Duplicate cursor ordinal");
      String origin = origins.path(key).asText();
      String expected =
          switch (correction.path("visibility").asText()) {
            case "VISIBLE" -> "MANUAL_VISIBLE";
            case "HIDDEN" -> "MANUAL_HIDDEN";
            case "AMBIGUOUS", "UNSUPPORTED" -> "MANUAL_AMBIGUOUS";
            default -> "INVALID";
          };
      if (!expected.equals(origin)
          && !(origin.equals("ACCEPTED_PREDICTION") && !blind && expected.equals("MANUAL_VISIBLE")))
        throw invalid("Label origin does not match its visible evidence or session mode");
    }
    if (origins.size() != keys.size())
      throw invalid("Label origin keys must match corrections exactly");
    Map<String, Object> created =
        reviews.createSnapshot(
            owner,
            actor,
            sourceId,
            requestId.toString(),
            (UUID) session.get("analysis_id"),
            base,
            body.path("reason").asText(),
            body.path("sourceUse").asText(),
            corrections);
    ObjectNode provenance = mapper.createObjectNode();
    provenance.put("contract", "review-provenance/v1");
    provenance.set("session", mapper.valueToTree(view(session)));
    provenance.put("actor", actor);
    provenance.put("annotationAttestation", true);
    provenance.put("suggestionsExposed", !blind);
    provenance.put("independentProtocol", blind);
    provenance.set("origins", origins);
    provenance.put("snapshotDigest", snapshotDigest);
    db.update(
        "update mouse_video_review_revisions set review_session_id=?,provenance_json=?::jsonb,client_edit_version=?,snapshot_digest=?,applied_source_revision=? where id=?",
        id,
        json(provenance),
        version,
        snapshotDigest,
        base + 1,
        UUID.fromString((String) created.get("id")));
    db.update(
        "update mouse_video_review_sessions set latest_revision_id=? where id=?",
        UUID.fromString((String) created.get("id")),
        id);
    event("saved");
    return new Result(immutableAcknowledgement(decorate(created)), true);
  }

  private void validateCurrent(Map<String, Object> session, MouseVideoSource source, Policy p) {
    var index =
        indices.requireIndex(
            (String) session.get("owner"), source.getId(), (UUID) session.get("analysis_id"));
    if (!p.digest().equals(session.get("policy_digest"))
        || !source.getOriginalChecksum().equals(session.get("original_digest"))
        || !index.checksum().equals(session.get("frame_index_checksum")))
      throw conflict("Pinned source, policy or index changed");
    Object pinned = session.get("coordinate_plane_digest");
    if (pinned != null) {
      var analysis =
          analyses
              .findByIdAndOwner((UUID) session.get("analysis_id"), (String) session.get("owner"))
              .orElseThrow(this::missing);
      String current =
          planes.forAnalysis(analysis).map(MouseVideoCoordinatePlane.Identity::digest).orElse(null);
      if (!pinned.equals(current)) throw conflict("Pinned coordinate plane changed");
    }
  }

  @Transactional
  public Map<String, Object> seal(String owner, String actor, UUID id, JsonNode body) {
    keys(body, Set.of("requestId", "revisionId", "snapshotDigest"));
    uuid(body, "requestId");
    UUID revisionId = uuid(body, "revisionId");
    var session = row(owner, actor, id);
    UUID sourceId = (UUID) session.get("source_id");
    sources.findForUpdateByIdAndOwner(sourceId, owner).orElseThrow(this::missing);
    MouseVideoSource source = source(owner, sourceId);
    Policy p = policy(source);
    lock(owner, actor, p);
    session = row(owner, actor, id);
    String requestDigest = digest(json(body));
    if ("SEALED".equals(session.get("state"))) {
      if (!requestDigest.equals(session.get("seal_request_digest")))
        throw conflict("Sealed session has a different seal identity");
      return view(session);
    }
    validateCurrent(session, source, p);
    if (!"OPEN".equals(session.get("state"))
        || !revisionId.equals(session.get("latest_revision_id")))
      throw conflict("Seal must identify the latest acknowledged session revision");
    var revision = decorate(reviews.getSnapshot(owner, sourceId, revisionId));
    if (!Objects.equals(body.path("snapshotDigest").asText(), revision.get("snapshotDigest")))
      throw conflict("Seal snapshot digest differs from the acknowledgement");
    JsonNode corrections = mapper.valueToTree(revision.get("corrections"));
    Set<Long> labelled = new HashSet<>();
    for (JsonNode correction : corrections)
      labelled.add(correction.path("frameOrdinal").longValue());
    var index = indices.requireIndex(owner, sourceId, (UUID) session.get("analysis_id"));
    // Dense truth protocol: nearest source frame to each 100 ms target, with explicit ambiguity
    // allowed.
    long start = ((Number) session.get("start_ordinal")).longValue(),
        end = ((Number) session.get("end_ordinal")).longValue();
    double next = Double.parseDouble(index.frames().get((int) start).ptsTime());
    for (long ordinal = start; ordinal <= end; ordinal++) {
      double time = Double.parseDouble(index.frames().get((int) ordinal).ptsTime());
      if (!Double.isFinite(time)) throw invalid("Index has invalid annotation timing");
      if (time + 1e-9 >= next || ordinal == end) {
        if (!labelled.contains(ordinal)) throw conflict("Dense annotation coverage is incomplete");
        next = time + 0.1;
      }
    }
    boolean blind = "WITHOUT_SUGGESTIONS".equals(session.get("mode"));
    if (blind && exposed(owner, actor, p)) throw refused("Exposure prevents independent sealing");
    ObjectNode export = mapper.createObjectNode();
    export.put("contract", "mouse-video-review-export/v2");
    export.put("protocol", "review-provenance/v1");
    export.set("session", mapper.valueToTree(view(session)));
    ((ObjectNode) export.get("session")).put("state", "SEALED");
    export.set("revision", mapper.valueToTree(revision));
    Object planeJson = session.get("coordinate_plane_json");
    if (planeJson != null) {
      export.set("coordinatePlane", parse((String) planeJson));
      export.put("coordinatePlaneDigest", (String) session.get("coordinate_plane_digest"));
    }
    export.put("independentProtocol", blind);
    export.put("attestation", true);
    export.put("sealedAt", java.time.Instant.now().toString());
    export.put("revisionJson", json(revision));
    export.put("contentDigest", digest(json(revision)));
    db.update(
        "update mouse_video_review_sessions set state='SEALED',seal_request_id=?,seal_request_digest=?,sealed_export=?,sealed_at=now() where id=?",
        body.path("requestId").asText(),
        requestDigest,
        json(export),
        id);
    event("sealed");
    return get(owner, actor, id);
  }

  @Transactional
  public Map<String, Object> abandon(String owner, String actor, UUID id, JsonNode body) {
    keys(body, Set.of("requestId", "latestRevisionId"));
    uuid(body, "requestId");
    var session = row(owner, actor, id);
    UUID sourceId = (UUID) session.get("source_id");
    sources.findForUpdateByIdAndOwner(sourceId, owner).orElseThrow(this::missing);
    Policy p = policy(source(owner, sourceId));
    lock(owner, actor, p);
    session = row(owner, actor, id);
    if ("SEALED".equals(session.get("state")))
      throw conflict("Sealed evidence cannot be abandoned");
    UUID expected =
        body.path("latestRevisionId").isNull() || body.path("latestRevisionId").isMissingNode()
            ? null
            : uuid(body, "latestRevisionId");
    if (!Objects.equals(expected, session.get("latest_revision_id")))
      throw conflict("Abandon must acknowledge the latest saved labels");
    db.update("update mouse_video_review_sessions set state='ABANDONED' where id=?", id);
    event("abandoned");
    return get(owner, actor, id);
  }

  @Transactional(readOnly = true)
  public String export(String owner, String actor, UUID id) {
    var session = row(owner, actor, id);
    if (!"SEALED".equals(session.get("state")))
      throw conflict("Only sealed sessions have an immutable qualification export");
    return (String) session.get("sealed_export");
  }
}
