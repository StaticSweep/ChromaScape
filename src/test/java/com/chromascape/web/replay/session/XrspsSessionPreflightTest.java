package com.chromascape.web.replay.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class XrspsSessionPreflightTest {

  private XrspsSessionPreflight.Input eligibleInput() {
    return new XrspsSessionPreflight.Input(
        "recording_pinned_fishing",
        true,
        1.0,
        List.of(),
        true,
        true,
        List.of(),
        "rsprox:archived-binary");
  }

  @Test
  void eligibleRecordingPassesWithNoReasons() {
    XrspsSessionPreflight.Verdict verdict = XrspsSessionPreflight.evaluate(eligibleInput());
    assertTrue(verdict.eligible());
    assertTrue(verdict.reasons().isEmpty());
  }

  @Test
  void latestRecordingStaysBlockedWithExactReasons() {
    XrspsSessionPreflight.Input input =
        new XrspsSessionPreflight.Input(
            "recording_b83c37e5",
            false,
            1.0,
            List.of("exchange-npc"),
            false,
            true,
            List.of(),
            "debug_365ecab7650a47c892e4a21f54e50507");
    XrspsSessionPreflight.Verdict verdict = XrspsSessionPreflight.evaluate(input);
    assertFalse(verdict.eligible());
    assertEquals(
        List.of(
            XrspsSessionPreflight.BlockCode.TYPED_ACTION_STREAM_INCOMPLETE,
            XrspsSessionPreflight.BlockCode.UNRESOLVED_TARGET,
            XrspsSessionPreflight.BlockCode.NO_SUPPORTED_FISHING_NPC),
        verdict.reasons().stream().map(XrspsSessionPreflight.Reason::code).toList());
  }

  @Test
  void fullCoverageNeverCancelsIncompleteStreamWarning() {
    XrspsSessionPreflight.Input input =
        new XrspsSessionPreflight.Input(
            "recording_full_coverage_gap",
            false,
            1.0,
            List.of(),
            true,
            true,
            List.of(),
            "rsprox:archived-binary");
    XrspsSessionPreflight.Verdict verdict = XrspsSessionPreflight.evaluate(input);
    assertFalse(verdict.eligible());
    assertEquals(1, verdict.reasons().size());
    assertEquals(
        XrspsSessionPreflight.BlockCode.TYPED_ACTION_STREAM_INCOMPLETE,
        verdict.reasons().get(0).code());
  }

  @Test
  void unsupportedActionsAndIncompleteWorldBlockSeparately() {
    XrspsSessionPreflight.Input input =
        new XrspsSessionPreflight.Input(
            "recording_partial",
            true,
            0.9,
            List.of(),
            true,
            false,
            List.of("CAST_SPELL:teleport"),
            "compiler:source-mapping");
    XrspsSessionPreflight.Verdict verdict = XrspsSessionPreflight.evaluate(input);
    assertFalse(verdict.eligible());
    assertEquals(
        List.of(
            XrspsSessionPreflight.BlockCode.INITIAL_WORLD_INCOMPLETE,
            XrspsSessionPreflight.BlockCode.UNSUPPORTED_ACTION),
        verdict.reasons().stream().map(XrspsSessionPreflight.Reason::code).toList());
  }
}
