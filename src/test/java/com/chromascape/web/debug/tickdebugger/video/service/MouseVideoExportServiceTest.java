package com.chromascape.web.debug.tickdebugger.video.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.chromascape.web.debug.tickdebugger.video.MouseVideoProperties;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoAnalysisRepository;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoArtifactRef;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoArtifactRefRepository;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoExport;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoExportRepository;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoFramePreviewRepository;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoReviewRevisionRepository;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoSource;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoSourceRepository;
import com.chromascape.web.debug.tickdebugger.video.store.MouseVideoByteStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
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
class MouseVideoExportServiceTest {

  @TempDir Path root;

  @Mock MouseVideoSourceRepository sources;
  @Mock MouseVideoAnalysisRepository analyses;
  @Mock MouseVideoArtifactRefRepository artifacts;
  @Mock MouseVideoReviewRevisionRepository reviews;
  @Mock MouseVideoExportRepository exports;
  @Mock MouseVideoFramePreviewRepository previews;

  private MouseVideoExportService service;
  private final UUID sourceId = UUID.randomUUID();
  private MouseVideoSource source;

  @BeforeEach
  void setUp() throws IOException {
    MouseVideoProperties properties = new MouseVideoProperties();
    properties.setDirectory(root.toString());
    MouseVideoByteStore byteStore = new MouseVideoByteStore(properties);
    ObjectMapper mapper = new ObjectMapper();

    source = new MouseVideoSource("owner-1", MouseVideoSource.KIND_UPLOAD);
    ReflectionTestUtils.setField(source, "id", sourceId);
    source.bindOriginal("sha256:" + "a".repeat(64), 1234L, "video/mp4");

    when(sources.findByIdAndOwner(sourceId, "owner-1")).thenReturn(Optional.of(source));
    when(analyses.findBySourceIdAndOwnerOrderByCreatedAtDesc(any(), any())).thenReturn(List.of());
    when(artifacts.findBySourceIdAndAnalysisIdIsNull(any())).thenReturn(List.of());
    when(previews.findByAnalysisIdOrderByFrameOrdinalAsc(any())).thenReturn(List.of());
    when(reviews.findBySourceIdOrderByCreatedAtAsc(any())).thenReturn(List.of());
    when(exports.findBySourceIdAndRequestDigest(any(), any())).thenReturn(Optional.empty());
    when(exports.saveAndFlush(any(MouseVideoExport.class)))
        .thenAnswer(
            invocation -> {
              MouseVideoExport snapshot = invocation.getArgument(0);
              ReflectionTestUtils.setField(snapshot, "id", UUID.randomUUID());
              return snapshot;
            });
    when(artifacts.save(any(MouseVideoArtifactRef.class))).thenAnswer(inv -> inv.getArgument(0));

    service =
        new MouseVideoExportService(
            sources, analyses, artifacts, reviews, exports, previews, byteStore, mapper);
  }

  @Test
  void manifestAlwaysDisclaimsNativeCapabilities() {
    Map<String, Object> manifest = service.describe(sourceId, "owner-1");
    assertThat(manifest.get("contract")).isEqualTo(MouseVideoExportService.CONTRACT);
    @SuppressWarnings("unchecked")
    Map<String, Object> capabilities = (Map<String, Object>) manifest.get("capabilities");
    assertThat(capabilities)
        .containsEntry("nativeTelemetry", false)
        .containsEntry("exactTicksAvailable", false)
        .containsEntry("controlAllowed", false)
        .containsEntry("trainingEligible", false);
  }

  @Test
  void createIsRepeatableAndReturnsExistingSnapshot() throws Exception {
    MouseVideoExportService.ExportBundle first =
        service.create("owner-1", sourceId, UUID.randomUUID().toString(), null, null);
    assertThat(first.created()).isTrue();
    assertThat(first.snapshot().getManifestChecksum()).startsWith("sha256:");
    assertThat(first.json()).contains("\"contract\":\"mouse-video-evidence/v1\"");

    when(exports.findBySourceIdAndRequestDigest(any(), any()))
        .thenReturn(Optional.of(first.snapshot()));
    MouseVideoExportService.ExportBundle retry =
        service.create("owner-1", sourceId, UUID.randomUUID().toString(), null, null);
    assertThat(retry.created()).isFalse();
    assertThat(retry.snapshot().getId()).isEqualTo(first.snapshot().getId());
  }
}
