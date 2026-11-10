package com.chromascape.web.gameplay.provider.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.io.InputStream;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The v2 proposal schema is what the provider is asked for. It must accept the contract's canonical
 * v2 decision and reject a v1 decision outright, because a v1 output carries no GoalPlan and can
 * never be relabelled into a v2 delivery.
 */
class ProviderProposalSchemaV2Test {
  private final ObjectMapper objectMapper = JsonMapper.builder().build();

  @Test
  void acceptsTheCanonicalV2Decision() throws Exception {
    JsonSchema schema = schema();

    Set<?> violations = schema.validate(fixtures().path("claudeDecision"));

    assertThat(violations).isEmpty();
  }

  @Test
  void rejectsADecisionWithoutAGoalPlan() throws Exception {
    ObjectNode decision = (ObjectNode) fixtures().path("claudeDecision").deepCopy();
    decision.remove("goalPlan");

    assertThat(schema().validate(decision)).isNotEmpty();
  }

  @Test
  void rejectsAV1Decision() throws Exception {
    String v1 =
        "{\"contractVersion\":\"sc-1429/v1\",\"decisionId\":\"d-1\",\"policyConfidence\":0.9,"
            + "\"semanticAction\":{\"kind\":\"START_FISHING_TASK\",\"taskKey\":\"FISHING_TRAINING\","
            + "\"settings\":{\"targetLevel\":2,\"areaPolicy\":\"LUMBRIDGE\"},\"target\":\"FISHING\","
            + "\"expectedTransition\":\"FISHING_XP_INCREASED\",\"preconditions\":[\"ACCOUNT_IDLE\"],"
            + "\"verifierId\":\"fishing-xp-v1\",\"decisionId\":\"d-1\","
            + "\"evidenceRefs\":[\"observation:1\"]},\"evidenceRefs\":[]}";

    assertThat(schema().validate(objectMapper.readTree(v1))).isNotEmpty();
  }

  @Test
  void rejectsAGoalPlanNodeWithoutTaskSkillProvenance() throws Exception {
    ObjectNode decision = (ObjectNode) fixtures().path("claudeDecision").deepCopy();
    ((ObjectNode) decision.path("goalPlan").path("nodes").get(0)).remove("taskSkillRefs");

    assertThat(schema().validate(decision)).isNotEmpty();
  }

  private JsonSchema schema() {
    return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
        .getSchema(ProviderProposalSchemaV2.JSON);
  }

  private JsonNode fixtures() throws Exception {
    try (InputStream input =
        getClass()
            .getClassLoader()
            .getResourceAsStream("contracts/gameplay/sc-1429-golden-fixtures-v2.json")) {
      assertThat(input).as("v2 golden fixtures must be on the test classpath").isNotNull();
      return objectMapper.readTree(input);
    }
  }
}
