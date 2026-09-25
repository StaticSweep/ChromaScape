package com.chromascape.web.replay.compiler;

import com.chromascape.web.replay.manifest.entity.ScenarioManifest;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fresh-transaction winner reader for concurrent manifest compiles. After a losing insert
 * transaction rolls back, the winning row is read HERE — in a NEW read-only transaction with no
 * poisoned persistence context — so the orchestrator compares committed truth, never stale state.
 *
 * <p>Must be invoked through the Spring proxy (injected bean), never through {@code this}.
 */
@Service
public class ScenarioManifestWinnerReader {

  private final com.chromascape.web.replay.manifest.repository.ScenarioManifestRepository
      repository;

  public ScenarioManifestWinnerReader(
      com.chromascape.web.replay.manifest.repository.ScenarioManifestRepository repository) {
    this.repository = repository;
  }

  /** Reads a manifest by id in a fresh {@code REQUIRES_NEW} read-only transaction. */
  @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
  public Optional<ScenarioManifest> findById(UUID id) {
    return repository.findById(id);
  }

  /** Reads a manifest by checksum in a fresh {@code REQUIRES_NEW} read-only transaction. */
  @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
  public Optional<ScenarioManifest> findByChecksum(String manifestChecksum) {
    return repository.findByManifestChecksum(manifestChecksum);
  }
}
