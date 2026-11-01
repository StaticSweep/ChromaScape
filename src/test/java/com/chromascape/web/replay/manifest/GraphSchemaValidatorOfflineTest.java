package com.chromascape.web.replay.manifest;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chromascape.web.aiplayer.routine.service.GraphSchemaValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The contract schemas declare a public {@code $id}, so a relative {@code $ref} must resolve from
 * the classpath rather than the network. This runs with no network access and would fail with an
 * unreachable {@code contracts.leeuwbot.dev} URI without the local loader.
 */
class GraphSchemaValidatorOfflineTest {
  private static final String HASH = "sha256:" + "a".repeat(64);
  private final ObjectMapper json = new ObjectMapper();

  @Test
  void draftResponseResolvesSiblingContractSchemasLocally() {
    GraphSchemaValidator validator = new GraphSchemaValidator();

    assertThatCode(() -> validator.validateDraftResponse(draftResponse()))
        .doesNotThrowAnyException();

    // The nested sibling schema is actually applied, not silently skipped.
    ObjectNode invalid = draftResponse();
    ((ObjectNode) invalid.path("definition")).remove("edges");
    assertThatThrownBy(() -> validator.validateDraftResponse(invalid))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void publicationResponseAndRequestsStayOfflineSafe() {
    GraphSchemaValidator validator = new GraphSchemaValidator();

    assertThatCode(() -> validator.validateDraftCreateRequest(createRequest()))
        .doesNotThrowAnyException();
    assertThatCode(() -> validator.validatePublishRequest(publishRequest()))
        .doesNotThrowAnyException();
    assertThatCode(() -> validator.validatePublicationResponse(publicationResponse()))
        .doesNotThrowAnyException();
  }

  private ObjectNode createRequest() {
    return json.createObjectNode()
        .put("schema", "behavior-graph-draft-create-request/v1")
        .put("draft_id", "sc274_human_fishing_draft")
        .put("graph_id", "sc274_human_fishing")
        .put("task_key", "FISHING_TRAINING")
        .put("profile_id", "1a67835e-3c32-4d95-94ab-46b537ff5687")
        .put("profile_version", "1");
  }

  private ObjectNode publishRequest() {
    return json.createObjectNode()
        .put("schema", "behavior-graph-publish-request/v1")
        .put("base_revision", 1)
        .put("graph_hash", HASH)
        .put("layout_hash", HASH);
  }

  private ObjectNode publicationResponse() {
    ObjectNode out = json.createObjectNode();
    out.put("schema", "behavior-graph-publication-response/v1")
        .put("publication_id", "publication_1")
        .put("draft_id", "sc274_human_fishing_draft")
        .put("source_draft_revision", 1)
        .put("graph_id", "sc274_human_fishing")
        .put("graph_version", 1)
        .put("graph_hash", HASH)
        .put("layout_hash", HASH)
        .put("published_at", "2026-11-01T00:00:00Z");
    out.set("authority", authority());
    out.put("control_allowed", false);
    return out;
  }

  private ObjectNode draftResponse() {
    ObjectNode out = json.createObjectNode();
    out.put("schema", "behavior-graph-draft-response/v1")
        .put("draft_id", "sc274_human_fishing_draft")
        .put("revision", 1)
        .put("state", "DRAFT")
        .put("graph_hash", HASH)
        .put("layout_hash", HASH);
    out.set("definition", definition());
    out.set("layout", layout());
    out.set("validation", validation());
    out.set("authority", authority());
    out.put("control_allowed", false);
    return out;
  }

  private ObjectNode definition() {
    ObjectNode out = json.createObjectNode();
    out.put("schema", "behavior-graph-definition/v2")
        .put("schema_version", "behavior-graph-definition/v2")
        .put("graph_id", "sc274_human_fishing")
        .put("graph_hash", HASH)
        .put("control_allowed", false);
    out.set("task", json.createObjectNode().put("task_key", "FISHING_TRAINING"));
    out.set(
        "profile",
        json.createObjectNode()
            .put("profile_id", "1a67835e-3c32-4d95-94ab-46b537ff5687")
            .put("profile_version", "1"));
    out.set("authority", authority());
    ArrayNode nodes = out.putArray("nodes");
    nodesFor(List.of("start", "success")).forEach(nodes::add);
    ArrayNode edges = out.putArray("edges");
    edges
        .addObject()
        .put("id", "start_success")
        .put("from", "start")
        .put("to", "success")
        .put("priority", 0)
        .put("source_order", 0);
    return out;
  }

  private ArrayNode nodesFor(List<String> ids) {
    ArrayNode nodes = json.createArrayNode();
    for (String id : ids) {
      nodes
          .addObject()
          .put("id", id)
          .put("type", "start".equals(id) ? "START" : "SUCCESS")
          .put("label", id)
          .putObject("configuration");
    }
    return nodes;
  }

  private ObjectNode layout() {
    ObjectNode out = json.createObjectNode();
    out.put("schema", "behavior-graph-layout/v1")
        .put("schema_version", "behavior-graph-layout/v1")
        .put("graph_id", "sc274_human_fishing")
        .put("layout_hash", HASH);
    ArrayNode nodes = out.putArray("nodes");
    nodes.addObject().put("node_id", "start").put("x", 0).put("y", 0);
    nodes.addObject().put("node_id", "success").put("x", 280).put("y", 0);
    out.putObject("viewport").put("x", 0).put("y", 0).put("zoom", 1);
    out.putArray("groups");
    return out;
  }

  private ObjectNode validation() {
    ObjectNode out = json.createObjectNode();
    out.put("state", "VALID");
    out.putArray("errors");
    out.putArray("warnings");
    return out;
  }

  private ObjectNode authority() {
    ObjectNode out = json.createObjectNode();
    for (String field :
        List.of(
            "can_write_source",
            "can_approve_patch",
            "can_publish_graph",
            "can_activate_model",
            "can_dispatch_input")) out.put(field, false);
    return out;
  }
}
