package com.chromascape.web.replay.worker;

import static org.assertj.core.api.Assertions.*;

import com.chromascape.web.replay.error.ReplayException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class NativeRunProtocolTest {
  private final ObjectMapper mapper = new ObjectMapper();

  private ObjectNode protocol() {
    return mapper
        .createObjectNode()
        .put("version", 1)
        .put("stateSchema", "xrsps-run-state/v2")
        .put("policySchema", "xrsps-termination-policy/v1")
        .put("snapshotDescriptorVersion", 4)
        .put("evaluation", "RESET_ACTION_EACH_TICK");
  }

  @Test
  void absentProtocolIsLegacyNotInferredAndExactProtocolDoesNotAttestCertification() {
    ObjectNode capability =
        mapper.createObjectNode().put("certifiable", false).put("fidelity", "SYNTHETIC_CONTRACT");
    assertThat(NativeRunProtocol.negotiated(capability)).isFalse();
    capability.set("nativeRunProtocol", protocol());
    assertThat(NativeRunProtocol.negotiated(capability)).isTrue();
    assertThat(capability.path("certifiable").asBoolean()).isFalse();
    capability.putObject("causal");
    assertThatThrownBy(() -> NativeRunProtocol.negotiated(capability))
        .isInstanceOf(ReplayException.class)
        .hasMessageContaining("mixed causal-v2");
  }

  static Stream<Arguments> mutations() {
    List<Arguments> cases = new ArrayList<>();
    for (String field :
        List.of(
            "version", "stateSchema", "policySchema", "snapshotDescriptorVersion", "evaluation")) {
      cases.add(Arguments.of("missing " + field, (Consumer<ObjectNode>) p -> p.remove(field)));
      cases.add(Arguments.of("null " + field, (Consumer<ObjectNode>) p -> p.putNull(field)));
    }
    cases.add(Arguments.of("version two", (Consumer<ObjectNode>) p -> p.put("version", 2)));
    cases.add(Arguments.of("string version", (Consumer<ObjectNode>) p -> p.put("version", "1")));
    cases.add(Arguments.of("decimal version", (Consumer<ObjectNode>) p -> p.put("version", 1.0)));
    cases.add(
        Arguments.of(
            "old state", (Consumer<ObjectNode>) p -> p.put("stateSchema", "xrsps-run-state/v1")));
    cases.add(
        Arguments.of(
            "new policy",
            (Consumer<ObjectNode>) p -> p.put("policySchema", "xrsps-termination-policy/v2")));
    cases.add(
        Arguments.of(
            "old descriptor", (Consumer<ObjectNode>) p -> p.put("snapshotDescriptorVersion", 3)));
    cases.add(
        Arguments.of(
            "string descriptor",
            (Consumer<ObjectNode>) p -> p.put("snapshotDescriptorVersion", "4")));
    cases.add(
        Arguments.of(
            "weaker evaluation", (Consumer<ObjectNode>) p -> p.put("evaluation", "FINAL_ONLY")));
    cases.add(
        Arguments.of(
            "extra permission", (Consumer<ObjectNode>) p -> p.put("controlAllowed", true)));
    return cases.stream();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("mutations")
  void unknownOrPartialProtocolCannotBeSelected(String name, Consumer<ObjectNode> mutation) {
    ObjectNode protocol = protocol();
    mutation.accept(protocol);
    ObjectNode capability = mapper.createObjectNode().set("nativeRunProtocol", protocol);
    assertThatThrownBy(() -> NativeRunProtocol.negotiated(capability))
        .isInstanceOf(ReplayException.class)
        .hasMessageContaining("NATIVE_RUN_PROTOCOL_INCOMPATIBLE");
  }

  @Test
  void nullScalarAndArrayProtocolsAreNotAbsence() {
    for (var value :
        List.of(
            mapper.nullNode(), mapper.createArrayNode(), mapper.getNodeFactory().textNode("v1"))) {
      ObjectNode capability = mapper.createObjectNode().set("nativeRunProtocol", value);
      assertThatThrownBy(() -> NativeRunProtocol.negotiated(capability))
          .isInstanceOf(ReplayException.class);
    }
  }
}
