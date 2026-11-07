package com.chromascape.web.replay.manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.chromascape.web.replay.error.ReplayException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class TerminationPolicyContractTest {
  private final ObjectMapper json = new ObjectMapper();

  @Test
  void sameClosedContractVectorsAreSharedWithRuntime() throws Exception {
    try (var stream =
        new ClassPathResource("replay/termination-policy-vectors-v1.json").getInputStream()) {
      JsonNode vectors = json.readTree(stream);
      assertThat(vectors.path("cases").size()).isEqualTo(39);
      for (JsonNode vector : vectors.path("cases")) {
        JsonNode policy = vector.path("policy"), before = policy.deepCopy();
        if (vector.path("valid").asBoolean()) TerminationPolicyContract.validate(policy);
        else
          assertThrows(
              ReplayException.class,
              () -> TerminationPolicyContract.validate(policy),
              vector.path("name").asText());
        assertThat(policy).isEqualTo(before);
      }
    }
  }

  @Test
  void selectedSourceIsDetachedAndOuterDocumentCannotInventDefaultConditions() {
    ObjectNode source = json.createObjectNode();
    source.putObject("contentSnapshot").set("termination", policy());
    ObjectNode selected = TerminationPolicyContract.fromSource(json, source);
    assertThat(selected).isEqualTo(source.path("contentSnapshot").path("termination"));
    selected.put("schema", "modified");
    assertThat(source.path("contentSnapshot").path("termination").path("schema").asText())
        .isEqualTo(TerminationPolicyContract.SCHEMA);
    ObjectNode manifest = json.createObjectNode();
    manifest.set("termination", policy());
    assertThrows(ReplayException.class, () -> TerminationPolicyContract.validateManifest(manifest));
    manifest.set("termination", TerminationPolicyContract.defaults(json));
    TerminationPolicyContract.validateManifest(manifest);
    assertThat(TerminationPolicyContract.nonempty(manifest)).isFalse();
  }

  @Test
  void depthNodesChildrenAndCategorySizesAreBounded() {
    ObjectNode nested = json.createObjectNode().put("op", "exists").put("pointer", "/a");
    for (int i = 1; i < 12; i++) {
      ObjectNode parent = json.createObjectNode().put("op", "not");
      parent.set("predicate", nested);
      nested = parent;
    }
    ObjectNode policy = policy();
    ((ObjectNode) policy.path("success").get(0)).set("predicate", nested);
    TerminationPolicyContract.validate(policy);
    ObjectNode parent = json.createObjectNode().put("op", "not");
    parent.set("predicate", nested);
    ((ObjectNode) policy.path("success").get(0)).set("predicate", parent);
    assertThrows(ReplayException.class, () -> TerminationPolicyContract.validate(policy));
    ObjectNode broad = json.createObjectNode().put("op", "all");
    var children = broad.putArray("predicates");
    for (int i = 0; i < 17; i++) children.addObject().put("op", "exists").put("pointer", "/a");
    ((ObjectNode) policy.path("success").get(0)).set("predicate", broad);
    assertThrows(ReplayException.class, () -> TerminationPolicyContract.validate(policy));
    children.remove(16);
    TerminationPolicyContract.validate(policy);
    for (int i = 1; i < 33; i++) {
      var clause = policy.withArray("success").addObject().put("id", "c" + i);
      clause.putObject("predicate").put("op", "exists").put("pointer", "/a");
    }
    assertThrows(ReplayException.class, () -> TerminationPolicyContract.validate(policy));
    policy.withArray("success").remove(32);
    for (JsonNode clause : policy.path("success"))
      ((ObjectNode) clause).set("predicate", broad.deepCopy());
    assertThrows(ReplayException.class, () -> TerminationPolicyContract.validate(policy));
  }

  private ObjectNode policy() {
    ObjectNode policy = TerminationPolicyContract.defaults(json);
    policy
        .withArray("success")
        .addObject()
        .put("id", "done")
        .putObject("predicate")
        .put("op", "exists")
        .put("pointer", "/a");
    return policy;
  }
}
