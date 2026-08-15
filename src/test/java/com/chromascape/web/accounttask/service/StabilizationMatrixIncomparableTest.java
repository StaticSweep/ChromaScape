package com.chromascape.web.accounttask.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.chromascape.web.accounttask.entity.ScriptStabilizationSnapshot;
import com.chromascape.web.accounttask.repository.ScriptFailureTriageRepository;
import com.chromascape.web.accounttask.repository.ScriptStabilizationRunRepository;
import com.chromascape.web.accounttask.repository.ScriptStabilizationSnapshotRepository;
import com.chromascape.web.accounttask.service.StabilizationArtifactStore;
import com.chromascape.web.accounttask.repository.StabilizationArtifactRepository;
import com.chromascape.web.accounttask.repository.StabilizationRunAggregateRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Incomparable matrix contract (SC-572): incomparable entries are persisted and readable. */
@ExtendWith(MockitoExtension.class)
class StabilizationMatrixIncomparableTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Mock private ScriptStabilizationRunRepository runRepository;
  @Mock private ScriptFailureTriageRepository triageRepository;
  @Mock private ScriptStabilizationSnapshotRepository snapshotRepository;
  @Mock private StabilizationArtifactStore artifactStore;
  @Mock private StabilizationArtifactRepository artifactRepository;
  @Mock private StabilizationRunAggregateRepository aggregateRepository;
  @Mock private jakarta.persistence.EntityManager entityManager;

  private StabilizationIngestionService service;

  @BeforeEach
  void setUp() {
    service = new StabilizationIngestionService(runRepository, triageRepository, snapshotRepository,
            artifactStore, artifactRepository, aggregateRepository, entityManager);
  }

  @Test
  @DisplayName("a matrix with incomparable entries persists them and reconstructs the document")
  void incomparableEntriesArePersisted() throws Exception {
    ScriptStabilizationSnapshot snapshot = new ScriptStabilizationSnapshot();
    String matrixJson =
        """
        {
          "schema_version": "reliability-matrix/v1",
          "matrix_hash": "sha256:matrix",
          "groups": [],
          "incomparable": [
            {"reason": "provenance_variance:artifact_digest", "run_id": "run-1"},
            {"reason": "missing_group_field:build_id", "run_id": "run-2"}
          ]
        }
        """;
    snapshot.setMatrixJson(matrixJson);
    when(snapshotRepository.findByMatrixHashOrderByGroupKeyAsc("sha256:matrix"))
        .thenReturn(List.of(snapshot));

    Optional<JsonNode> document = service.getMatrixDocumentByHash("sha256:matrix");

    assertThat(document).isPresent();
    assertThat(document.get().path("incomparable")).hasSize(2);
    assertThat(document.get().path("incomparable").get(0).path("reason").asText())
        .isEqualTo("provenance_variance:artifact_digest");
  }

  @Test
  @DisplayName("an unknown matrix hash returns empty")
  void unknownMatrixReturnsEmpty() {
    when(snapshotRepository.findByMatrixHashOrderByGroupKeyAsc("missing")).thenReturn(List.of());

    assertThat(service.getMatrixDocumentByHash("missing")).isEmpty();
  }
}
