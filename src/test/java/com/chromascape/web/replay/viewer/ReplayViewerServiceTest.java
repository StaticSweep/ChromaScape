package com.chromascape.web.replay.viewer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import com.chromascape.web.replay.api.dto.ArtifactKind;
import com.chromascape.web.replay.artifact.ReplayArtifactService;
import com.chromascape.web.replay.artifact.entity.ArtifactRef;
import com.chromascape.web.replay.artifact.repository.ArtifactRefRepository;
import com.chromascape.web.replay.config.ReplayProperties;
import com.chromascape.web.replay.error.ReplayErrorCode;
import com.chromascape.web.replay.error.ReplayException;
import com.chromascape.web.replay.projection.CanonicalProjectionService;
import com.chromascape.web.replay.report.repository.ReplayReportRepository;
import com.chromascape.web.replay.run.entity.ReplayRun;
import com.chromascape.web.replay.run.repository.ReplayRunRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReplayViewerServiceTest {

  @Mock private ArtifactRefRepository artifactRepository;
  @Mock private ReplayArtifactService artifactService;
  @Mock private ReplayReportRepository reportRepository;
  @Mock private ReplayRunRepository runRepository;

  private final ObjectMapper mapper = new ObjectMapper();
  private ReplayViewerService service;
  private UUID runId;

  @BeforeEach
  void setUp() {
    service =
        new ReplayViewerService(
            artifactRepository,
            artifactService,
            reportRepository,
            runRepository,
            new ReplayProperties(),
            mapper);
    runId = UUID.randomUUID();
  }

  @Test
  void framesUseOpaqueCursorAndPreserveTraceOrder() throws Exception {
    ReplayRun run = new ReplayRun();
    run.setId(runId);
    run.setOwnerId("owner");
    run.setViewerTraceChecksum("sha256:trace");
    run.setViewerTraceFrameCount(2L);
    ArtifactRef ref =
        new ArtifactRef(UUID.randomUUID(), runId, ArtifactKind.TRACE, "sha256:trace", 1);
    ref.setState(ArtifactRef.STATE_FINALIZED);
    ObjectNode trace = mapper.createObjectNode();
    trace.put("schema", "xrsps-replay-trace/v1");
    trace
        .putArray("frames")
        .add(frame(0, 10, "state-0", "chain-0"))
        .add(frame(1, 11, "state-1", "chain-1"));
    byte[] content = mapper.writeValueAsBytes(trace);
    when(runRepository.findById(runId)).thenReturn(Optional.of(run));
    when(artifactRepository.findFirstByRunIdAndKindAndStateOrderByCreatedAtDesc(
            runId, ArtifactKind.TRACE, ArtifactRef.STATE_FINALIZED))
        .thenReturn(Optional.of(ref));
    when(artifactService.readVerified(ref)).thenReturn(content);

    var first = service.frames(runId, "owner", null, null, 1);
    var second = service.frames(runId, "owner", null, first.nextCursor(), 1);

    assertEquals(0, first.frames().get(0).path("ordinal").asInt());
    assertEquals(11, second.frames().get(0).path("tick").asInt());
    ObjectNode checksumBasis = mapper.createObjectNode();
    checksumBasis.put("schema", "xrsps-replay-trace/v1");
    checksumBasis.set("frames", trace.path("frames"));
    assertEquals(
        new CanonicalProjectionService()
            .sha256Hex(new CanonicalProjectionService().canonicalJsonBytes(checksumBasis)),
        second.traceChecksum());
    assertEquals(false, second.hasMore());
  }

  @Test
  void invalidCursorIsRejectedBeforeServingTrace() throws Exception {
    ReplayRun run = new ReplayRun();
    run.setId(runId);
    run.setOwnerId("owner");
    run.setViewerTraceChecksum("sha256:trace");
    ArtifactRef ref =
        new ArtifactRef(UUID.randomUUID(), runId, ArtifactKind.TRACE, "sha256:trace", 1);
    ref.setState(ArtifactRef.STATE_FINALIZED);
    ObjectNode trace = mapper.createObjectNode();
    trace.put("schema", "xrsps-replay-trace/v1");
    trace.putArray("frames").add(frame(0, 10, "state-0", "chain-0"));
    when(runRepository.findById(runId)).thenReturn(Optional.of(run));
    when(artifactRepository.findFirstByRunIdAndKindAndStateOrderByCreatedAtDesc(
            runId, ArtifactKind.TRACE, ArtifactRef.STATE_FINALIZED))
        .thenReturn(Optional.of(ref));
    when(artifactService.readVerified(ref)).thenReturn(mapper.writeValueAsBytes(trace));

    ReplayException error =
        assertThrows(
            ReplayException.class, () -> service.frames(runId, "owner", null, "not-a-cursor", 1));

    assertEquals(ReplayErrorCode.VALIDATION_ERROR, error.getErrorCode());
  }

  private ObjectNode frame(int ordinal, int tick, String stateHash, String chainHash) {
    ObjectNode frame = mapper.createObjectNode();
    frame.put("ordinal", ordinal);
    frame.put("tick", tick);
    frame.put("stateHash", stateHash);
    frame.put("chainHash", chainHash);
    return frame;
  }
}
