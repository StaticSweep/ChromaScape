package com.chromascape.web.debug.tickdebugger.video.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chromascape.web.debug.tickdebugger.video.MouseVideoException;
import com.chromascape.web.debug.tickdebugger.video.MouseVideoProperties;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoAnalysis;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoAnalysisRepository;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoArtifactRef;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoArtifactRefRepository;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoJobRepository;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoSource;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoSourceRepository;
import com.chromascape.web.debug.tickdebugger.video.store.MouseVideoByteStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MouseVideoCatalogServiceTest {

  @TempDir Path root;

  @Mock MouseVideoSourceRepository sources;
  @Mock MouseVideoJobRepository jobs;
  @Mock MouseVideoAnalysisRepository analyses;
  @Mock MouseVideoArtifactRefRepository artifacts;
  @Mock MouseVideoProbeWorker probeWorker;
  @Mock MouseVideoDerivativeWorker derivativeWorker;

  private MouseVideoCatalogService service;
  private MouseVideoByteStore byteStore;
  private final UUID sourceId = UUID.randomUUID();
  private MouseVideoSource source;

  @BeforeEach
  void setUp() throws IOException {
    MouseVideoProperties properties = new MouseVideoProperties();
    properties.setDirectory(root.toString());
    byteStore = new MouseVideoByteStore(properties);
    source = new MouseVideoSource("owner-1", MouseVideoSource.KIND_UPLOAD);
    ReflectionTestUtils.setField(source, "id", sourceId);
    when(sources.findByIdAndOwner(sourceId, "owner-1")).thenReturn(Optional.of(source));
    service =
        new MouseVideoCatalogService(
            properties,
            sources,
            jobs,
            analyses,
            artifacts,
            byteStore,
            probeWorker,
            derivativeWorker);
  }

  private String publish(String content) throws IOException {
    byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
    MouseVideoByteStore.StagedArtifact staged =
        byteStore.stage(new java.io.ByteArrayInputStream(bytes), bytes.length + 1L);
    byteStore.publish(staged);
    return staged.checksum();
  }

  private MouseVideoArtifactRef ref(String role, String checksum, String mediaType, String state) {
    MouseVideoArtifactRef ref =
        new MouseVideoArtifactRef("owner-1", sourceId, null, role, checksum, 1L, mediaType);
    if (MouseVideoArtifactRef.STATE_FINALIZED.equals(state)) {
      ref.finalizeRow();
    } else {
      ref.quarantine();
    }
    return ref;
  }

  @Test
  void quarantinedOriginalIsNeverServedInline() throws IOException {
    String checksum = publish("bytes");
    when(artifacts.findBySourceIdAndRoleAndAnalysisIdIsNullAndState(
            sourceId, MouseVideoArtifactRef.ROLE_ORIGINAL, MouseVideoArtifactRef.STATE_FINALIZED))
        .thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.media("owner-1", sourceId, "ORIGINAL"))
        .isInstanceOf(MouseVideoException.class)
        .extracting(error -> ((MouseVideoException) error).getCode())
        .isEqualTo(MouseVideoException.CODE_SOURCE_UNAVAILABLE);
  }

  @Test
  void qualifiedOriginalServesInlineVerifiedType() throws IOException {
    String checksum = publish("bytes");
    source.qualifyOriginal("video/mp4");
    when(artifacts.findBySourceIdAndRoleAndAnalysisIdIsNullAndState(
            sourceId, MouseVideoArtifactRef.ROLE_ORIGINAL, MouseVideoArtifactRef.STATE_FINALIZED))
        .thenReturn(
            Optional.of(
                ref(
                    MouseVideoArtifactRef.ROLE_ORIGINAL,
                    checksum,
                    "video/mp4",
                    MouseVideoArtifactRef.STATE_FINALIZED)));

    MouseVideoCatalogService.MediaHandle handle = service.media("owner-1", sourceId, "ORIGINAL");
    assertThat(handle.mediaType()).isEqualTo("video/mp4");
    assertThat(handle.inline()).isTrue();
  }

  @Test
  void finalizedButUnqualifiedOriginalIsUnavailable() throws IOException {
    String checksum = publish("bytes");
    when(artifacts.findBySourceIdAndRoleAndAnalysisIdIsNullAndState(
            sourceId, MouseVideoArtifactRef.ROLE_ORIGINAL, MouseVideoArtifactRef.STATE_FINALIZED))
        .thenReturn(
            Optional.of(
                ref(
                    MouseVideoArtifactRef.ROLE_ORIGINAL,
                    checksum,
                    "video/mp4",
                    MouseVideoArtifactRef.STATE_FINALIZED)));

    assertThatThrownBy(() -> service.media("owner-1", sourceId, "ORIGINAL"))
        .isInstanceOf(MouseVideoException.class)
        .extracting(error -> ((MouseVideoException) error).getCode())
        .isEqualTo(MouseVideoException.CODE_SOURCE_UNAVAILABLE);
  }

  @Test
  void thumbnailWithActiveContentTypeIsRefused() throws IOException {
    String checksum = publish("bytes");
    MouseVideoAnalysis analysis = completedAnalysis(UUID.randomUUID());
    when(analyses.findFirstBySourceIdAndOwnerAndStatusOrderByCompletedAtDescCreatedAtDescIdDesc(
            sourceId, "owner-1", MouseVideoAnalysis.STATUS_COMPLETE))
        .thenReturn(Optional.of(analysis));
    when(artifacts.findByAnalysisIdAndRoleAndState(
            analysis.getId(),
            MouseVideoArtifactRef.ROLE_THUMBNAIL,
            MouseVideoArtifactRef.STATE_FINALIZED))
        .thenReturn(
            Optional.of(
                analysisRef(
                    analysis.getId(),
                    MouseVideoArtifactRef.ROLE_THUMBNAIL,
                    checksum,
                    "text/html")));

    assertThatThrownBy(() -> service.media("owner-1", sourceId, "THUMBNAIL"))
        .isInstanceOf(MouseVideoException.class)
        .extracting(error -> ((MouseVideoException) error).getCode())
        .isEqualTo(MouseVideoException.CODE_SOURCE_UNAVAILABLE);

    verify(artifacts)
        .findByAnalysisIdAndRoleAndState(
            analysis.getId(),
            MouseVideoArtifactRef.ROLE_THUMBNAIL,
            MouseVideoArtifactRef.STATE_FINALIZED);
  }

  @Test
  void foreignOwnerCannotResolveMedia() {
    when(sources.findByIdAndOwner(eq(sourceId), eq("other"))).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.media("other", sourceId, "ORIGINAL"))
        .isInstanceOf(MouseVideoException.class)
        .extracting(error -> ((MouseVideoException) error).getCode())
        .isEqualTo(MouseVideoException.CODE_NOT_FOUND);
  }

  @Test
  void derivativeMediaResolvesAgainstTheSelectedCompletedAnalysis() throws IOException {
    String checksum = publish("proxy-bytes");
    MouseVideoAnalysis analysis = completedAnalysis(UUID.randomUUID());
    when(analyses.findFirstBySourceIdAndOwnerAndStatusOrderByCompletedAtDescCreatedAtDescIdDesc(
            sourceId, "owner-1", MouseVideoAnalysis.STATUS_COMPLETE))
        .thenReturn(Optional.of(analysis));
    when(artifacts.findByAnalysisIdAndRoleAndState(
            analysis.getId(),
            MouseVideoArtifactRef.ROLE_PROXY,
            MouseVideoArtifactRef.STATE_FINALIZED))
        .thenReturn(
            Optional.of(
                analysisRef(
                    analysis.getId(), MouseVideoArtifactRef.ROLE_PROXY, checksum, "video/mp4")));

    MouseVideoCatalogService.MediaHandle handle = service.media("owner-1", sourceId, "PROXY");

    assertThat(handle.mediaType()).isEqualTo("video/mp4");
    assertThat(handle.inline()).isTrue();
    assertThat(handle.sizeBytes()).isEqualTo(1L);
  }

  @Test
  void explicitAnalysisIdPinsDerivativeMedia() throws IOException {
    String checksum = publish("thumbnail-bytes");
    UUID requestedAnalysis = UUID.randomUUID();
    MouseVideoAnalysis analysis = completedAnalysis(requestedAnalysis);
    when(analyses.findByIdAndOwner(requestedAnalysis, "owner-1")).thenReturn(Optional.of(analysis));
    when(artifacts.findByAnalysisIdAndRoleAndState(
            requestedAnalysis,
            MouseVideoArtifactRef.ROLE_THUMBNAIL,
            MouseVideoArtifactRef.STATE_FINALIZED))
        .thenReturn(
            Optional.of(
                analysisRef(
                    requestedAnalysis,
                    MouseVideoArtifactRef.ROLE_THUMBNAIL,
                    checksum,
                    "image/png")));

    MouseVideoCatalogService.MediaHandle handle =
        service.media("owner-1", sourceId, "THUMBNAIL", requestedAnalysis);

    assertThat(handle.mediaType()).isEqualTo("image/png");
  }

  @Test
  void derivativeMediaRefusesAnIncompleteExplicitAnalysis() {
    UUID requestedAnalysis = UUID.randomUUID();
    MouseVideoAnalysis analysis = completedAnalysis(requestedAnalysis);
    analysis.setStatus(MouseVideoAnalysis.STATUS_PENDING);
    when(analyses.findByIdAndOwner(requestedAnalysis, "owner-1")).thenReturn(Optional.of(analysis));

    assertThatThrownBy(() -> service.media("owner-1", sourceId, "PROXY", requestedAnalysis))
        .isInstanceOf(MouseVideoException.class)
        .extracting(error -> ((MouseVideoException) error).getCode())
        .isEqualTo(MouseVideoException.CODE_SOURCE_UNAVAILABLE);
  }

  @Test
  void derivativeMediaRefusesAnalysisFromAnotherSource() {
    UUID requestedAnalysis = UUID.randomUUID();
    MouseVideoAnalysis analysis = completedAnalysis(requestedAnalysis);
    ReflectionTestUtils.setField(analysis, "sourceId", UUID.randomUUID());
    when(analyses.findByIdAndOwner(requestedAnalysis, "owner-1")).thenReturn(Optional.of(analysis));

    assertThatThrownBy(() -> service.media("owner-1", sourceId, "PROXY", requestedAnalysis))
        .isInstanceOf(MouseVideoException.class)
        .extracting(error -> ((MouseVideoException) error).getCode())
        .isEqualTo(MouseVideoException.CODE_NOT_FOUND);
  }

  @Test
  void derivativeMediaWithoutACompletedAnalysisIsUnavailable() {
    when(analyses.findFirstBySourceIdAndOwnerAndStatusOrderByCompletedAtDescCreatedAtDescIdDesc(
            sourceId, "owner-1", MouseVideoAnalysis.STATUS_COMPLETE))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.media("owner-1", sourceId, "PROXY"))
        .isInstanceOf(MouseVideoException.class)
        .extracting(error -> ((MouseVideoException) error).getCode())
        .isEqualTo(MouseVideoException.CODE_SOURCE_UNAVAILABLE);
  }

  @Test
  void sourceDetailListsOnlyTheSelectedAnalysisArtifacts() throws IOException {
    String checksum = publish("artifact-bytes");
    MouseVideoAnalysis analysis = completedAnalysis(UUID.randomUUID());
    when(analyses.findFirstBySourceIdAndOwnerAndStatusOrderByCompletedAtDescCreatedAtDescIdDesc(
            sourceId, "owner-1", MouseVideoAnalysis.STATUS_COMPLETE))
        .thenReturn(Optional.of(analysis));
    when(analyses.findBySourceIdAndOwnerOrderByCreatedAtDesc(sourceId, "owner-1"))
        .thenReturn(List.of(analysis));
    when(jobs.findBySourceIdAndOwnerOrderByCreatedAtAsc(sourceId, "owner-1")).thenReturn(List.of());
    when(artifacts.findBySourceIdAndAnalysisIdIsNull(sourceId))
        .thenReturn(
            List.of(
                ref(
                    MouseVideoArtifactRef.ROLE_ORIGINAL,
                    checksum,
                    "video/mp4",
                    MouseVideoArtifactRef.STATE_FINALIZED)));
    when(artifacts.findByAnalysisId(analysis.getId()))
        .thenReturn(
            List.of(
                analysisRef(
                    analysis.getId(), MouseVideoArtifactRef.ROLE_PROXY, checksum, "video/mp4"),
                analysisRef(
                    analysis.getId(),
                    MouseVideoArtifactRef.ROLE_THUMBNAIL,
                    checksum,
                    "image/png")));

    Map<String, Object> body = service.get("owner-1", sourceId);

    assertThat(body.get("selectedAnalysisId")).isEqualTo(analysis.getId().toString());
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> views = (List<Map<String, Object>>) body.get("artifacts");
    assertThat(views).hasSize(3);
    assertThat(views)
        .extracting(view -> view.get("scope"))
        .containsExactly("SOURCE", "ANALYSIS", "ANALYSIS");
    assertThat(views)
        .filteredOn(view -> "ANALYSIS".equals(view.get("scope")))
        .allSatisfy(
            view -> assertThat(view.get("analysisId")).isEqualTo(analysis.getId().toString()));
  }

  private MouseVideoAnalysis completedAnalysis(UUID id) {
    source.bindOriginal("sha256:original", 10L, "video/mp4");
    MouseVideoAnalysis analysis =
        new MouseVideoAnalysis(
            sourceId, "owner-1", "sha256:original", "probe/v1", null, "config", "toolchain");
    ReflectionTestUtils.setField(analysis, "id", id);
    analysis.complete("probe-only", null);
    return analysis;
  }

  private MouseVideoArtifactRef analysisRef(
      UUID analysisId, String role, String checksum, String mediaType) {
    MouseVideoArtifactRef ref =
        new MouseVideoArtifactRef("owner-1", sourceId, analysisId, role, checksum, 1L, mediaType);
    ref.finalizeRow();
    return ref;
  }
}
