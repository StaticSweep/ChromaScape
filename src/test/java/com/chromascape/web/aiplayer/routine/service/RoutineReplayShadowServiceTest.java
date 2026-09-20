package com.chromascape.web.aiplayer.routine.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Policy replay used to pair the model's actions against the raw recording index for index and
 * demand equal lengths, so only a model that was a verbatim transcript of its recording could pass.
 * The 2026-08-29 acceptance recording is the counter-example these are built from: the operator
 * dismissed a menu, walked to the spot and double-clicked it, so the demonstrated routine is a
 * strict subsequence of the trace.
 */
class RoutineReplayShadowServiceTest {

  private final ObjectMapper mapper = new ObjectMapper();

  private JsonNode action(String option, String target, long tick) {
    return mapper.valueToTree(
        Map.of("kind", "MENU_OPTION_CLICKED", "option", option, "target", target, "tick", tick));
  }

  private List<JsonNode> recording() {
    return List.of(
        action("Cancel", "", 14),
        action("Walk here", "", 15),
        action("Small Net", "<col=ffff00>Fishing spot", 143),
        action("Small Net", "<col=ffff00>Fishing spot", 143));
  }

  @Test
  void aModelIsAllowedToAbstainOnTheOperatorsIncidentalClicks() {
    List<JsonNode> expected = List.of(action("Small Net", "<col=ffff00>Fishing spot", 143));

    List<Map<String, Object>> decisions =
        RoutineReplayShadowService.decisions(expected, recording());

    assertThat(RoutineReplayShadowService.passed(expected, decisions)).isTrue();
    assertThat(decisions).hasSize(4);
    assertThat(decisions)
        .extracting(d -> d.get("status"))
        .containsExactly("ABSTAINED", "ABSTAINED", "MATCHED", "ABSTAINED");
    // The abstentions carry the observed action so a failing run stays diagnosable.
    assertThat(decisions.get(0).get("observed")).isNotNull();
    assertThat(decisions.get(0).get("expected")).isNull();
  }

  @Test
  void anActionTheRecordingNeverShowsIsADivergence() {
    List<JsonNode> expected = List.of(action("Cage", "<col=ffff00>Fishing spot", 200));

    List<Map<String, Object>> decisions =
        RoutineReplayShadowService.decisions(expected, recording());

    assertThat(RoutineReplayShadowService.passed(expected, decisions)).isFalse();
    assertThat(decisions)
        .last()
        .satisfies(
            decision -> {
              assertThat(decision).containsEntry("status", "MISSING");
              assertThat(decision.get("observed")).isNull();
            });
  }

  @Test
  void orderIsStillEnforced() {
    List<JsonNode> expected =
        List.of(action("Small Net", "<col=ffff00>Fishing spot", 143), action("Walk here", "", 15));

    List<Map<String, Object>> decisions =
        RoutineReplayShadowService.decisions(expected, recording());

    // The walk precedes the net in the recording, so the second modelled action never matches.
    assertThat(RoutineReplayShadowService.passed(expected, decisions)).isFalse();
  }

  @Test
  void aModelThatClaimsNothingDoesNotPassByAbstainingOnEverything() {
    List<JsonNode> expected = List.of();

    List<Map<String, Object>> decisions =
        RoutineReplayShadowService.decisions(expected, recording());

    assertThat(decisions).extracting(d -> d.get("status")).containsOnly("ABSTAINED");
    assertThat(RoutineReplayShadowService.passed(expected, decisions)).isFalse();
  }

  @Test
  void anExactTranscriptStillPasses() {
    List<JsonNode> expected = recording();

    List<Map<String, Object>> decisions =
        RoutineReplayShadowService.decisions(expected, recording());

    assertThat(RoutineReplayShadowService.passed(expected, decisions)).isTrue();
    assertThat(decisions).extracting(d -> d.get("status")).containsOnly("MATCHED");
  }
}
