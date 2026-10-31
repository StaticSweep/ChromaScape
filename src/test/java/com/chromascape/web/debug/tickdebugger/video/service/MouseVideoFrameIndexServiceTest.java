package com.chromascape.web.debug.tickdebugger.video.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.chromascape.web.debug.tickdebugger.video.MouseVideoCorrectionContract;
import com.chromascape.web.debug.tickdebugger.video.MouseVideoException;
import com.chromascape.web.debug.tickdebugger.video.MouseVideoProperties;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoAnalysis;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoAnalysisRepository;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoArtifactRef;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoArtifactRefRepository;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoSource;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoSourceRepository;
import com.chromascape.web.debug.tickdebugger.video.store.MouseVideoByteStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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

/**
 * Read-API coverage: bounded paging pinned to one immutable artifact, exact PTS transport, owner
 * and analysis binding, and honest reporting when no observation artifact exists.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MouseVideoFrameIndexServiceTest {

  private static final String OWNER = "owner-1";

  @TempDir Path root;

  @Mock MouseVideoSourceRepository sources;
  @Mock MouseVideoAnalysisRepository analyses;
  @Mock MouseVideoArtifactRefRepository artifacts;

  private MouseVideoProperties properties;
  private MouseVideoByteStore byteStore;
  private MouseVideoFrameIndexService service;
  private MouseVideoObservationsService observations;
  private UUID sourceId;
  private UUID analysisId;
  private MouseVideoSource source;
  private MouseVideoAnalysis analysis;

  @BeforeEach
  void setUp() throws Exception {
    properties = new MouseVideoProperties();
    properties.setDirectory(root.resolve("store").toString());
    properties.setMaxObservationPageSize(2);
    byteStore = new MouseVideoByteStore(properties);
    ObjectMapper mapper = new ObjectMapper();
    service =
        new MouseVideoFrameIndexService(
            properties, sources, analyses, artifacts, byteStore, mapper);
    observations =
        new MouseVideoObservationsService(
            properties, sources, analyses, artifacts, byteStore, mapper);
    sourceId = UUID.randomUUID();
    analysisId = UUID.randomUUID();
    source = new MouseVideoSource(OWNER, MouseVideoSource.KIND_UPLOAD);
    ReflectionTestUtils.setField(source, "id", sourceId);
    source.bindOriginal("sha256:" + "a".repeat(64), 1024L, "video/mp4");
    analysis =
        new MouseVideoAnalysis(
            sourceId, OWNER, source.getOriginalChecksum(), "probe/v1", null, "config", "toolchain");
    ReflectionTestUtils.setField(analysis, "id", analysisId);
    analysis.complete("coverage", "manifest");
    when(sources.findByIdAndOwner(sourceId, OWNER)).thenReturn(Optional.of(source));
    when(analyses.findByIdAndOwner(analysisId, OWNER)).thenReturn(Optional.of(analysis));
  }

  @Test
  void pagesTheFrozenIndexWithExactPtsStrings() throws Exception {
    String checksum = publishFrameIndex(frameIndexJson());
    stubArtifact(MouseVideoArtifactRef.ROLE_FRAME_INDEX, checksum, "application/json");

    Map<String, Object> first = service.page(OWNER, sourceId, analysisId, null, 2);

    assertThat(first.get("artifactChecksum")).isEqualTo(checksum);
    assertThat(first.get("frameCount")).isEqualTo(3);
    @SuppressWarnings("unchecked")
    Map<String, Object> timeBase = (Map<String, Object>) first.get("timeBase");
    assertThat(timeBase.get("numerator")).isEqualTo("1");
    assertThat(timeBase.get("denominator")).isEqualTo("1000");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> items = (List<Map<String, Object>>) first.get("items");
    assertThat(items).hasSize(2);
    assertThat(items.get(0).get("ordinal")).isEqualTo(0);
    assertThat(items.get(0).get("pts")).isEqualTo("0");
    assertThat(items.get(1).get("pts")).isEqualTo("9007199254740993");
    String cursor = (String) first.get("nextCursor");
    assertThat(cursor).isNotBlank();

    Map<String, Object> second = service.page(OWNER, sourceId, analysisId, cursor, 2);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rest = (List<Map<String, Object>>) second.get("items");
    assertThat(rest).hasSize(1);
    assertThat(rest.get(0).get("pts")).isEqualTo("18014398509481986");
    assertThat(second.get("nextCursor")).isNull();
  }

  @Test
  void clampsPageSizeToTheConfiguredCap() throws Exception {
    String checksum = publishFrameIndex(frameIndexJson());
    stubArtifact(MouseVideoArtifactRef.ROLE_FRAME_INDEX, checksum, "application/json");

    Map<String, Object> page = service.page(OWNER, sourceId, analysisId, null, 500);

    assertThat(page.get("limit")).isEqualTo(2);
  }

  @Test
  void rejectsACursorMintedForAnotherArtifact() throws Exception {
    String checksum = publishFrameIndex(frameIndexJson());
    stubArtifact(MouseVideoArtifactRef.ROLE_FRAME_INDEX, checksum, "application/json");
    String cursor = (String) service.page(OWNER, sourceId, analysisId, null, 1).get("nextCursor");
    String otherChecksum = "sha256:" + "b".repeat(64);

    assertThatThrownBy(
            () -> service.page(OWNER, sourceId, analysisId, tamper(cursor, otherChecksum), 1))
        .isInstanceOf(MouseVideoException.class)
        .hasMessageContaining("different analysis artifact");
  }

  @Test
  void reportsSourceUnavailableWhenNoIndexIsPublished() {
    when(artifacts.findByAnalysisIdAndRoleAndState(any(), anyString(), anyString()))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.page(OWNER, sourceId, analysisId, null, 10))
        .isInstanceOf(MouseVideoException.class)
        .extracting(error -> ((MouseVideoException) error).getCode())
        .isEqualTo(MouseVideoException.CODE_SOURCE_UNAVAILABLE);
  }

  @Test
  void hidesAnalysisThatBelongsToAnotherOwner() {
    when(analyses.findByIdAndOwner(analysisId, "owner-2")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.page("owner-2", sourceId, analysisId, null, 10))
        .isInstanceOf(MouseVideoException.class)
        .extracting(error -> ((MouseVideoException) error).getCode())
        .isEqualTo(MouseVideoException.CODE_NOT_FOUND);
  }

  @Test
  void reportsCorrectionBoundsFromTheFrozenIndex() throws Exception {
    String checksum = publishFrameIndex(frameIndexJson());
    stubArtifact(MouseVideoArtifactRef.ROLE_FRAME_INDEX, checksum, "application/json");

    MouseVideoCorrectionContract.Bounds bounds = service.bounds(OWNER, sourceId, analysisId);

    assertThat(bounds.frameCount()).isEqualTo(3);
    assertThat(bounds.maxWidth()).isEqualTo(3840);
  }

  @Test
  void observationsReportAnHonestAbsenceWhenNoneArePublished() {
    when(artifacts.findByAnalysisIdAndRoleAndState(any(), anyString(), anyString()))
        .thenReturn(Optional.empty());

    Map<String, Object> body =
        observations.list(OWNER, sourceId, analysisId, null, 10, null, null, null);

    assertThat(body.get("available")).isEqualTo(false);
    @SuppressWarnings("unchecked")
    List<String> unknowns = (List<String>) body.get("unknowns");
    assertThat(unknowns).isNotEmpty();
    assertThat(unknowns.get(0)).contains("no published observation artifact");
    @SuppressWarnings("unchecked")
    List<Object> items = (List<Object>) body.get("items");
    assertThat(items).isEmpty();
  }

  @Test
  void observationsFilterByKindAndPtsWithBoundedPaging() throws Exception {
    String checksum = publishObservations(observationsJson());
    stubArtifact(MouseVideoArtifactRef.ROLE_OBSERVATIONS, checksum, "application/json");

    Map<String, Object> page =
        observations.list(OWNER, sourceId, analysisId, null, 2, "cursor_visibility", null, null);

    assertThat(page.get("available")).isEqualTo(true);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> items = (List<Map<String, Object>>) page.get("items");
    assertThat(items).hasSize(2);
    assertThat(items.get(0).get("kind")).isEqualTo("CURSOR_VISIBILITY");
    assertThat(items.get(0).get("pts")).isEqualTo("0");
    String cursor = (String) page.get("nextCursor");
    assertThat(cursor).isNotBlank();

    Map<String, Object> rest =
        observations.list(OWNER, sourceId, analysisId, cursor, 2, "CURSOR_VISIBILITY", null, null);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> restItems = (List<Map<String, Object>>) rest.get("items");
    assertThat(restItems).hasSize(1);
    assertThat(rest.get("nextCursor")).isNull();
  }

  @Test
  void observationsFilterByPtsRange() throws Exception {
    String checksum = publishObservations(observationsJson());
    stubArtifact(MouseVideoArtifactRef.ROLE_OBSERVATIONS, checksum, "application/json");

    Map<String, Object> page =
        observations.list(OWNER, sourceId, analysisId, null, 10, null, "40", "50");

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> items = (List<Map<String, Object>>) page.get("items");
    assertThat(items).hasSize(1);
    assertThat(items.get(0).get("pts")).isEqualTo("40");
  }

  @Test
  void observationsExposeOnlyTheBoundedCursorEvidenceKeys() throws Exception {
    String checksum = publishObservations(observationsWithEvidenceJson());
    stubArtifact(MouseVideoArtifactRef.ROLE_OBSERVATIONS, checksum, "application/json");

    Map<String, Object> page =
        observations.list(OWNER, sourceId, analysisId, null, 10, null, null, null);

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> items = (List<Map<String, Object>>) page.get("items");
    @SuppressWarnings("unchecked")
    Map<String, Object> evidence = (Map<String, Object>) items.get(0).get("evidence");
    assertThat(evidence)
        .containsEntry("rule", "held")
        .containsEntry("reason", "competing placement")
        .containsEntry("continuation", true)
        .containsEntry("windowed", true)
        .containsEntry("rechecked", true)
        .containsEntry("takeover", false)
        .containsEntry("pass", "backward")
        .containsEntry("score", 0.87d)
        .containsEntry("missedSamples", 2)
        .containsEntry("forwardReason", "below reviewed threshold");
    assertThat(evidence).doesNotContainKeys("gain", "phaseX", "maskPixels", "notAllowed");

    // Other observation kinds keep their evidence internal; only a cursor sample is explained.
    assertThat(items.get(1).get("kind")).isEqualTo("SCENE_CUT");
    assertThat(items.get(1)).doesNotContainKey("evidence");
  }

  @Test
  void observationsRejectAnInvertedPtsRange() throws Exception {
    String checksum = publishObservations(observationsJson());
    stubArtifact(MouseVideoArtifactRef.ROLE_OBSERVATIONS, checksum, "application/json");

    assertThatThrownBy(
            () -> observations.list(OWNER, sourceId, analysisId, null, 10, null, "60", "10"))
        .isInstanceOf(MouseVideoException.class)
        .extracting(error -> ((MouseVideoException) error).getCode())
        .isEqualTo(MouseVideoException.CODE_INVALID_REQUEST);
  }

  @Test
  void incompleteIndexCanBeReadButCannotAuthorizeCorrections() throws Exception {
    String checksum =
        publishFrameIndex(frameIndexJson().replace("\"complete\":true", "\"complete\":false"));
    stubArtifact(MouseVideoArtifactRef.ROLE_FRAME_INDEX, checksum, "application/json");
    assertThat(service.requireIndex(OWNER, sourceId, analysisId).complete()).isFalse();
    assertThatThrownBy(() -> service.bounds(OWNER, sourceId, analysisId))
        .isInstanceOf(MouseVideoException.class);
  }

  @Test
  void streamingReadsRejectTruncatedArtifactsAndFrameCapPlusOne() throws Exception {
    String checksum =
        publishFrameIndex(frameIndexJson().substring(0, frameIndexJson().length() - 1));
    stubArtifact(MouseVideoArtifactRef.ROLE_FRAME_INDEX, checksum, "application/json");
    assertThatThrownBy(() -> service.page(OWNER, sourceId, analysisId, null, 2))
        .isInstanceOf(MouseVideoException.class);
    checksum = publishFrameIndex(frameIndexJson());
    stubArtifact(MouseVideoArtifactRef.ROLE_FRAME_INDEX, checksum, "application/json");
    properties.setMaxIndexedFrames(2);
    assertThatThrownBy(() -> service.page(OWNER, sourceId, analysisId, null, 2))
        .isInstanceOf(MouseVideoException.class);
    String observationChecksum = publishObservations(observationsJson() + " trailing");
    stubArtifact(MouseVideoArtifactRef.ROLE_OBSERVATIONS, observationChecksum, "application/json");
    assertThatThrownBy(
            () -> observations.list(OWNER, sourceId, analysisId, null, 2, null, null, null))
        .isInstanceOf(MouseVideoException.class);
  }

  @Test
  void concurrentLargeObservationReadsFitInAFixedHeap() throws Exception {
    java.util.Set<String> entries = new java.util.LinkedHashSet<>();
    entries.add(System.getProperty("java.class.path"));
    for (ClassLoader loader = getClass().getClassLoader();
        loader != null;
        loader = loader.getParent()) {
      if (loader instanceof java.net.URLClassLoader urls) {
        for (java.net.URL url : urls.getURLs()) entries.add(Path.of(url.toURI()).toString());
      }
    }
    Process child =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx192m",
                "-Djdk.attach.allowAttachSelf=true",
                "-cp",
                String.join(java.io.File.pathSeparator, entries),
                getClass().getName(),
                root.toString())
            .redirectErrorStream(true)
            .redirectOutput(root.resolve("memory-check.log").toFile())
            .start();
    try {
      assertThat(child.waitFor(90, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      assertThat(child.exitValue())
          .withFailMessage(Files.readString(root.resolve("memory-check.log")))
          .isZero();
    } finally {
      if (child.isAlive()) child.destroyForcibly();
    }
  }

  /** Dedicated 192 MiB child: this document exceeds the heap when materialized as a JSON tree. */
  public static void main(String[] args) throws Exception {
    MouseVideoFrameIndexServiceTest test = new MouseVideoFrameIndexServiceTest();
    test.root = Path.of(args[0]);
    test.sources = org.mockito.Mockito.mock(MouseVideoSourceRepository.class);
    test.analyses = org.mockito.Mockito.mock(MouseVideoAnalysisRepository.class);
    test.artifacts = org.mockito.Mockito.mock(MouseVideoArtifactRefRepository.class);
    test.setUp();
    Path document = test.root.resolve("large-observations.json");
    try (var writer = Files.newBufferedWriter(document)) {
      writer.write("{\"schema\":\"observations/v1\",\"observations\":[");
      String record =
          "{\"kind\":\"CURSOR_VISIBILITY\",\"pts\":0,\"visibility\":\"HIDDEN\",\"unknownReason\":\""
              + "x".repeat(128)
              + "\"}";
      for (int i = 0; i < 1_000_000; i++) {
        if (i > 0) writer.write(',');
        writer.write(record);
      }
      writer.write("]}");
    }
    long size = Files.size(document);
    MouseVideoByteStore.StagedArtifact staged;
    try (var input = Files.newInputStream(document)) {
      staged = test.byteStore.stage(input, size);
    }
    test.byteStore.publish(staged);
    test.stubArtifact(
        MouseVideoArtifactRef.ROLE_OBSERVATIONS, staged.checksum(), "application/json");
    var pool = java.util.concurrent.Executors.newFixedThreadPool(4);
    try {
      var calls = new java.util.ArrayList<java.util.concurrent.Callable<Void>>();
      for (int i = 0; i < 4; i++)
        calls.add(
            () -> {
              var page =
                  test.observations.list(
                      OWNER, test.sourceId, test.analysisId, null, 2, null, null, null);
              assertThat((List<?>) page.get("items")).hasSize(2);
              assertThat(page.get("nextCursor")).isNotNull();
              return null;
            });
      for (var result : pool.invokeAll(calls)) result.get();
    } finally {
      pool.shutdownNow();
    }
    System.out.println(
        "PASS four concurrent reads, artifact bytes="
            + size
            + ", max heap="
            + Runtime.getRuntime().maxMemory());
  }

  private void stubArtifact(String role, String checksum, String mediaType) {
    MouseVideoArtifactRef ref =
        new MouseVideoArtifactRef(OWNER, sourceId, analysisId, role, checksum, 512L, mediaType);
    ref.finalizeRow();
    when(artifacts.findByAnalysisIdAndRoleAndState(
            analysisId, role, MouseVideoArtifactRef.STATE_FINALIZED))
        .thenReturn(Optional.of(ref));
  }

  private String publishFrameIndex(String json) throws Exception {
    return publish(json.getBytes(StandardCharsets.UTF_8));
  }

  private String publishObservations(String json) throws Exception {
    return publish(json.getBytes(StandardCharsets.UTF_8));
  }

  private String publish(byte[] bytes) throws Exception {
    MouseVideoByteStore.StagedArtifact staged =
        byteStore.stage(new ByteArrayInputStream(bytes), bytes.length + 1L);
    byteStore.publish(staged);
    return staged.checksum();
  }

  private static String frameIndexJson() {
    return "{\"schema\":\"frame-index/v2\",\"complete\":true,\"timeBase\":\"1/1000\",\"frames\":["
        + "{\"pts\":0,\"ptsTime\":0,\"duration\":0.04,\"key\":true},"
        + "{\"pts\":9007199254740993,\"ptsTime\":9007199254740.993,\"duration\":0.04,\"key\":false},"
        + "{\"pts\":18014398509481986,\"ptsTime\":18014398509481.986,\"duration\":0.04,\"key\":false}"
        + "]}";
  }

  private static String observationsJson() {
    return "{\"schema\":\"observations/v1\",\"observations\":["
        + "{\"kind\":\"CURSOR_VISIBILITY\",\"frameOrdinal\":0,\"pts\":0,\"visibility\":\"VISIBLE\","
        + "\"confidence\":0.9,\"x\":10,\"y\":20},"
        + "{\"kind\":\"CURSOR_VISIBILITY\",\"frameOrdinal\":1,\"pts\":20,\"visibility\":\"HIDDEN\","
        + "\"confidence\":0.4,\"unknownReason\":\"occluded\"},"
        + "{\"kind\":\"SCENE_CUT\",\"frameOrdinal\":2,\"pts\":40,\"confidence\":0.99},"
        + "{\"kind\":\"CURSOR_VISIBILITY\",\"frameOrdinal\":3,\"pts\":60,\"visibility\":\"AMBIGUOUS\","
        + "\"confidence\":0.3,\"unknownReason\":\"compression\"}]}";
  }

  private static String observationsWithEvidenceJson() {
    return "{\"schema\":\"observations/v1\",\"observations\":["
        + "{\"kind\":\"CURSOR_VISIBILITY\",\"frameOrdinal\":0,\"pts\":0,\"visibility\":\"VISIBLE\","
        + "\"confidence\":0.87,\"x\":10,\"y\":20,\"evidence\":{\"rule\":\"held\","
        + "\"continuation\":true,\"pass\":\"backward\",\"score\":0.87,\"runnerUpScore\":0.41,"
        + "\"sourceScale\":1.0,\"missedSamples\":2,\"forwardReason\":\"below reviewed threshold\","
        + "\"gain\":0.9,\"phaseX\":0.5,\"maskPixels\":84,\"windowed\":true,"
        + "\"rechecked\":true,\"takeover\":false,"
        + "\"reason\":\"competing placement\",\"notAllowed\":\"secret\"}},"
        + "{\"kind\":\"SCENE_CUT\",\"frameOrdinal\":1,\"pts\":20,\"confidence\":0.99,"
        + "\"evidence\":{\"meanDelta\":0.9,\"rule\":\"absolute\"}}]}";
  }

  /** Re-encodes a cursor with a substituted artifact address to prove the pinning check. */
  private static String tamper(String cursor, String checksum) {
    String raw =
        new String(java.util.Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
    String[] parts = raw.split("\\|", -1);
    String replaced = parts[0] + '|' + checksum + '|' + parts[2];
    return java.util.Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(replaced.getBytes(StandardCharsets.UTF_8));
  }
}
