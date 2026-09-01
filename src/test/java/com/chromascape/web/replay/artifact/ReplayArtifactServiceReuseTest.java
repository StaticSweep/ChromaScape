package com.chromascape.web.replay.artifact;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.chromascape.web.replay.api.dto.ArtifactKind;
import com.chromascape.web.replay.artifact.entity.ArtifactRef;
import com.chromascape.web.replay.artifact.repository.ArtifactRefRepository;
import com.chromascape.web.replay.config.ReplayProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Content-addressed publication: preparation touches no database rows, verified final files are
 * reused rather than overwritten in a publication race, and a corrupt pre-existing final file
 * blocks publication while preserving evidence.
 */
@ExtendWith(MockitoExtension.class)
class ReplayArtifactServiceReuseTest {

  @TempDir Path tempRoot;

  @Mock private ArtifactRefRepository repo;

  private ReplayArtifactService service;

  @BeforeEach
  void setUp() {
    ReplayProperties properties = new ReplayProperties();
    properties.getArtifacts().setRoot(tempRoot.toString());
    service = new ReplayArtifactService(repo, properties);
  }

  private static String hexOf(String checksum) {
    return checksum.substring("sha256:".length());
  }

  private Path finalPathOf(String checksum) {
    String hex = hexOf(checksum);
    return tempRoot.resolve(hex.substring(0, 2)).resolve(hex);
  }

  private ArtifactRef refFor(PreparedArtifact prepared) {
    return new ArtifactRef(
        prepared.proposedReferenceId(),
        prepared.runId(),
        prepared.kind(),
        prepared.checksum(),
        prepared.sizeBytes());
  }

  @Test
  void prepareWritesStagedBytesWithoutTouchingDatabase() {
    byte[] content = "trace-bytes".getBytes();
    PreparedArtifact prepared =
        service.prepareArtifact(UUID.randomUUID(), ArtifactKind.TRACE, content, "application/json");

    assertTrue(
        Files.exists(prepared.stagingPath()),
        "staging path is <root>/staging/<runId>/<artifactRefId>");
    try {
      assertArrayEquals(content, Files.readAllBytes(prepared.stagingPath()));
    } catch (IOException e) {
      throw new AssertionError(e);
    }
    verifyNoInteractions(repo);
  }

  @Test
  void publishVerifiesContentReturnsReceiptAndNeverMutatesRows() {
    UUID runId = UUID.randomUUID();
    PreparedArtifact prepared =
        service.prepareArtifact(
            runId, ArtifactKind.SNAPSHOT, "snap".getBytes(), "application/json");
    ArtifactRef ref = refFor(prepared);
    when(repo.findByRunIdAndState(runId, ArtifactRef.STATE_STAGING)).thenReturn(List.of(ref));

    ArtifactPublicationReceipt receipt = service.publishArtifacts(runId);

    assertEquals(
        ArtifactRef.STATE_STAGING,
        ref.getState(),
        "publication must not mark rows FINALIZED; that is fenced");
    assertEquals(1, receipt.published().size());
    assertEquals(prepared.checksum(), receipt.published().get(0).checksum());
    assertEquals(
        PublishedArtifact.PublicationDisposition.CREATED,
        receipt.published().get(0).disposition());
    assertTrue(Files.exists(finalPathOf(prepared.checksum())));
    assertTrue(
        !Files.exists(tempRoot.resolve("staging").resolve(runId.toString())),
        "empty staging dir cleaned after publication");
    // Reads are allowed; publication must never write database rows.
    verify(repo, never()).save(any());
  }

  @Test
  void identicalContentAcrossRunsReusesWinnerWithoutOverwrite() throws IOException {
    byte[] manifest = "{\"manifestVersion\":1}".getBytes();
    UUID runA = UUID.randomUUID();
    UUID runB = UUID.randomUUID();

    PreparedArtifact preparedA =
        service.prepareArtifact(runA, ArtifactKind.MANIFEST, manifest, "application/json");
    ArtifactRef refA = refFor(preparedA);
    when(repo.findByRunIdAndState(runA, ArtifactRef.STATE_STAGING)).thenReturn(List.of(refA));
    service.publishArtifacts(runA);

    Path finalPath = finalPathOf(preparedA.checksum());
    long modifiedBefore = Files.getLastModifiedTime(finalPath).toMillis();

    // Second run stages identical content: its publication must verify
    // and reuse the winner instead of overwriting it.
    PreparedArtifact preparedB =
        service.prepareArtifact(runB, ArtifactKind.REPORT, manifest, "application/json");
    assertNotEquals(preparedA.proposedReferenceId(), preparedB.proposedReferenceId());
    assertEquals(preparedA.checksum(), preparedB.checksum(), "content address matches across runs");
    ArtifactRef refB = refFor(preparedB);
    when(repo.findByRunIdAndState(runB, ArtifactRef.STATE_STAGING)).thenReturn(List.of(refB));
    ArtifactPublicationReceipt secondReceipt = service.publishArtifacts(runB);

    assertEquals(
        PublishedArtifact.PublicationDisposition.REUSED_EXISTING,
        secondReceipt.published().get(0).disposition());

    assertEquals(
        modifiedBefore,
        Files.getLastModifiedTime(finalPath).toMillis(),
        "verified final file must never be overwritten");
    assertArrayEquals(manifest, Files.readAllBytes(finalPath));
  }

