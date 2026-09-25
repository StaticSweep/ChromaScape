package com.chromascape.web.gameplay.orchestration;

import com.chromascape.web.gameplay.contract.CanonicalGameplayJson;
import com.chromascape.web.gameplay.contract.GameplayContracts.ExecutionEnvelope;
import com.chromascape.web.gameplay.contract.GameplayContracts.HumanAuthority;
import com.chromascape.web.gameplay.contract.GameplayContracts.ProviderDecision;
import com.chromascape.web.gameplay.contract.GameplayContracts.Receipt;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Fail-closed atomic evidence writer for one provider-origin bootstrap recording. */
public final class BootstrapEvidenceStore {
  private final ObjectMapper objectMapper;
  private final CanonicalGameplayJson canonicalJson;
  private final Path root;
  private final Map<String, EvidenceChain> active = new ConcurrentHashMap<>();

  public BootstrapEvidenceStore(
      ObjectMapper objectMapper, CanonicalGameplayJson canonicalJson, Path root) {
    this.objectMapper = objectMapper;
    this.canonicalJson = canonicalJson;
    this.root = root;
  }

  public void begin(
      JsonNode observation,
      ProviderDecision decision,
      ExecutionEnvelope envelope,
      HumanAuthority authority) {
    if (decision.provider() == null) {
      throw new IllegalArgumentException("Provider provenance is required");
    }
    String recordingId = envelope.identities().path("recordingId").asText();
    if (recordingId.isBlank()) {
      throw new IllegalArgumentException("recordingId is required");
    }
    EvidenceChain chain =
        new EvidenceChain(
            recordingId,
            decision.provider().name(),
            decision.modelVersion(),
            observation.deepCopy(),
            decision,
            envelope,
            authority,
            new ArrayList<>(),
            Instant.now());
    if (active.putIfAbsent(recordingId, chain) != null) {
      throw new IllegalStateException("Recording evidence chain already exists: " + recordingId);
    }
  }

  public void append(String recordingId, Receipt receipt) {
    EvidenceChain chain = requireActive(recordingId);
    synchronized (chain) {
      chain.receipts().add(receipt);
    }
  }

  public Path finalizeAtomically(String recordingId) {
    EvidenceChain chain = requireActive(recordingId);
    synchronized (chain) {
      if (chain.receipts().isEmpty()) {
        throw new IllegalStateException("Cannot finalize an evidence chain without receipts");
      }
      Map<String, Object> document = new LinkedHashMap<>();
      document.put("contractVersion", "sc-1429/v1");
      document.put("recordingKind", "BOOTSTRAP_RECORDING");
      document.put(
          "provenance", Map.of("origin", chain.provider(), "modelVersion", chain.modelVersion()));
      document.put("observation", chain.observation());
      document.put("decision", chain.decision());
      document.put("envelope", chain.envelope());
      document.put("authority", chain.authority());
      document.put("receipts", List.copyOf(chain.receipts()));
      document.put("startedAt", chain.startedAt());
      document.put("finalizedAt", Instant.now());
      document.put("learnedModelActivated", false);
      document.put("continuingAuthority", false);
      document.put("simulatorTrace", false);
      document.put("evidenceDigest", canonicalJson.digest(document));
      Path temporary = null;
      try {
        Files.createDirectories(root);
        Path target = root.resolve(recordingId + ".sc-1498.json");
        if (Files.exists(target)) {
          throw new IllegalStateException("Immutable bootstrap evidence already exists: " + target);
        }
        temporary = Files.createTempFile(root, recordingId + ".", ".pending");
        objectMapper.writeValue(temporary.toFile(), document);
        try {
          Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
          throw new IllegalStateException(
              "Evidence filesystem does not support atomic finalization", exception);
        }
        active.remove(recordingId, chain);
        return target;
      } catch (IOException exception) {
        throw new IllegalStateException(
            "Failed to atomically finalize bootstrap evidence", exception);
      } finally {
        if (temporary != null) {
          try {
            Files.deleteIfExists(temporary);
          } catch (IOException ignored) {
            // The authoritative target is never replaced; a stale .pending file is non-published.
          }
        }
      }
    }
  }

  private EvidenceChain requireActive(String recordingId) {
    EvidenceChain chain = active.get(recordingId);
    if (chain == null) {
      throw new IllegalArgumentException("Unknown active recording evidence chain: " + recordingId);
    }
    return chain;
  }

  private record EvidenceChain(
      String recordingId,
      String provider,
      String modelVersion,
      JsonNode observation,
      ProviderDecision decision,
      ExecutionEnvelope envelope,
      HumanAuthority authority,
      List<Receipt> receipts,
      Instant startedAt) {}
}
