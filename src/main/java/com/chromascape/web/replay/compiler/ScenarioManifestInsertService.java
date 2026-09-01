package com.chromascape.web.replay.compiler;

import com.chromascape.web.replay.manifest.entity.ScenarioManifest;
import com.chromascape.web.replay.manifest.repository.ScenarioManifestRepository;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Dedicated REQUIRES_NEW insertion point for compiled scenario manifests.
 *
 * <p>The deterministic compiler itself must stay OUTSIDE any database transaction: when two
 * instances compile an identical scenario concurrently, exactly one INSERT commits and the loser
 * receives {@code DataIntegrityViolationException} from its OWN transaction. That exception must
 * escape this method untouched so the non-transactional orchestrator can fetch and verify the
 * winning row in a fresh transaction — catching it inside this transaction would poison it and make
 * every subsequent read untrustworthy.
 *
 * <p>Must be invoked through the Spring proxy (injected bean), never through {@code this}.
 */
@Service
public class ScenarioManifestInsertService {

  private final ScenarioManifestRepository repository;

  public ScenarioManifestInsertService(ScenarioManifestRepository repository) {
    this.repository = repository;
  }

  /**
   * Inserts the manifest in its own committed transaction.
   *
   * @throws org.springframework.dao.DataIntegrityViolationException when a concurrent identical
   *     compile inserted the same checksum first; propagates untouched
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public ScenarioManifest insert(ScenarioManifest entity) {
    return repository.saveAndFlush(entity);
  }

  /** Narrow type alias so tests can stub the persistence boundary without JPA internals. */
  interface ManifestPersistence extends JpaRepository<ScenarioManifest, UUID> {}
}