  @Test
  void finalizedReadbackVerifiesTheContentAddress() throws IOException {
    UUID runId = UUID.randomUUID();
    byte[] content = "readback-bytes".getBytes();
    PreparedArtifact prepared =
        service.prepareArtifact(runId, ArtifactKind.TRACE, content, "application/octet-stream");
    ArtifactRef ref = refFor(prepared);
    when(repo.findByRunIdAndState(runId, ArtifactRef.STATE_STAGING)).thenReturn(List.of(ref));
    service.publishArtifacts(runId);

    assertArrayEquals(content, service.readFinalized(ref));
    Files.write(finalPathOf(prepared.checksum()), "tampered".getBytes());

    var failure =
        assertThrows(
            com.chromascape.web.replay.error.ReplayException.class,
            () -> service.readFinalized(ref));
    assertEquals(
        com.chromascape.web.replay.error.ReplayErrorCode.CHECKSUM_MISMATCH,
        failure.getErrorCode());
  }

  @Test
  void concurrentPublicationRaceReusesVerifiedWinner() throws IOException {
    UUID runId = UUID.randomUUID();
    PreparedArtifact prepared =
        service.prepareArtifact(
            runId, ArtifactKind.REPORT, "race-body".getBytes(), "application/json");

    // Simulate another instance winning the race between fence
    // registration and this move: the final file already exists and is
    // byte-identical. The loser must delete its staged copy and reuse.
    Path finalPath = finalPathOf(prepared.checksum());
    Files.createDirectories(finalPath.getParent());
    Files.write(finalPath, "race-body".getBytes());
    long modifiedBefore = Files.getLastModifiedTime(finalPath).toMillis();

    ArtifactRef ref = refFor(prepared);
    when(repo.findByRunIdAndState(runId, ArtifactRef.STATE_STAGING)).thenReturn(List.of(ref));
    ArtifactPublicationReceipt receipt = service.publishArtifacts(runId);

    assertEquals(1, receipt.published().size());
    assertTrue(
        !Files.exists(prepared.stagingPath()),
        "redundant staged copy deleted after winner verification");
    assertEquals(modifiedBefore, Files.getLastModifiedTime(finalPath).toMillis());
  }

  @Test
  void corruptPreexistingFinalFileBlocksPublication() throws IOException {
    UUID runId = UUID.randomUUID();
    PreparedArtifact prepared =
        service.prepareArtifact(
            runId, ArtifactKind.REPORT, "real-report".getBytes(), "application/json");
    // Tampered content at the expected content address.
    Path finalPath = finalPathOf(prepared.checksum());
    Files.createDirectories(finalPath.getParent());
    Files.write(finalPath, "tampered".getBytes());

    ArtifactRef ref = refFor(prepared);
    when(repo.findByRunIdAndState(runId, ArtifactRef.STATE_STAGING)).thenReturn(List.of(ref));

    assertThrows(
        com.chromascape.web.replay.error.ReplayException.class,
        () -> service.publishArtifacts(runId),
        "wrong-checksum final content blocks publication");
    // Evidence preserved on disk: neither file destroyed.
    assertTrue(Files.exists(finalPath));
    assertTrue(Files.exists(prepared.stagingPath()));
  }

  @Test
  void missingContentEverywhereBlocksPublication() {
    UUID runId = UUID.randomUUID();
    PreparedArtifact prepared =
        service.prepareArtifact(runId, ArtifactKind.TRACE, "gone".getBytes(), "application/json");
    try {
      Files.deleteIfExists(prepared.stagingPath());
    } catch (IOException ignored) {
      // ignore
    }
    ArtifactRef ref = refFor(prepared);
    when(repo.findByRunIdAndState(runId, ArtifactRef.STATE_STAGING)).thenReturn(List.of(ref));

    assertThrows(
        com.chromascape.web.replay.error.ReplayException.class,
        () -> service.publishArtifacts(runId));
  }
}
