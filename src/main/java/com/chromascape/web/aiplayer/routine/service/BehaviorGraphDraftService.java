package com.chromascape.web.aiplayer.routine.service;

import com.chromascape.web.aiplayer.routine.dto.BehaviorGraphDraftDtos.*;
import com.chromascape.web.aiplayer.routine.entity.*;
import com.chromascape.web.aiplayer.routine.model.BehaviorGraphValidationIssue;
import com.chromascape.web.aiplayer.routine.model.BehaviorGraphValidationResult;
import com.chromascape.web.aiplayer.routine.repository.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BehaviorGraphDraftService {
  public static class DraftException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final Map<String, Object> details;

    DraftException(HttpStatus s, String c, String m) {
      this(s, c, m, Map.of());
    }

    DraftException(HttpStatus s, String c, String m, Map<String, Object> d) {
      super(m);
      status = s;
      code = c;
      details = d;
    }

    public HttpStatus status() {
      return status;
    }

    public String code() {
      return code;
    }

    public Map<String, Object> details() {
      return details;
    }
  }

  private final ObjectMapper mapper;
  private final BehaviorGraphDraftRepository drafts;
  private final BehaviorGraphPublicationRepository publications;
  private final GraphSchemaValidator schemas;
  private final BehaviorGraphV2Validator semantics;
  private final BehaviorGraphV2Canonicalizer graphs;
  private final BehaviorGraphLayoutCanonicalizer layouts;

  public BehaviorGraphDraftService(
      ObjectMapper m,
      BehaviorGraphDraftRepository d,
      BehaviorGraphPublicationRepository p,
      GraphSchemaValidator s,
      BehaviorGraphV2Validator v,
      BehaviorGraphV2Canonicalizer g,
      BehaviorGraphLayoutCanonicalizer l) {
    mapper = m;
    drafts = d;
    publications = p;
    schemas = s;
    semantics = v;
    graphs = g;
    layouts = l;
  }

  @Transactional
  public Map<String, Object> createFishingDraft(String draftId) {
    return createMinimalDraft(
        draftId, "fishing_training_routine", "FISHING_TRAINING", "fishing", "1");
  }

  @Transactional
  public Map<String, Object> createDraft(DraftCreateRequest request) {
    if (request == null)
      throw error(HttpStatus.BAD_REQUEST, "MALFORMED_CONTRACT", "request required");
    JsonNode envelope = mapper.valueToTree(request);
    try {
      schemas.validateDraftCreateRequest(envelope);
    } catch (IllegalArgumentException e) {
      throw error(HttpStatus.BAD_REQUEST, "MALFORMED_CONTRACT", e.getMessage());
    }
    return createMinimalDraft(
        request.draftId(),
        request.graphId(),
        request.taskKey(),
        request.profileId(),
        request.profileVersion());
  }

  private Map<String, Object> createMinimalDraft(
      String draftId, String graphId, String taskKey, String profileId, String profileVersion) {
    if (drafts.findByDraftId(draftId).isPresent()) return getDraft(draftId);
    ObjectNode def = mapper.createObjectNode();
    def.put("schema", "behavior-graph-definition/v2");
    def.put("schema_version", "behavior-graph-definition/v2");
    def.put("graph_id", graphId);
    def.set("task", mapper.valueToTree(Map.of("task_key", taskKey)));
    def.set(
        "profile",
        mapper.valueToTree(Map.of("profile_id", profileId, "profile_version", profileVersion)));
    def.set(
        "nodes", mapper.valueToTree(List.of(node("start", "START"), node("success", "SUCCESS"))));
    def.set(
        "edges",
        mapper.valueToTree(
            List.of(
                Map.of(
                    "id",
                    "start_success",
                    "from",
                    "start",
                    "to",
                    "success",
                    "priority",
                    0,
                    "source_order",
                    0))));
    def.set("authority", mapper.valueToTree(authority()));
    def.put("control_allowed", false);
    def.put("graph_hash", graphs.graphHash(def));
    ObjectNode layout = mapper.createObjectNode();
    layout.put("schema", "behavior-graph-layout/v1");
    layout.put("schema_version", "behavior-graph-layout/v1");
    layout.put("graph_id", graphId);
    layout.set(
        "nodes",
        mapper.valueToTree(
            List.of(
                Map.of("node_id", "start", "x", 0, "y", 0),
                Map.of("node_id", "success", "x", 280, "y", 0))));
    layout.set("viewport", mapper.valueToTree(Map.of("x", 0, "y", 0, "zoom", 1)));
    // `groups` is written even when empty: the editor always materializes it, and the layout
    // canonicalizer hashes a missing array as an empty one, so omitting it here would make a
    // freshly created draft fail the editor's layout_hash check with HASH_MISMATCH.
    layout.set("groups", mapper.valueToTree(List.of()));
    layout.put("layout_hash", layouts.layoutHash(layout));
    return saveNew(
        draftId,
        def,
        layout,
        BehaviorGraphV2Validator.ValidationContext.of(taskKey, profileId, profileVersion, null));
  }

  private Map<String, Object> node(String id, String type) {
    return Map.of("id", id, "type", type, "label", type, "configuration", Map.of());
  }

  private Map<String, Object> authority() {
    return Map.of(
        "can_write_source",
        false,
        "can_approve_patch",
        false,
        "can_publish_graph",
        false,
        "can_activate_model",
        false,
        "can_dispatch_input",
        false);
  }

  private Map<String, Object> saveNew(
      String id,
      JsonNode def,
      JsonNode layout,
      BehaviorGraphV2Validator.ValidationContext context) {
    validate(def, layout, context);
    BehaviorGraphDraft d = new BehaviorGraphDraft();
    d.setDraftId(id);
    d.setGraphId(def.path("graph_id").asText());
    d.setTaskKey(def.path("task").path("task_key").asText());
    d.setProfileId(def.path("profile").path("profile_id").asText());
    d.setProfileVersion(def.path("profile").path("profile_version").asText());
    d.setRevision(1);
    d.setDefinitionSchema("behavior-graph-definition/v2");
    d.setLayoutSchema("behavior-graph-layout/v1");
    d.setDefinitionJson(write(def));
    d.setLayoutJson(write(layout));
    d.setGraphHash(def.path("graph_hash").asText());
    d.setLayoutHash(layout.path("layout_hash").asText());
    drafts.save(d);
    return response(d);
  }

  @Transactional(readOnly = true)
  public Map<String, Object> getDraft(String id) {
    return response(
        drafts
            .findByDraftId(id)
            .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "DRAFT_NOT_FOUND", "draft not found")));
  }

  @Transactional
  public Map<String, Object> updateDraft(String id, DraftUpdateRequest request) {
    if (request == null)
      throw error(HttpStatus.BAD_REQUEST, "MALFORMED_CONTRACT", "request required");
    ObjectNode envelope = mapper.createObjectNode();
    envelope.put("schema", request.schema());
    envelope.put("base_revision", request.baseRevision());
    envelope.set("definition", request.definition());
    envelope.set("layout", request.layout());
    try {
      schemas.validateDraftRequest(envelope);
    } catch (IllegalArgumentException e) {
      throw error(HttpStatus.BAD_REQUEST, "MALFORMED_CONTRACT", e.getMessage());
    }
    BehaviorGraphDraft d =
        drafts
            .findWithLockByDraftId(id)
            .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "DRAFT_NOT_FOUND", "draft not found"));
    if (d.getRevision() != request.baseRevision())
      throw new DraftException(
          HttpStatus.CONFLICT,
          "DRAFT_REVISION_CONFLICT",
          "draft revision differs",
          Map.of("expected_revision", request.baseRevision(), "actual_revision", d.getRevision()));
    if (!d.getGraphId().equals(request.definition().path("graph_id").asText())
        || !d.getGraphId().equals(request.layout().path("graph_id").asText()))
      throw error(
          HttpStatus.UNPROCESSABLE_ENTITY, "CAPABILITY_MISMATCH", "graph_id must match draft");
    validate(request.definition(), request.layout(), contextFor(d));
    String gh = graphs.graphHash(request.definition()), lh = layouts.layoutHash(request.layout());
    ((ObjectNode) request.definition()).put("graph_hash", gh);
    ((ObjectNode) request.layout()).put("layout_hash", lh);
    d.setDefinitionJson(write(request.definition()));
    d.setLayoutJson(write(request.layout()));
    d.setGraphHash(gh);
    d.setLayoutHash(lh);
    d.setRevision(d.getRevision() + 1);
    d.touch();
    return response(d);
  }

  @Transactional
  public Map<String, Object> publishDraft(String id, PublishRequest r) {
    if (r == null) throw error(HttpStatus.BAD_REQUEST, "MALFORMED_CONTRACT", "request required");
    ObjectNode request = mapper.createObjectNode();
    request.put("schema", r.schema());
    request.put("base_revision", r.baseRevision());
    request.put("graph_hash", r.graphHash());
    request.put("layout_hash", r.layoutHash());
    try {
      schemas.validatePublishRequest(request);
    } catch (IllegalArgumentException e) {
      throw error(HttpStatus.BAD_REQUEST, "MALFORMED_CONTRACT", e.getMessage());
    }
    BehaviorGraphDraft d =
        drafts
            .findWithLockByDraftId(id)
            .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "DRAFT_NOT_FOUND", "draft not found"));
    if (d.getRevision() != r.baseRevision())
      throw new DraftException(
          HttpStatus.CONFLICT,
          "DRAFT_REVISION_CONFLICT",
          "draft revision differs",
          Map.of("expected_revision", r.baseRevision(), "actual_revision", d.getRevision()));
    if (!d.getGraphHash().equals(r.graphHash()) || !d.getLayoutHash().equals(r.layoutHash()))
      throw error(HttpStatus.CONFLICT, "DRAFT_HASH_CONFLICT", "draft hash differs");
    Optional<BehaviorGraphPublication> existing =
        publications.findBySourceDraftIdAndSourceDraftRevision(id, d.getRevision());
    if (existing.isPresent()) return publicationResponse(existing.get());
    JsonNode def = read(d.getDefinitionJson()), lay = read(d.getLayoutJson());
    BehaviorGraphValidationResult valid = validate(def, lay, contextFor(d));
    if (!valid.errors().isEmpty())
      throw error(
          HttpStatus.UNPROCESSABLE_ENTITY, "GRAPH_SEMANTIC_INVALID", "draft has validation errors");
    long version =
        publications
            .findTopByGraphIdOrderByGraphVersionDesc(d.getGraphId())
            .map(x -> x.getGraphVersion() + 1)
            .orElse(1L);
    BehaviorGraphPublication p = new BehaviorGraphPublication();
    p.setGraphId(d.getGraphId());
    p.setGraphVersion(version);
    p.setSourceDraftId(id);
    p.setSourceDraftRevision(d.getRevision());
    p.setDefinitionSchema("behavior-graph-definition/v2");
    p.setDefinitionJson(d.getDefinitionJson());
    p.setGraphHash(d.getGraphHash());
    p.setLayoutSchema("behavior-graph-layout/v1");
    p.setLayoutJson(d.getLayoutJson());
    p.setLayoutHash(d.getLayoutHash());
    publications.saveAndFlush(p);
    // A draft remains an editable DRAFT workspace; publications are immutable snapshots.
    return publicationResponse(p);
  }

  /* Drafts deliberately retain semantic errors so an operator can repair an incomplete graph.
   * Structural contracts and layout integrity remain fail-closed; publish is the semantic gate. */
  private BehaviorGraphValidationResult validate(
      JsonNode def, JsonNode layout, BehaviorGraphV2Validator.ValidationContext context) {
    try {
      schemas.validateDefinitionV2(def);
    } catch (IllegalArgumentException e) {
      throw error(HttpStatus.UNPROCESSABLE_ENTITY, "GRAPH_SCHEMA_INVALID", e.getMessage());
    }
    try {
      schemas.validateLayoutV1(layout);
    } catch (IllegalArgumentException e) {
      throw error(HttpStatus.UNPROCESSABLE_ENTITY, "LAYOUT_SCHEMA_INVALID", e.getMessage());
    }
    if (!def.path("graph_id").asText().equals(layout.path("graph_id").asText()))
      throw error(
          HttpStatus.UNPROCESSABLE_ENTITY, "LAYOUT_SCHEMA_INVALID", "layout graph_id differs");
    validateLayoutReferences(def, layout);
    return semantics.validate(def, context);
  }

  /**
   * A draft's task and profile identity are fixed at creation. Supplying them as validation
   * expectations is what turns an update that silently retargets a draft at another task into a
   * TASK_NOT_FOUND issue instead of a silently accepted change.
   */
  private static BehaviorGraphV2Validator.ValidationContext contextFor(BehaviorGraphDraft draft) {
    return BehaviorGraphV2Validator.ValidationContext.of(
        draft.getTaskKey(), draft.getProfileId(), draft.getProfileVersion(), null);
  }

  private void validateLayoutReferences(JsonNode def, JsonNode layout) {
    Set<String> ids = new HashSet<>(), placements = new HashSet<>(), groups = new HashSet<>();
    def.path("nodes").forEach(n -> ids.add(n.path("id").asText()));
    for (JsonNode p : layout.path("nodes")) {
      String id = p.path("node_id").asText();
      if (!ids.contains(id) || !placements.add(id))
        throw error(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "LAYOUT_SCHEMA_INVALID",
            "layout placements must uniquely reference graph nodes");
    }
    for (JsonNode group : layout.path("groups")) {
      if (!groups.add(group.path("id").asText()))
        throw error(HttpStatus.UNPROCESSABLE_ENTITY, "LAYOUT_SCHEMA_INVALID", "duplicate group id");
      for (JsonNode id : group.path("node_ids"))
        if (!ids.contains(id.asText()))
          throw error(
              HttpStatus.UNPROCESSABLE_ENTITY,
              "LAYOUT_SCHEMA_INVALID",
              "group references an unknown node");
    }
  }

  private Map<String, Object> response(BehaviorGraphDraft d) {
    JsonNode def = read(d.getDefinitionJson()), lay = read(d.getLayoutJson());
    BehaviorGraphValidationResult v = validatePersisted(def, lay, d);
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("schema", "behavior-graph-draft-response/v1");
    out.put("draft_id", d.getDraftId());
    out.put("revision", d.getRevision());
    out.put("state", d.getState());
    out.put("definition", def);
    out.put("layout", lay);
    out.put("graph_hash", d.getGraphHash());
    out.put("layout_hash", d.getLayoutHash());
    out.put(
        "validation",
        Map.of("state", v.state(), "errors", wire(v.errors()), "warnings", wire(v.warnings())));
    out.put("authority", authority());
    out.put("control_allowed", false);
    try {
      schemas.validateDraftResponse(mapper.valueToTree(out));
    } catch (IllegalArgumentException e) {
      throw error(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "MALFORMED_CONTRACT",
          "persisted draft response invalid");
    }
    return out;
  }

  private BehaviorGraphValidationResult validatePersisted(
      JsonNode definition, JsonNode layout, BehaviorGraphDraft draft) {
    BehaviorGraphValidationResult validation = validate(definition, layout, contextFor(draft));
    if (!draft.getGraphHash().equals(graphs.graphHash(definition))
        || !draft.getLayoutHash().equals(layouts.layoutHash(layout)))
      throw error(
          HttpStatus.UNPROCESSABLE_ENTITY, "MALFORMED_CONTRACT", "persisted draft hashes invalid");
    return validation;
  }

  private static List<Map<String, Object>> wire(List<BehaviorGraphValidationIssue> issues) {
    List<Map<String, Object>> out = new ArrayList<>(issues.size());
    for (BehaviorGraphValidationIssue issue : issues) out.add(issue.toWire());
    return out;
  }

  private Map<String, Object> publicationResponse(BehaviorGraphPublication publication) {
    if (publication.getPublicationId() == null)
      throw error(HttpStatus.UNPROCESSABLE_ENTITY, "MALFORMED_CONTRACT", "publication id missing");
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("schema", "behavior-graph-publication-response/v1");
    out.put("publication_id", "publication_" + publication.getPublicationId());
    out.put("draft_id", publication.getSourceDraftId());
    out.put("source_draft_revision", publication.getSourceDraftRevision());
    out.put("graph_id", publication.getGraphId());
    out.put("graph_version", publication.getGraphVersion());
    out.put("graph_hash", publication.getGraphHash());
    out.put("layout_hash", publication.getLayoutHash());
    out.put("published_at", publication.getPublishedAt().toString());
    out.put("authority", authority());
    out.put("control_allowed", false);
    try {
      schemas.validatePublicationResponse(mapper.valueToTree(out));
    } catch (IllegalArgumentException e) {
      throw error(
          HttpStatus.UNPROCESSABLE_ENTITY, "MALFORMED_CONTRACT", "publication response invalid");
    }
    return out;
  }

  private JsonNode read(String v) {
    try {
      return mapper.readTree(v);
    } catch (Exception e) {
      throw error(HttpStatus.UNPROCESSABLE_ENTITY, "MALFORMED_CONTRACT", "persisted JSON invalid");
    }
  }

  private String write(JsonNode v) {
    try {
      return mapper.writeValueAsString(v);
    } catch (Exception e) {
      throw error(HttpStatus.BAD_REQUEST, "MALFORMED_CONTRACT", "JSON cannot serialize");
    }
  }

  private DraftException error(HttpStatus s, String c, String m) {
    return new DraftException(s, c, m);
  }
}
