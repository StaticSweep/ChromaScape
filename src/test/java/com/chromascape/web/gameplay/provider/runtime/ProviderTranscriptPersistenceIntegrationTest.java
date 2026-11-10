package com.chromascape.web.gameplay.provider.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chromascape.web.gameplay.contract.CanonicalGameplayJson;
import com.chromascape.web.gameplay.contract.GameplayContracts.Provider;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.BudgetLimits;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.SessionRequest;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.TurnRequest;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import jakarta.persistence.EntityManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/** Database metadata must match the exact redacted artifact bytes; tampering fails closed. */
class ProviderTranscriptPersistenceIntegrationTest extends AbstractProviderRuntimePostgresTest {
  private static final String DIGEST = "sha256:" + "a".repeat(64);

  @Autowired private JpaProviderRuntimeStore store;
  @Autowired private CanonicalGameplayJson canonicalJson;
  @Autowired private EntityManager entityManager;

  @TempDir Path temporary;

  @Test
  @Transactional
  void transcriptMetadataMatchesExactBytesAndTamperingFailsClosed() throws Exception {
    ProviderRuntimeProperties properties = new ProviderRuntimeProperties();
    properties.setTranscriptRoot(temporary.resolve("transcripts"));
    RedactedTranscriptStore transcripts = new RedactedTranscriptStore(properties, canonicalJson);

    SessionRequest session = session("transcript-" + System.nanoTime());
    store.createSession(session);
    TurnRequest turn = turn(session, "transcript");
    store.reserveTurn(session.sessionId(), turn);
    store.markSubmitted(turn.turnId(), "provider-session", "provider-turn", List.of());
    store.markStreaming(turn.turnId());

    RedactedTranscriptStore.Artifact artifact =
        transcripts.store("api_key=super-secret password: hunter2 token abc123 result=ok");

    Path file =
        temporary.resolve(
            "transcripts/" + artifact.reference().substring("sha256:".length()) + ".json");
    assertTrue(
        file.toAbsolutePath()
            .normalize()
            .startsWith(temporary.resolve("transcripts").toAbsolutePath().normalize()));
    byte[] bytes = Files.readAllBytes(file);
    assertEquals(artifact.byteLength(), bytes.length);
    assertEquals(
        "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
        artifact.reference());

    String stored = Files.readString(file);
    assertTrue(stored.contains("[REDACTED]"));
    assertFalse(stored.contains("super-secret"));
    assertFalse(stored.contains("hunter2"));

    store.recordTranscript(turn.turnId(), artifact.reference(), artifact.byteLength());
    ProviderTranscriptEntity row =
        entityManager.find(ProviderTranscriptEntity.class, artifact.reference());
    assertNotNull(row, "transcript metadata must be persisted");
    assertEquals(artifact.reference(), row.sha256);
    assertEquals(artifact.byteLength(), row.byteLength);
    assertEquals("sc-1430-redaction/v1", row.redactionVersion);
    assertEquals(turn.turnId(), row.turnId);

    Files.writeString(file, "tampered-content");
    ProviderRuntimeException tamper =
        assertThrows(
            ProviderRuntimeException.class,
            () ->
                transcripts.store("api_key=super-secret password: hunter2 token abc123 result=ok"));
    assertEquals(
        ProviderRuntimeModels.FailureCode.TRANSCRIPT_PERSISTENCE_FAILED.name(),
        tamper.failureCode());
  }

  /**
   * The v2 producer derives its decision from persisted provider output, so the handoff needs the
   * verified transcript text — not just its digest. The read must return exactly the stored,
   * redacted bytes and must fail closed once the artifact is tampered with.
   */
  @Test
  void verifiedTranscriptTextIsReadableAndTamperingFailsClosed() throws Exception {
    ProviderRuntimeProperties properties = new ProviderRuntimeProperties();
    properties.setTranscriptRoot(temporary.resolve("transcripts-read"));
    RedactedTranscriptStore transcripts = new RedactedTranscriptStore(properties, canonicalJson);

    RedactedTranscriptStore.Artifact artifact =
        transcripts.store("{\"decision\":\"ok\",\"api_key\":\"super-secret\"}");

    String text = transcripts.read(artifact.reference());
    assertEquals(Files.readString(
            temporary.resolve(
                "transcripts-read/"
                    + artifact.reference().substring("sha256:".length())
                    + ".json")),
        text);
    assertTrue(text.contains("\"decision\":\"ok\""));
    assertFalse(text.contains("super-secret"));

    Path file =
        temporary.resolve(
            "transcripts-read/" + artifact.reference().substring("sha256:".length()) + ".json");
    Files.writeString(file, "{\"decision\":\"tampered\"}");

    ProviderRuntimeException tamper =
        assertThrows(
            ProviderRuntimeException.class, () -> transcripts.read(artifact.reference()));
    assertEquals(
        ProviderRuntimeModels.FailureCode.TRANSCRIPT_DIGEST_MISMATCH.name(),
        tamper.failureCode());
  }

  private SessionRequest session(String id) {
    return new SessionRequest(
        id,
        Provider.CODEX,
        "gpt-5",
        "task-test",
        "run-test",
        "recording-test",
        DIGEST,
        DIGEST,
        DIGEST,
        DIGEST,
        List.of(DIGEST),
        Instant.now().plusSeconds(300),
        new BudgetLimits(10_000, 10_000, 4, 0, 4, 60_000, 240_000),
        true);
  }

  private TurnRequest turn(SessionRequest session, String suffix) {
    return new TurnRequest(
        session.sessionId() + "-turn-" + suffix,
        session.sessionId() + "-request-" + suffix,
        session.sessionId() + "-idempotency-" + suffix,
        DIGEST,
        JsonNodeFactory.instance.objectNode(),
        DIGEST,
        JsonNodeFactory.instance.arrayNode(),
        DIGEST,
        JsonNodeFactory.instance.objectNode(),
        "source-test",
        10,
        10,
        Instant.now().plusSeconds(60),
        true);
  }
}
