package com.chromascape.web.replay.curation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chromascape.web.replay.catalog.ReplayRecordingResolver;
import com.chromascape.web.replay.curation.dto.CuratedActionDto;
import com.chromascape.web.replay.curation.dto.UpdateCurationRequest;
import com.chromascape.web.replay.curation.entity.CuratedAction;
import com.chromascape.web.replay.curation.entity.CurationState;
import com.chromascape.web.replay.curation.entity.RecordingCuration;
import com.chromascape.web.replay.curation.repository.CuratedActionRepository;
import com.chromascape.web.replay.curation.repository.RecordingCurationRepository;
import com.chromascape.web.replay.error.ReplayException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Curation integrity: supplied actionLabels replace the draft collection transactionally,
 * content/checksum are rebuilt together, ownership is enforced with a uniform 404, and published
 * curations are immutable.
 */
@ExtendWith(MockitoExtension.class)
class ReplayCurationActionReplacementTest {

  @Mock private RecordingCurationRepository curationRepository;
  @Mock private CuratedActionRepository actionRepository;
  @Mock private ReplayRecordingResolver resolver;

  private final ObjectMapper mapper = new ObjectMapper();
  private ReplayCurationService service;
  private RecordingCuration draft;

  @BeforeEach
  void setUp() {
    service = new ReplayCurationService(curationRepository, actionRepository, resolver, mapper);
    draft = new RecordingCuration();
    draft.setId(UUID.randomUUID());
    draft.setRecordingId("rec-1");
    draft.setDebugSessionId("sess-1");
    draft.setVersion(1);
    draft.setState(CurationState.DRAFT);
    draft.setCreatedBy("alice");
    ObjectNode content = mapper.createObjectNode();
    content.putObject("tickRange").put("start", 0).put("end", 100);
    content.putArray("actionLabels");
    draft.setContentJson(content);
    draft.setContentChecksum("sha256:old");

    lenient().when(curationRepository.findById(draft.getId())).thenReturn(Optional.of(draft));
  }

  private CuratedActionDto label(long tick, int seq) {
    return new CuratedActionDto(
        tick,
        seq,
        "ACCEPTED_LABEL",
        "INTERACT_NPC",
        "npc:sess-1:0",
        null,
        null,
        BigDecimal.valueOf(0.9));
  }

  @Test
  void updateWithActionLabelsReplacesRowsAndRebuildsChecksum() {
    when(curationRepository.save(any(RecordingCuration.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    UpdateCurationRequest request =
        new UpdateCurationRequest(null, null, List.of(label(5, 0), label(7, 1)));
    service.update(draft.getId(), request, "alice");

    // Old labels deleted, exactly the new ones saved.
    verify(actionRepository).deleteByCurationId(draft.getId());
    ArgumentCaptor<CuratedAction> saved = ArgumentCaptor.forClass(CuratedAction.class);
    verify(actionRepository, org.mockito.Mockito.times(2)).save(saved.capture());
    assertEquals(5L, saved.getAllValues().get(0).getTick());
    assertEquals(7L, saved.getAllValues().get(1).getTick());

    assertNotEquals(
        "sha256:old",
        draft.getContentChecksum(),
        "content checksum must be recomputed after replacement");
  }

  @Test
  void updateNeverIgnoresSuppliedActionLabels() {
    when(curationRepository.save(any(RecordingCuration.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    UpdateCurationRequest request = new UpdateCurationRequest(null, null, List.of(label(3, 0)));
    service.update(draft.getId(), request, "alice");

    verify(actionRepository).deleteByCurationId(draft.getId());
    verify(actionRepository).save(any(CuratedAction.class));
  }

  @Test
  void foreignOwnerGetsUniform404() {
    UpdateCurationRequest request = new UpdateCurationRequest(null, null, List.of(label(3, 0)));

    ReplayException e =
        assertThrows(
            ReplayException.class, () -> service.update(draft.getId(), request, "mallory"));
    assertEquals(org.springframework.http.HttpStatus.NOT_FOUND, e.getHttpStatus());

    ReplayException pe =
        assertThrows(ReplayException.class, () -> service.publish(draft.getId(), "mallory"));
    assertEquals(org.springframework.http.HttpStatus.NOT_FOUND, pe.getHttpStatus());
  }

  @Test
  void publishedCurationsAreImmutable() {
    draft.setState(CurationState.PUBLISHED);
    UpdateCurationRequest request = new UpdateCurationRequest(null, null, List.of(label(3, 0)));

    assertThrows(ReplayException.class, () -> service.update(draft.getId(), request, "alice"));
    verify(actionRepository, never()).deleteByCurationId(any());
  }

  @Test
  void outOfRangeTickIsRejectedBeforeAnyWrite() {
    UpdateCurationRequest request =
        new UpdateCurationRequest(
            null,
            null,
            List.of(
                new CuratedActionDto(
                    -1, 0, "ACCEPTED_LABEL", "WAIT_UNTIL_TICK", null, null, null, null)));

    assertThrows(ReplayException.class, () -> service.update(draft.getId(), request, "alice"));
    verify(actionRepository, never()).save(any());
    verify(actionRepository, never()).deleteByCurationId(any());
  }

  @Test
  void duplicateTickSequenceIsRejected() {
    UpdateCurationRequest request =
        new UpdateCurationRequest(null, null, List.of(label(5, 0), label(5, 0)));

    assertThrows(
        ReplayException.class,
        () -> service.update(draft.getId(), request, "alice"),
        "(tick, sequence) must be unique within the curation");
    verify(actionRepository, never()).save(any());
  }
}
