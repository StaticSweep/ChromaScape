package com.chromascape.web.replay.conversion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.chromascape.web.debug.tickdebugger.dto.RecordingAction;
import com.chromascape.web.debug.tickdebugger.entity.ReplayRecordingAction;
import com.chromascape.web.debug.tickdebugger.entity.ReplayRecordingActionRepository;
import com.chromascape.web.debug.tickdebugger.entity.TickLog;
import com.chromascape.web.debug.tickdebugger.entity.TickLogRepository;
import com.chromascape.web.debug.tickdebugger.entity.TickSnapshotRepository;
import com.chromascape.web.replay.projection.CanonicalProjectionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReplayActionConversionServiceTest {

  private final ReplayRecordingActionRepository typedRepository =
      mock(ReplayRecordingActionRepository.class);
  private final TickLogRepository logRepository = mock(TickLogRepository.class);
  private final TickSnapshotRepository snapshotRepository = mock(TickSnapshotRepository.class);
  private final ObjectMapper objectMapper = new ObjectMapper();
  private ReplayActionConversionService service;

  @BeforeEach
  void setUp() {
    service =
        new ReplayActionConversionService(
            typedRepository,
            logRepository,
            snapshotRepository,
            objectMapper,
            new CanonicalProjectionService());
    when(snapshotRepository.findBySessionIdAndTickBetweenOrderByTick("session-1", 0L, 1L))
        .thenReturn(List.of());
  }

  @Test
  void mixedTypedAndLegacyDispatchesCannotBeCertified() {
    when(typedRepository.findBySessionIdAndTickBetweenOrderByTickAscSequenceAsc(
            "session-1", 0L, 1L))
        .thenReturn(List.of(typedAction(0, 0)));
    when(logRepository.findBySessionIdAndTickBetweenOrderByTickAsc("session-1", 0L, 1L))
        .thenReturn(
            List.of(
                menuLog(0, 0, "Net"),
                // This dispatch has no typed counterpart and must downgrade the stream.
                menuLog(1, 1, "Talk-to")));

    ReplayActionConversionResult result =
        service.convert("session-1", 0, 1, ConversionMode.AUTO);

    assertThat(result.quality()).isEqualTo(ConversionQuality.BEST_EFFORT);
    assertThat(result.gaps())
        .anyMatch(gap -> "TYPED_ACTION_STREAM_INCOMPLETE".equals(gap.code()));
  }

  @Test
  void matchingTypedAndLegacyDispatchesCanBeCertified() {
    when(typedRepository.findBySessionIdAndTickBetweenOrderByTickAscSequenceAsc(
            "session-1", 0L, 1L))
        .thenReturn(List.of(typedAction(0, 0)));
    when(logRepository.findBySessionIdAndTickBetweenOrderByTickAsc("session-1", 0L, 1L))
        .thenReturn(List.of(menuLog(0, 0, "Net")));

    ReplayActionConversionResult result =
        service.convert("session-1", 0, 1, ConversionMode.AUTO);

    assertThat(result.quality()).isEqualTo(ConversionQuality.CERTIFIED);
    assertThat(result.gaps()).isEmpty();
  }

  private ReplayRecordingAction typedAction(long tick, int sequence) {
    ReplayRecordingAction action = new ReplayRecordingAction();
    action.setSessionId("session-1");
    action.setTick(tick);
    action.setSequence(sequence);
    action.setSchema(RecordingAction.SCHEMA);
    action.setActionType("INTERACT_NPC");
    ObjectNode target = objectMapper.createObjectNode();
    target.put("kind", "NPC");
    target.put("stableId", "npc:1518:42");
    action.setTargetJson(target);
    action.setTargetStableId("npc:1518:42");
    ObjectNode arguments = objectMapper.createObjectNode();
    arguments.put("option", "net");
    action.setArgumentsJson(arguments);
    action.setSource(RecordingAction.SOURCE_MICRO6_SEMANTIC_DISPATCH);
    action.setConfidence(BigDecimal.ONE);
    action.setCapturedAt(LocalDateTime.of(2026, 8, 31, 22, 0));
    return action;
  }

  private TickLog menuLog(long enclosingTick, long sourceTick, String option) {
    TickLog log =
        new TickLog(
            "session-1",
            enclosingTick,
            LocalDateTime.of(2026, 8, 31, 22, 0),
            "PLAYER_ACTION",
            "INFO",
            option + " Fishing spot");
    log.setEventType("MENU_CLICK");
    ObjectNode details = objectMapper.createObjectNode();
    details.put("sourceTick", sourceTick);
    details.put("sequence", sourceTick);
    details.put("option", option);
    details.put("menuAction", "NPC_FIRST_OPTION");
    details.put("identifier", 1518);
    details.put("stableId", "npc:1518:42");
    log.setDetails(details);
    return log;
  }
}
