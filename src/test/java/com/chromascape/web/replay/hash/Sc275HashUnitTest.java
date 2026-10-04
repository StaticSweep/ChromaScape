package com.chromascape.web.replay.hash;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * SC-275 Java unit coverage: scalar policy rejections, digest strictness, chain tamper matrix, diff
 * redaction/limits, slot rejection reasons, version negotiation, and restore validation.
 */
class Sc275HashUnitTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String DIGEST = "sha256:" + "ab".repeat(32);

  @Test
  void canonicalBytesMatchTypeScriptVectors() throws Exception {
    JsonNode reordered = MAPPER.readTree("{\"b\":1,\"a\":[3,2],\"Z\":null}");
    assertThat(CausalCanonicalJson.canonicalize(reordered))
        .isEqualTo("{\"Z\":null,\"a\":[3,2],\"b\":1}");
    assertThat(CausalCanonicalJson.canonicalize(MAPPER.getNodeFactory().numberNode(-0.0)))
        .isEqualTo("0");
    assertThat(CausalCanonicalJson.canonicalize(MAPPER.readTree("3.0"))).isEqualTo("3");
  }

  @Test
  void scalarPolicyRejects() throws Exception {
    assertThatThrownBy(() -> CausalCanonicalJson.canonicalize(MAPPER.readTree("3.14")))
        .isInstanceOf(Sc275Exception.class)
        .hasMessageContaining("PROJECTION_VALUE_INVALID");
    assertThatThrownBy(() -> CausalCanonicalJson.canonicalize(MAPPER.readTree("9007199254740992")))
        .isInstanceOf(Sc275Exception.class);
    assertThatThrownBy(
            () -> CausalCanonicalJson.canonicalize(MAPPER.getNodeFactory().numberNode(Double.NaN)))
        .isInstanceOf(Sc275Exception.class);
    assertThatThrownBy(
            () ->
                CausalCanonicalJson.canonicalize(
                    MAPPER.getNodeFactory().numberNode(Double.POSITIVE_INFINITY)))
        .isInstanceOf(Sc275Exception.class);
    assertThatThrownBy(() -> CausalCanonicalJson.canonicalize(MAPPER.readTree("\"\\ud800\"")))
        .isInstanceOf(Sc275Exception.class);
    assertThatThrownBy(() -> CausalCanonicalJson.canonicalize(MAPPER.missingNode()))
        .isInstanceOf(Sc275Exception.class);
  }

  @Test
  void digestValidationIsStrict() {
    assertThat(SemanticHashV2.assertDigest(DIGEST, "d")).isEqualTo("ab".repeat(32));
    assertThatThrownBy(() -> SemanticHashV2.assertDigest("sha256:" + "AB".repeat(32), "d"))
        .isInstanceOf(Sc275Exception.class)
        .hasMessageContaining("HASH_DIGEST_INVALID");
    assertThatThrownBy(() -> SemanticHashV2.assertDigest("sha256:" + "ab".repeat(31), "d"))
        .isInstanceOf(Sc275Exception.class);
    assertThatThrownBy(() -> SemanticHashV2.assertDigest("ab".repeat(32), "d"))
        .isInstanceOf(Sc275Exception.class);
  }

  @Test
  void chainTamperMatrixRejects() throws Exception {
    ObjectMapper mapper = MAPPER;
    ObjectNode genesis = frame(0, 0, DIGEST, DIGEST);
    List<com.fasterxml.jackson.databind.JsonNode> frames =
        List.of(genesis, frame(1, 1, DIGEST, genesis.path("chainHash").asText()));
    TransitionChainVerifier.Anchors anchors =
        new TransitionChainVerifier.Anchors(
            DIGEST,
            DIGEST,
            genesis.path("previousChainHash").asText(),
            frames.get(1).path("chainHash").asText());
    assertThat(TransitionChainVerifier.verify(frames, anchors).valid()).isTrue();

    ObjectNode edited = genesis.deepCopy();
    edited.put("stateHash", "sha256:" + "dd".repeat(32));
    assertThat(TransitionChainVerifier.verify(List.of(edited, frames.get(1)), anchors).valid())
        .isFalse();
    assertThat(TransitionChainVerifier.verify(frames.subList(0, 1), anchors).valid()).isFalse();
    assertThat(
            TransitionChainVerifier.verify(
                    frames,
                    new TransitionChainVerifier.Anchors(
                        DIGEST, DIGEST, "sha256:" + "ee".repeat(32)))
                .valid())
        .isFalse();
    assertThat(
            TransitionChainVerifier.verify(
                    frames,
                    new TransitionChainVerifier.Anchors(
                        "sha256:" + "ee".repeat(32),
                        DIGEST,
                        genesis.path("previousChainHash").asText()))
                .reason())
        .contains("CHECKPOINT_IDENTITY_MISMATCH");
  }

  @Test
  void chainSequenceLifecycleMatrixRejects() throws Exception {
    ObjectNode reset = chainFrame(0, 0, 0, "RESET", "ABSTAINED", DIGEST);
    TransitionChainVerifier.Anchors anchors =
        new TransitionChainVerifier.Anchors(DIGEST, DIGEST, DIGEST);
    assertThat(TransitionChainVerifier.verify(List.of(reset), anchors).valid()).isTrue();

    // Duplicate tick/sequence pair.
    ObjectNode dup =
        chainFrame(1, 0, 0, "ACTION", "ACCEPTED", DIGEST, reset.path("chainHash").asText());
    assertThat(TransitionChainVerifier.verify(List.of(reset, dup), anchors).valid()).isFalse();

    // Skipped sequence.
    ObjectNode skipped =
        chainFrame(1, 0, 2, "ACTION", "ACCEPTED", DIGEST, reset.path("chainHash").asText());
    assertThat(TransitionChainVerifier.verify(List.of(reset, skipped), anchors).valid()).isFalse();

    // Tick regression.
    ObjectNode first =
        chainFrame(1, 1, 0, "ACTION", "ACCEPTED", DIGEST, reset.path("chainHash").asText());
    ObjectNode regressed =
        chainFrame(2, 0, 0, "ACTION", "ACCEPTED", DIGEST, first.path("chainHash").asText());
    assertThat(TransitionChainVerifier.verify(List.of(reset, first, regressed), anchors).valid())
        .isFalse();

    // Mid-chain RESET and missing RESET-first.
    ObjectNode midReset =
        chainFrame(1, 0, 1, "RESET", "ABSTAINED", DIGEST, reset.path("chainHash").asText());
    assertThat(TransitionChainVerifier.verify(List.of(reset, midReset), anchors).valid()).isFalse();
    ObjectNode noReset = chainFrame(0, 0, 0, "ACTION", "ACCEPTED", DIGEST);
    assertThat(TransitionChainVerifier.verify(List.of(noReset), anchors).valid()).isFalse();

    // Nothing may follow TERMINAL.
    ObjectNode terminal =
        chainFrame(1, 1, 0, "TERMINAL", "TERMINAL", DIGEST, reset.path("chainHash").asText());
    ObjectNode after =
        chainFrame(2, 2, 0, "ACTION", "ACCEPTED", DIGEST, terminal.path("chainHash").asText());
    assertThat(TransitionChainVerifier.verify(List.of(reset, terminal), anchors).valid()).isTrue();
    assertThat(TransitionChainVerifier.verify(List.of(reset, terminal, after), anchors).valid())
        .isFalse();
  }

  @Test
  void chainSuffixStartVerifiesContinuation() throws Exception {
    ObjectNode reset = chainFrame(0, 0, 0, "RESET", "ABSTAINED", DIGEST);
    ObjectNode first =
        chainFrame(1, 0, 1, "ACTION", "ACCEPTED", DIGEST, reset.path("chainHash").asText());
    ObjectNode second =
        chainFrame(2, 1, 0, "ADVANCE", "ABSTAINED", DIGEST, first.path("chainHash").asText());
    TransitionChainVerifier.Anchors anchors =
        new TransitionChainVerifier.Anchors(
            DIGEST, DIGEST, DIGEST, second.path("chainHash").asText());
    TransitionChainVerifier.SuffixStart start =
        new TransitionChainVerifier.SuffixStart(1, 0, 1, reset.path("chainHash").asText());
    assertThat(TransitionChainVerifier.verify(List.of(first, second), anchors, start).valid())
        .isTrue();
    TransitionChainVerifier.SuffixStart wrongPredecessor =
        new TransitionChainVerifier.SuffixStart(1, 0, 1, DIGEST);
    assertThat(
            TransitionChainVerifier.verify(List.of(first, second), anchors, wrongPredecessor)
                .valid())
        .isFalse();
  }

  private static ObjectNode chainFrame(
      int ordinal, long tick, long sequence, String kind, String result, String previous) {
    return chainFrame(ordinal, tick, sequence, kind, result, DIGEST, previous);
  }

  private static ObjectNode chainFrame(
      int ordinal,
      long tick,
      long sequence,
      String kind,
      String result,
      String stateHash,
      String previous) {
    ObjectNode frame = MAPPER.createObjectNode();
    frame.put("traceVersion", 2);
    frame.put("manifestDigest", DIGEST);
    frame.put("runtimeDigest", DIGEST);
    frame.put("tick", tick);
    frame.put("sequence", sequence);
    frame.put("ordinal", ordinal);
    frame.put("kind", kind);
    String actionDigest = SemanticHashV2.nullActionDigest();
    String resultDigest =
        SemanticHashV2.resultDigest(result, result.equals("TERMINAL") ? "end-tick" : "no-action");
    frame.put("actionDigest", actionDigest);
    frame.put("resultDigest", resultDigest);
    frame.put("stateHash", stateHash);
    frame.put("previousChainHash", previous);
    frame.put(
        "chainHash",
        SemanticHashV2.chainHash(
            DIGEST,
            DIGEST,
            previous,
            tick,
            sequence,
            ordinal,
            kind,
            actionDigest,
            resultDigest,
            stateHash));
    return frame;
  }

  private static ObjectNode frame(int ordinal, int tick, String stateHash, String previous) {
    ObjectNode frame = MAPPER.createObjectNode();
    frame.put("traceVersion", 2);
    frame.put("manifestDigest", DIGEST);
    frame.put("runtimeDigest", DIGEST);
    frame.put("tick", tick);
    frame.put("sequence", 0);
    frame.put("ordinal", ordinal);
    frame.put("kind", ordinal == 0 ? "RESET" : "ACTION");
    frame.put("actionDigest", SemanticHashV2.nullActionDigest());
    frame.put("resultDigest", SemanticHashV2.resultDigest("ABSTAINED", "no-action"));
    frame.put("stateHash", stateHash);
    frame.put("previousChainHash", previous);
    frame.put(
        "chainHash",
        SemanticHashV2.chainHash(
            DIGEST,
            DIGEST,
            previous,
            tick,
            0,
            ordinal,
            ordinal == 0 ? "RESET" : "ACTION",
            SemanticHashV2.nullActionDigest(),
            SemanticHashV2.resultDigest("ABSTAINED", "no-action"),
            stateHash));
    return frame;
  }

  @Test
  void diffRedactsPrivateStringsAndEscapesPointers() throws Exception {
    StructuralDiff.DiffResult result =
        StructuralDiff.diffValues(
            MAPPER.readTree("{\"a/b\":1,\"secret\":\"x\"}"),
            MAPPER.readTree("{\"a/b\":2,\"secret\":\"y\"}"),
            "CAUSAL_STATE",
            "world");
    List<StructuralDiff.StateDifference> diffs = result.differences();
    assertThat(diffs).hasSize(2);
    assertThat(diffs.get(0).path()).isEqualTo("/a~1b");
    assertThat(diffs.get(1).actual().asText()).isEqualTo("[redacted]");
    assertThat(result.truncated()).isFalse();
  }

  @Test
  void diffBoundsOutputBudgetAcrossDomains() throws Exception {
    ObjectNode left = MAPPER.createObjectNode();
    ObjectNode right = MAPPER.createObjectNode();
    for (int index = 0; index < 10; index++) {
      left.put("k" + index, index);
    }
    Map<String, JsonNode> expected = Map.of("d1", left, "d2", left);
    Map<String, JsonNode> actual = Map.of("d1", right, "d2", right);
    StructuralDiff.DiffResult result =
        StructuralDiff.diffDomains(
            0,
            expected,
            0,
            actual,
            List.of("d1", "d2"),
            "CAUSAL_STATE",
            new StructuralDiff.DiffLimits(32, 10000, 5, 1024));
    // Ten differences per domain would exceed the budget without sharing;
    // the shared budget caps the total and reports truncation.
    assertThat(result.differences()).hasSize(5);
    assertThat(result.truncated()).isTrue();
  }

  @Test
  void diffSurvivesHostileDepth() throws Exception {
    JsonNode deepLeft = MAPPER.readTree("{\"value\":1}");
    JsonNode deepRight = MAPPER.readTree("{\"value\":2}");
    for (int index = 0; index < 100000; index++) {
      ObjectNode wrapLeft = MAPPER.createObjectNode();
      wrapLeft.set("l", deepLeft);
      deepLeft = wrapLeft;
      ObjectNode wrapRight = MAPPER.createObjectNode();
      wrapRight.set("l", deepRight);
      deepRight = wrapRight;
    }
    // A hostile maxDepth must not control call-stack use: traversal is
    // iterative, so a 100k-deep input completes with bounded output.
    StructuralDiff.DiffResult result =
        StructuralDiff.diffValues(
            deepLeft,
            deepRight,
            "CAUSAL_STATE",
            "world",
            null,
            null,
            null,
            new StructuralDiff.DiffLimits(200000, 500000, 10, 1024));
    assertThat(result.differences()).hasSize(1);
    assertThat(result.truncated()).isFalse();
    StructuralDiff.DiffResult clipped =
        StructuralDiff.diffValues(
            deepLeft,
            deepRight,
            "CAUSAL_STATE",
            "world",
            null,
            null,
            null,
            new StructuralDiff.DiffLimits(8, 10000, 10, 1024));
    assertThat(clipped.truncated()).isTrue();
    assertThat(clipped.differences().size()).isLessThanOrEqualTo(10);
  }

  @Test
  void negotiationRequiresV2() throws Exception {
    JsonNode v1Only =
        MAPPER.readTree(
            "{\"projectionVersions\":[1],\"hashVersions\":[\"sha256-canonical-json-v1\"]}");
    assertThat(CausalVersionNegotiation.supportsCausalV2(v1Only)).isFalse();
    assertThatThrownBy(
            () ->
                CausalVersionNegotiation.requireCausalV2(
                    MAPPER.readTree("{\"projectionVersions\":[1,2],\"hashVersions\":[]}")))
        .isInstanceOf(Sc275Exception.class)
        .hasMessageContaining("PROJECTION_VERSION_UNSUPPORTED");
    CausalVersionNegotiation.requireCausalV2(
        MAPPER.readTree(
            "{\"projectionVersions\":[2],\"hashVersions\":[\"sha256-semantic-state-v2\"]"
                + ",\"traceVersions\":[2],\"snapshotVersions\":[1],\"contextVersions\":[2]}"));
  }

  @Test
  void hashProjectionRejectsUnknownVersionsAndMissingDomains() throws Exception {
    Map<String, JsonNode> domains = new java.util.LinkedHashMap<>();
    JsonNode fixture;
    try (var in = getClass().getResourceAsStream("/replay/contracts/sc275-golden-vectors.json")) {
      fixture = MAPPER.readTree(in);
    }
    fixture
        .path("sampleProjection")
        .path("domains")
        .fields()
        .forEachRemaining(e -> domains.put(e.getKey(), e.getValue()));
    SemanticHashV2.Hashes hashes = SemanticHashV2.hashProjection(2, 0, domains);
    assertThat(hashes.domainHashes().keySet())
        .containsExactlyInAnyOrderElementsOf(SemanticHashV2.REQUIRED_DOMAINS);
    assertThatThrownBy(() -> SemanticHashV2.hashProjection(99, 0, domains))
        .isInstanceOf(Sc275Exception.class)
        .hasMessageContaining("PROJECTION_VERSION_UNSUPPORTED");
    Map<String, JsonNode> partial = new java.util.LinkedHashMap<>(domains);
    partial.remove("world");
    assertThatThrownBy(() -> SemanticHashV2.hashProjection(2, 0, partial))
        .isInstanceOf(Sc275Exception.class)
        .hasMessageContaining("PROJECTION_STATE_INCOMPLETE");
  }

  @Test
  void restoreValidationAnchorsIdentity() throws Exception {
    ObjectNode context = MAPPER.createObjectNode();
    context.put("contextVersion", 1);
    context.put("manifestDigest", DIGEST);
    context.put("runtimeDigest", DIGEST);
    context.put("snapshotVersion", 1);
    context.put("nextTransitionOrdinal", 4);
    context.put("predecessorChainHash", DIGEST);
    assertThat(RestoreContextValidator.validate(context, DIGEST, DIGEST).valid()).isTrue();
    assertThat(
            RestoreContextValidator.validate(context, "sha256:" + "ee".repeat(32), DIGEST).reason())
        .contains("CHECKPOINT_IDENTITY_MISMATCH");
  }

  @Test
  void restoreValidationEnforcesSnapshotVersionAndV2Bindings() throws Exception {
    ObjectNode context = MAPPER.createObjectNode();
    context.put("contextVersion", 2);
    context.put("manifestDigest", DIGEST);
    context.put("runtimeDigest", DIGEST);
    context.put("snapshotVersion", 1);
    context.put("snapshotDigest", DIGEST);
    context.put("checkpointStateDigest", DIGEST);
    context.put("tick", 7);
    context.put("nextTransitionOrdinal", 7);
    context.put("predecessorChainHash", DIGEST);
    assertThat(RestoreContextValidator.validate(context, DIGEST, DIGEST).valid()).isTrue();

    ObjectNode badSnapshot = context.deepCopy();
    badSnapshot.put("snapshotVersion", 99);
    assertThat(RestoreContextValidator.validate(badSnapshot, DIGEST, DIGEST).reason())
        .contains("PROJECTION_VERSION_UNSUPPORTED");

    ObjectNode badDigest = context.deepCopy();
    badDigest.put("snapshotDigest", "bad");
    assertThat(RestoreContextValidator.validate(badDigest, DIGEST, DIGEST).reason())
        .contains("HASH_DIGEST_INVALID");

    ObjectNode unknownContext = context.deepCopy();
    unknownContext.put("contextVersion", 99);
    assertThat(RestoreContextValidator.validate(unknownContext, DIGEST, DIGEST).reason())
        .contains("PROJECTION_VERSION_UNSUPPORTED");
  }

  @Test
  void hashProjectionRejectsExtraDomains() throws Exception {
    Map<String, JsonNode> domains = new java.util.LinkedHashMap<>();
    for (String domain : SemanticHashV2.REQUIRED_DOMAINS) {
      domains.put(domain, MAPPER.createObjectNode());
    }
    domains.put("bonus", MAPPER.createObjectNode());
    assertThatThrownBy(() -> SemanticHashV2.hashProjection(2, 0, domains))
        .isInstanceOf(Sc275Exception.class)
        .hasMessageContaining("PROJECTION_VALUE_INVALID");
  }

  @Test
  void diffClipsCyclesAndRejectsInvalidLimits() throws Exception {
    ObjectNode loop = MAPPER.createObjectNode();
    loop.put("name", "loop");
    loop.set("self", loop);
    ObjectNode other = MAPPER.createObjectNode();
    other.put("name", "other");
    other.set("self", other);
    ObjectNode left = MAPPER.createObjectNode();
    left.set("loop", loop);
    ObjectNode right = MAPPER.createObjectNode();
    right.set("loop", other);
    StructuralDiff.DiffResult result =
        StructuralDiff.diffValues(
            left,
            right,
            "CAUSAL_STATE",
            "world",
            null,
            null,
            null,
            new StructuralDiff.DiffLimits(200000, 500000, 100, 1024));
    assertThat(result.truncated()).isTrue();
    assertThat(result.differences()).anyMatch(d -> d.expected().asText().equals("[cycle]"));

    ObjectNode smallLeft = MAPPER.createObjectNode();
    smallLeft.put("a", 1);
    ObjectNode smallRight = MAPPER.createObjectNode();
    smallRight.put("a", 2);
    assertThatThrownBy(
            () ->
                StructuralDiff.diffValues(
                    smallLeft,
                    smallRight,
                    "CAUSAL_STATE",
                    "world",
                    null,
                    null,
                    null,
                    new StructuralDiff.DiffLimits(-1, 10000, 100, 1024)))
        .isInstanceOf(Sc275Exception.class)
        .hasMessageContaining("PROJECTION_DIFF_LIMIT");
  }

  @Test
  void slotsRejectStaleEmptyAndMasked() {
    EntitySlotView.SlotView view =
        EntitySlotView.build(
            List.of(new EntitySlotView.SlotEntity("npc:a", 1, 1)), 1, 1, 5, DIGEST, null);
    assertThat(EntitySlotView.resolve(view, 5, 0, DIGEST, java.util.Set.of("npc:a")))
        .isEqualTo("npc:a");
    assertThatThrownBy(() -> EntitySlotView.resolve(view, 6, 0, DIGEST, java.util.Set.of("npc:a")))
        .isInstanceOf(Sc275Exception.class)
        .hasMessageContaining("SLOT_VIEW_STALE");
    EntitySlotView.SlotView masked =
        EntitySlotView.build(
            List.of(new EntitySlotView.SlotEntity("npc:a", 1, 1)),
            1,
            1,
            5,
            DIGEST,
            java.util.Set.of());
    assertThatThrownBy(
            () -> EntitySlotView.resolve(masked, 5, 0, DIGEST, java.util.Set.of("npc:a")))
        .isInstanceOf(Sc275Exception.class)
        .hasMessageContaining("SLOT_ACTION_MASKED");
  }

  @Test
  void stateHashCoversEveryDomain() {
    Map<String, String> hashes = Map.of("a", DIGEST, "b", DIGEST);
    String first = SemanticHashV2.stateHash(7, hashes);
    assertThat(SemanticHashV2.stateHash(8, hashes)).isNotEqualTo(first);
  }

  @Test
  void restoreSchemaRejectsCoercionOverflowAndUnanchoredHistory() {
    ObjectNode context = MAPPER.createObjectNode();
    context.put("contextVersion", 1);
    context.put("manifestDigest", DIGEST);
    context.put("runtimeDigest", DIGEST);
    context.put("snapshotVersion", 1);
    context.put("nextTransitionOrdinal", 1);
    context.put("predecessorChainHash", DIGEST);
    assertThat(RestoreContextValidator.validate(context, DIGEST, DIGEST).valid()).isTrue();
    context.put("contextVersion", "1");
    assertThat(RestoreContextValidator.validate(context, DIGEST, DIGEST).valid()).isFalse();
    context.put("contextVersion", 1);
    context.put("nextTransitionOrdinal", new java.math.BigInteger("18446744073709551616"));
    assertThat(RestoreContextValidator.validate(context, DIGEST, DIGEST).valid()).isFalse();
    context.put("nextTransitionOrdinal", 1);
    context.putNull("predecessorChainHash");
    assertThat(RestoreContextValidator.validate(context, DIGEST, DIGEST).valid()).isFalse();
  }

  @Test
  void utf8ZeroCountAndWideDisplayRespectBudgets() throws Exception {
    for (int bytes : new int[] {0, 1, 2, 3, 4}) {
      var result =
          StructuralDiff.diffValues(
              MAPPER.readTree("{\"name\":\"é😀\"}"),
              MAPPER.readTree("{\"name\":\"x\"}"),
              "CAUSAL_STATE",
              "world",
              null,
              null,
              null,
              new StructuralDiff.DiffLimits(32, 100, 1, bytes));
      for (var difference : result.differences()) {
        assertThat(
                difference
                    .expected()
                    .asText()
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)
                    .length)
            .isLessThanOrEqualTo(bytes);
        assertThat(difference.expected().asText()).doesNotContain("�");
      }
    }
    var zero = new StructuralDiff.DiffLimits(32, 100, 0, 1);
    assertThat(
            StructuralDiff.diffTransitions(
                    java.util.List.of(MAPPER.readTree("{\"tick\":1}")),
                    java.util.List.of(MAPPER.readTree("{\"tick\":2}")),
                    "CAUSAL_STATE",
                    zero)
                .differences())
        .isEmpty();
    assertThat(
            StructuralDiff.diffDomains(
                    0,
                    java.util.Map.of(),
                    1,
                    java.util.Map.of(),
                    java.util.List.of(),
                    "CAUSAL_STATE",
                    zero)
                .differences())
        .isEmpty();
    var wide = MAPPER.createObjectNode();
    for (int i = 0; i < 10000; i++) {
      wide.put("k" + i, i);
    }
    var clipped =
        StructuralDiff.diffValues(
            MAPPER.nullNode(),
            wide,
            "CAUSAL_STATE",
            "world",
            null,
            null,
            null,
            new StructuralDiff.DiffLimits(32, 1, 1, 1));
    assertThat(clipped.truncated()).isTrue();
    assertThat(
            clipped
                .differences()
                .get(0)
                .actual()
                .asText()
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)
                .length)
        .isLessThanOrEqualTo(1);
  }

  @Test
  void emptyRequiredDomainContentsReject() {
    Map<String, JsonNode> empty = new java.util.TreeMap<>();
    for (String domain : SemanticHashV2.REQUIRED_DOMAINS) {
      empty.put(domain, MAPPER.createObjectNode());
    }
    assertThatThrownBy(() -> SemanticHashV2.hashProjection(2, 0, empty))
        .isInstanceOf(Sc275Exception.class)
        .hasMessageContaining("PROJECTION_STATE_INCOMPLETE");
  }
}
