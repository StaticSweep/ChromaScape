package com.chromascape.web.replay.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.chromascape.web.debug.tickdebugger.entity.DebugSession;
import com.chromascape.web.debug.tickdebugger.entity.TickSnapshot;
import com.chromascape.web.debug.tickdebugger.entity.TickSnapshotRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RecordingCatalogIntegrityTest {

  @Test
  void publicCatalogUsesMetadataEligibilityInsteadOfHydratingAllTicks() {
    var recordings = mock(com.chromascape.web.recording.entity.RecordingSessionRepository.class);
    var resolver = mock(ReplayRecordingResolver.class);
    var integrity = mock(RecordingIntegrityService.class);
    var recording = new com.chromascape.web.recording.entity.RecordingSession();
    recording.setRecordingId("owned-fixture");
    var resolved = new ReplayRecordingResolver.ResolvedRecording(recording, null);
    when(recordings.findAllByOrderByStartTimeDesc()).thenReturn(List.of(recording));
    when(resolver.resolve("owned-fixture")).thenReturn(resolved);
    new ReplayCatalogService(recordings, resolver, integrity).catalog();
    verify(integrity).evaluateCatalog(resolved);
    verify(integrity, never()).evaluate(any());
  }

  @Test
  void catalogKeepsGapAndSchemaVerdictsWithoutLoadingAllWorldPayloads() {
    for (var secondTick : List.of(1L, 2L, 0L)) {
      for (Integer version : java.util.Arrays.asList(1, 2, null)) {
        var repository = mock(TickSnapshotRepository.class);
        var session = new DebugSession();
        session.setSessionId("fixture");
        session.setEndTime(LocalDateTime.now());
        var first = new TickSnapshot();
        first.setTick(0L);
        first.setSchemaVersion(1);
        var second = new TickSnapshot();
        second.setTick(secondTick);
        second.setSchemaVersion(version);
        when(repository.countBySessionId("fixture")).thenReturn(2L);
        when(repository.findMinTickBySessionId("fixture")).thenReturn(Optional.of(0L));
        when(repository.findMaxTickBySessionId("fixture")).thenReturn(Optional.of(secondTick));
        when(repository.findBySessionIdAndTickBetweenOrderByTick("fixture", 0L, secondTick))
            .thenReturn(List.of(first, second));
        when(repository.findFirstBySessionIdOrderByTickAsc("fixture"))
            .thenReturn(Optional.of(first));
        var metadata = List.of(metadata(0L, 1), metadata(secondTick, version));
        when(repository.findEvidenceMetadataBySessionId("fixture")).thenReturn(metadata);
        var resolved = new ReplayRecordingResolver.ResolvedRecording(null, session);
        var service = new RecordingIntegrityService(repository);
        assertThat(service.evaluateCatalog(resolved)).isEqualTo(service.evaluate(resolved));
        verify(repository, times(1))
            .findBySessionIdAndTickBetweenOrderByTick("fixture", 0L, secondTick);
      }
    }
  }

  private static TickSnapshotRepository.EvidenceMetadata metadata(Long tick, Integer version) {
    var metadata = mock(TickSnapshotRepository.EvidenceMetadata.class);
    when(metadata.getTick()).thenReturn(tick);
    when(metadata.getSchemaVersion()).thenReturn(version);
    return metadata;
  }
}
