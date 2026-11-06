package com.chromascape.web.debug.tickdebugger.video.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chromascape.web.debug.tickdebugger.video.MouseVideoException;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoAnalysisRepository;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoReviewRevision;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoReviewRevisionRepository;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoSource;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoSourceRepository;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MouseVideoReviewServiceTest {

  @Mock MouseVideoSourceRepository sources;
  @Mock MouseVideoAnalysisRepository analyses;
  @Mock MouseVideoReviewRevisionRepository revisions;
  @Mock MouseVideoFrameIndexService frameIndex;
  @Mock MouseVideoPreviewService previews;

  private MouseVideoReviewService service;
  private final UUID sourceId = UUID.randomUUID();
  private MouseVideoSource source;

  @BeforeEach
  void setUp() {
    service = new MouseVideoReviewService(sources, analyses, revisions, frameIndex, previews);
    source = new MouseVideoSource("owner-1", MouseVideoSource.KIND_UPLOAD);
    ReflectionTestUtils.setField(source, "id", sourceId);
    when(sources.findForUpdateByIdAndOwner(sourceId, "owner-1")).thenReturn(Optional.of(source));
    when(sources.findByIdAndOwner(sourceId, "owner-1")).thenReturn(Optional.of(source));
    when(revisions.findBySourceIdAndRequestId(any(), any())).thenReturn(Optional.empty());
    when(revisions.findFirstBySourceIdOrderByCreatedAtDesc(any())).thenReturn(Optional.empty());
    when(revisions.save(any(MouseVideoReviewRevision.class)))
        .thenAnswer(
            invocation -> {
              MouseVideoReviewRevision revision = invocation.getArgument(0);
              if (revision.getId() == null) {
                ReflectionTestUtils.setField(revision, "id", UUID.randomUUID());
              }
              return revision;
            });
    when(sources.save(any(MouseVideoSource.class))).thenAnswer(inv -> inv.getArgument(0));
  }

  private UUID geometricAnalysis() {
    UUID id = UUID.randomUUID();
    var analysis =
        new com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoAnalysis(
            sourceId, "owner-1", "original", "visual/v1", null, "config", "tools");
    ReflectionTestUtils.setField(analysis, "id", id);
    analysis.complete("coverage", "manifest");
    when(analyses.findByIdAndOwner(id, "owner-1")).thenReturn(Optional.of(analysis));
    when(frameIndex.bounds("owner-1", sourceId, id))
        .thenReturn(
            new com.chromascape.web.debug.tickdebugger.video.MouseVideoCorrectionContract.Bounds(
                1, 3840, 2160));
    when(frameIndex.requireIndex("owner-1", sourceId, id))
        .thenReturn(
            new MouseVideoFrameIndexService.FrozenFrameIndex(
                "index", "frame-index/v2", true, "1/30", "1", "30", List.of()));
    when(previews.requireFrameGeometry("owner-1", sourceId, id, 0, "index"))
        .thenReturn(new MouseVideoPreviewService.FrameGeometry(160, 96));
    return id;
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {"CURSOR_VISIBILITY", "TARGET_REGION", "CAMERA_ACTIVITY"})
  void geometryOutsideActualFrameIsRejectedEvenInsideConfiguredMaximum(String kind) {
    UUID id = geometricAnalysis();
    var entry =
        JsonNodeFactory.instance
            .objectNode()
            .put("kind", kind)
            .put("frameOrdinal", 0)
            .put("x", 159)
            .put("y", 0);
    if (kind.equals("CURSOR_VISIBILITY")) entry.put("visibility", "VISIBLE").put("x", 160);
    else {
      entry.put("width", 2).put("height", 1);
      if (kind.equals("CAMERA_ACTIVITY")) entry.put("activity", "VIEWPORT_MOTION");
    }
    assertThatThrownBy(
            () ->
                service.create(
                    "owner-1",
                    "owner-1",
                    sourceId,
                    UUID.randomUUID().toString(),
                    id,
                    0,
                    "outside source",
                    "INSPECTION_ONLY",
                    JsonNodeFactory.instance.arrayNode().add(entry)))
        .isInstanceOf(MouseVideoException.class)
        .extracting(e -> ((MouseVideoException) e).getCode())
        .isEqualTo(MouseVideoException.CODE_INVALID_REQUEST);
    assertThat(source.getReviewRevision()).isZero();
    verify(revisions, never()).save(any());
  }

  @Test
  void storesVersionedProvenanceAndRetriesWithoutAdvancingAgain() {
    UUID id = geometricAnalysis();
    var entry =
        JsonNodeFactory.instance
            .objectNode()
            .put("kind", "CURSOR_VISIBILITY")
            .put("frameOrdinal", 0)
            .put("visibility", "HIDDEN")
            .put("unknownReason", "not visible")
            .put("provenance", "BLIND_UNVERIFIED");
    var corrections = JsonNodeFactory.instance.arrayNode().add(entry);
    var created =
        service.create(
            "owner-1",
            "owner-1",
            sourceId,
            "provenance-request",
            id,
            0,
            "blind unverified",
            "INSPECTION_ONLY",
            corrections);
    assertThat(created.get("contract")).isEqualTo("corrections/v2");
    assertThat(created.get("corrections").toString()).contains("BLIND_UNVERIFIED");
    var saved = org.mockito.ArgumentCaptor.forClass(MouseVideoReviewRevision.class);
    verify(revisions).save(saved.capture());
    when(revisions.findBySourceIdAndRequestId(sourceId, "provenance-request"))
        .thenReturn(Optional.of(saved.getValue()));
    var retried =
        service.create(
            "owner-1",
            "owner-1",
            sourceId,
            "provenance-request",
            id,
            0,
            "blind unverified",
            "INSPECTION_ONLY",
            corrections);
    assertThat(retried.get("id")).isEqualTo(created.get("id"));
    assertThat(source.getReviewRevision()).isEqualTo(1);
    entry.put("provenance", "DETECTOR_ASSISTED");
    assertThatThrownBy(
            () ->
                service.create(
                    "owner-1",
                    "owner-1",
                    sourceId,
                    "provenance-request",
                    id,
                    0,
                    "blind unverified",
                    "INSPECTION_ONLY",
                    corrections))
        .isInstanceOf(MouseVideoException.class)
        .hasMessageContaining("different review content");
  }

  @Test
  void quarterTurnUsesPortraitPreviewHeightRatherThanTheLandscapeIntakeLimit() {
    UUID id = geometricAnalysis();
    when(previews.requireFrameGeometry("owner-1", sourceId, id, 0, "index"))
        .thenReturn(new MouseVideoPreviewService.FrameGeometry(2160, 3840));
    var entry =
        JsonNodeFactory.instance
            .objectNode()
            .put("kind", "CURSOR_VISIBILITY")
            .put("frameOrdinal", 0)
            .put("visibility", "VISIBLE")
            .put("x", 2159)
            .put("y", 3839);
    service.create(
        "owner-1",
        "owner-1",
        sourceId,
        "portrait",
        id,
        0,
        "rotated edge",
        "INSPECTION_ONLY",
        JsonNodeFactory.instance.arrayNode().add(entry));
    assertThat(source.getReviewRevision()).isEqualTo(1);
  }

  @Test
  void validEdgePointIsIdempotentEvenIfItsPreviewLaterBecomesUnavailable() {
    UUID id = geometricAnalysis();
    var entry =
        JsonNodeFactory.instance
            .objectNode()
            .put("kind", "CURSOR_VISIBILITY")
            .put("frameOrdinal", 0)
            .put("visibility", "VISIBLE")
            .put("x", 159)
            .put("y", 95);
    var corrections = JsonNodeFactory.instance.arrayNode().add(entry);
    service.create(
        "owner-1",
        "owner-1",
        sourceId,
        "geometry-retry",
        id,
        0,
        "edge",
        "INSPECTION_ONLY",
        corrections);
    var captured = org.mockito.ArgumentCaptor.forClass(MouseVideoReviewRevision.class);
    verify(revisions).save(captured.capture());
    when(revisions.findBySourceIdAndRequestId(sourceId, "geometry-retry"))
        .thenReturn(Optional.of(captured.getValue()));
    when(previews.requireFrameGeometry("owner-1", sourceId, id, 0, "index"))
        .thenThrow(new MouseVideoException(MouseVideoException.CODE_SOURCE_UNAVAILABLE, "missing"));
    org.mockito.Mockito.clearInvocations(previews);
    var retry =
        service.create(
            "owner-1",
            "owner-1",
            sourceId,
            "geometry-retry",
            id,
            0,
            "edge",
            "INSPECTION_ONLY",
            corrections);
    assertThat(retry.get("id")).isEqualTo(captured.getValue().getId().toString());
    assertThat(source.getReviewRevision()).isEqualTo(1);
    org.mockito.Mockito.verifyNoInteractions(previews);
  }

  @Test
  void newVisiblePointNeedsExactEvidenceButUnknownDoesNot() {
    UUID id = geometricAnalysis();
    when(previews.requireFrameGeometry("owner-1", sourceId, id, 0, "index"))
        .thenThrow(new MouseVideoException(MouseVideoException.CODE_SOURCE_UNAVAILABLE, "missing"));
    var entry =
        JsonNodeFactory.instance
            .objectNode()
            .put("kind", "CURSOR_VISIBILITY")
            .put("frameOrdinal", 0)
            .put("visibility", "VISIBLE")
            .put("x", 1)
            .put("y", 1);
    assertThatThrownBy(
            () ->
                service.create(
                    "owner-1",
                    "owner-1",
                    sourceId,
                    "no-preview",
                    id,
                    0,
                    "point",
                    "INSPECTION_ONLY",
                    JsonNodeFactory.instance.arrayNode().add(entry)))
        .isInstanceOf(MouseVideoException.class);
    entry.remove("x");
    entry.remove("y");
    entry.put("visibility", "HIDDEN").put("unknownReason", "synthetic occlusion");
    org.mockito.Mockito.clearInvocations(previews);
    service.create(
        "owner-1",
        "owner-1",
        sourceId,
        "unknown",
        id,
        0,
        "unknown",
        "INSPECTION_ONLY",
        JsonNodeFactory.instance.arrayNode().add(entry));
    org.mockito.Mockito.verifyNoInteractions(previews);
  }

  @Test
  void createsAppendOnlyRevisionAndAdvancesSourceRevision() {
    Map<String, Object> created =
        service.create(
            "owner-1",
            "owner-1",
            sourceId,
            UUID.randomUUID().toString(),
            null,
            0L,
            "correcting a label",
            "INSPECTION_ONLY",
            JsonNodeFactory.instance.arrayNode());
    assertThat(created.get("actor")).isEqualTo("owner-1");
    assertThat(created.get("expectedBaseRevision")).isEqualTo(0L);
    assertThat(source.getReviewRevision()).isEqualTo(1L);
    assertThat(source.getOriginReview()).isEqualTo(MouseVideoSource.REVIEW_REVIEWED);
  }

  @Test
  void rejectsCorrectionAgainstStaleBaseRevision() {
    source.advanceReviewRevision();
    assertThatThrownBy(
            () ->
                service.create(
                    "owner-1",
                    "owner-1",
                    sourceId,
                    UUID.randomUUID().toString(),
                    null,
                    0L,
                    "stale",
                    "INSPECTION_ONLY",
                    JsonNodeFactory.instance.arrayNode()))
        .isInstanceOf(MouseVideoException.class)
        .extracting(error -> ((MouseVideoException) error).getCode())
        .isEqualTo(MouseVideoException.CODE_CONFLICT);
  }

  @Test
  void identicalRetryReturnsTheExistingRevision() {
    MouseVideoReviewRevision existing =
        new MouseVideoReviewRevision(
            sourceId,
            null,
            null,
            "owner-1",
            "retry reason",
            0L,
            "INSPECTION_ONLY",
            JsonNodeFactory.instance.arrayNode());
    UUID existingId = UUID.randomUUID();
    ReflectionTestUtils.setField(existing, "id", existingId);
    existing.setRequestId("req-1");
    when(revisions.findBySourceIdAndRequestId(sourceId, "req-1")).thenReturn(Optional.of(existing));

    Map<String, Object> created =
        service.create(
            "owner-1",
            "owner-1",
            sourceId,
            "req-1",
            null,
            0L,
            "retry reason",
            "INSPECTION_ONLY",
            JsonNodeFactory.instance.arrayNode());

    assertThat(created.get("id")).isEqualTo(existingId.toString());
    assertThat(created.get("reason")).isEqualTo("retry reason");
    verify(revisions, never()).save(any(MouseVideoReviewRevision.class));
  }

  @Test
  void changedBodyReusingARequestIdConflicts() {
    MouseVideoReviewRevision existing =
        new MouseVideoReviewRevision(
            sourceId,
            null,
            null,
            "owner-1",
            "original reason",
            0L,
            "INSPECTION_ONLY",
            JsonNodeFactory.instance.arrayNode());
    ReflectionTestUtils.setField(existing, "id", UUID.randomUUID());
    existing.setRequestId("req-1");
    existing.setRequestDigest("sha256:" + "c".repeat(64));
    when(revisions.findBySourceIdAndRequestId(sourceId, "req-1")).thenReturn(Optional.of(existing));

    assertThatThrownBy(
            () ->
                service.create(
                    "owner-1",
                    "owner-1",
                    sourceId,
                    "req-1",
                    null,
                    0L,
                    "a different reason",
                    "INSPECTION_ONLY",
                    JsonNodeFactory.instance.arrayNode()))
        .isInstanceOf(MouseVideoException.class)
        .extracting(error -> ((MouseVideoException) error).getCode())
        .isEqualTo(MouseVideoException.CODE_CONFLICT);
  }

  @Test
  void historicalRowIsComparedThroughItsStoredProjection() {
    MouseVideoReviewRevision historical =
        new MouseVideoReviewRevision(
            sourceId,
            null,
            null,
            "owner-1",
            "same reason",
            0L,
            "INSPECTION_ONLY",
            JsonNodeFactory.instance.arrayNode());
    ReflectionTestUtils.setField(historical, "id", UUID.randomUUID());
    historical.setRequestId("req-historical");
    when(revisions.findBySourceIdAndRequestId(sourceId, "req-historical"))
        .thenReturn(Optional.of(historical));

    Map<String, Object> created =
        service.create(
            "owner-1",
            "owner-1",
            sourceId,
            "req-historical",
            null,
            0L,
            "same reason",
            "INSPECTION_ONLY",
            JsonNodeFactory.instance.arrayNode());

    assertThat(created.get("id")).isEqualTo(historical.getId().toString());
    assertThat(created.get("contract")).isEqualTo("corrections/v0");
    verify(revisions, never()).save(any(MouseVideoReviewRevision.class));
  }

  @Test
  void historicalRowWithAChangedProjectionConflicts() {
    MouseVideoReviewRevision historical =
        new MouseVideoReviewRevision(
            sourceId,
            null,
            null,
            "owner-1",
            "same reason",
            0L,
            "INSPECTION_ONLY",
            JsonNodeFactory.instance.arrayNode());
    ReflectionTestUtils.setField(historical, "id", UUID.randomUUID());
    historical.setRequestId("req-historical");
    when(revisions.findBySourceIdAndRequestId(sourceId, "req-historical"))
        .thenReturn(Optional.of(historical));

    assertThatThrownBy(
            () ->
                service.create(
                    "owner-1",
                    "owner-1",
                    sourceId,
                    "req-historical",
                    null,
                    0L,
                    "changed reason",
                    "INSPECTION_ONLY",
                    JsonNodeFactory.instance.arrayNode()))
        .isInstanceOf(MouseVideoException.class)
        .extracting(error -> ((MouseVideoException) error).getCode())
        .isEqualTo(MouseVideoException.CODE_CONFLICT);
  }

  @Test
  void predecessorContentIsNeverRewritten() {
    MouseVideoReviewRevision predecessor =
        new MouseVideoReviewRevision(
            sourceId,
            null,
            null,
            "owner-1",
            "first",
            0L,
            "INSPECTION_ONLY",
            JsonNodeFactory.instance.arrayNode());
    UUID predecessorId = UUID.randomUUID();
    ReflectionTestUtils.setField(predecessor, "id", predecessorId);
    when(revisions.findFirstBySourceIdOrderByCreatedAtDesc(sourceId))
        .thenReturn(Optional.of(predecessor));

    Map<String, Object> created =
        service.create(
            "owner-1",
            "owner-1",
            sourceId,
            UUID.randomUUID().toString(),
            null,
            0L,
            "second",
            "INSPECTION_ONLY",
            JsonNodeFactory.instance.arrayNode());

    assertThat(created.get("predecessorId")).isEqualTo(predecessorId.toString());
    assertThat(predecessor.getState()).isEqualTo(MouseVideoReviewRevision.STATE_REVIEWED);
    verify(revisions, never()).save(predecessor);
  }

  @Test
  void supersededStateIsDerivedFromSuccessorLineage() {
    MouseVideoReviewRevision first =
        new MouseVideoReviewRevision(
            sourceId,
            null,
            null,
            "owner-1",
            "first",
            0L,
            "INSPECTION_ONLY",
            JsonNodeFactory.instance.arrayNode());
    UUID firstId = UUID.randomUUID();
    ReflectionTestUtils.setField(first, "id", firstId);
    MouseVideoReviewRevision second =
        new MouseVideoReviewRevision(
            sourceId,
            null,
            firstId,
            "owner-1",
            "second",
            1L,
            "INSPECTION_ONLY",
            JsonNodeFactory.instance.arrayNode());
    UUID secondId = UUID.randomUUID();
    ReflectionTestUtils.setField(second, "id", secondId);
    when(revisions.findByIdAndSourceId(firstId, sourceId)).thenReturn(Optional.of(first));
    when(revisions.findBySourceIdOrderByCreatedAtAsc(sourceId)).thenReturn(List.of(first, second));

    Map<String, Object> view = service.get("owner-1", sourceId, firstId);

    assertThat(view.get("state")).isEqualTo(MouseVideoReviewRevision.STATE_SUPERSEDED);
    assertThat(view.get("supersededBy")).isEqualTo(secondId.toString());
    assertThat(first.getState()).isEqualTo(MouseVideoReviewRevision.STATE_REVIEWED);
  }

  @Test
  void invalidCorrectionsAreRejectedBeforeAnyWrite() {
    var corrections = JsonNodeFactory.instance.arrayNode();
    var unknownKind = JsonNodeFactory.instance.objectNode();
    unknownKind.put("kind", "MOVE_CURSOR_TO");
    unknownKind.put("frameOrdinal", 1);
    corrections.add(unknownKind);

    assertThatThrownBy(
            () ->
                service.create(
                    "owner-1",
                    "owner-1",
                    sourceId,
                    UUID.randomUUID().toString(),
                    null,
                    0L,
                    "invalid",
                    "INSPECTION_ONLY",
                    corrections))
        .isInstanceOf(MouseVideoException.class)
        .extracting(error -> ((MouseVideoException) error).getCode())
        .isEqualTo(MouseVideoException.CODE_INVALID_REQUEST);

    verify(revisions, never()).save(any(MouseVideoReviewRevision.class));
    assertThat(source.getReviewRevision()).isZero();
  }
}
