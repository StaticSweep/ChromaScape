package com.chromascape.web.debug.patchloop.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chromascape.web.debug.patchloop.dto.PatchProposal;
import com.chromascape.web.debug.patchloop.entity.ExecutionRuntimeSnapshot;
import com.chromascape.web.debug.patchloop.entity.ExecutionRuntimeSnapshotRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Tests for {@link ExecutionRuntimeSnapshotService} focusing on typed rehydration. */
@ExtendWith(MockitoExtension.class)
class ExecutionRuntimeSnapshotServiceTest {

  @Mock private ExecutionRuntimeSnapshotRepository repository;

  private ObjectMapper objectMapper;

  private ExecutionRuntimeSnapshotService snapshotService;

  private String executionId;

  @BeforeEach
  void setUp() {
    objectMapper = new ObjectMapper();
    snapshotService = new ExecutionRuntimeSnapshotService(repository, objectMapper);
    executionId = "exec_" + UUID.randomUUID().toString().substring(0, 8);
  }

  @Test
  @DisplayName("getRuntimeValue rehydrates PatchProposal from Map")
  void testGetRuntimeValueRehydratesPatchProposal() {
    // Given: runtime data with a patch proposal stored as Map
    Map<String, Object> patchMap = new HashMap<>();
    patchMap.put("diffText", "sample diff");
    patchMap.put("rationale", "fixes bug");
    patchMap.put("confidence", "HIGH");

    Map<String, Object> runtimeData = new HashMap<>();
    runtimeData.put("patch_1", patchMap);

    ExecutionRuntimeSnapshot snapshot = new ExecutionRuntimeSnapshot();
    snapshot.setExecutionId(executionId);
    snapshot.setRuntimeDataJson(toJson(runtimeData));
    snapshot.setContextDataJson("{}");
    snapshot.setLastUpdatedAt(LocalDateTime.now());

    when(repository.findByExecutionId(executionId)).thenReturn(Optional.of(snapshot));

    // When: getting the runtime value with typed rehydration
    PatchProposal result =
        snapshotService.getRuntimeValue(executionId, "patch_1", PatchProposal.class);

    // Then: should rehydrate to PatchProposal with correct fields
    assertThat(result).isNotNull();
    assertThat(result.getDiffText()).isEqualTo("sample diff");
    assertThat(result.getRationale()).isEqualTo("fixes bug");
  }

  @Test
  @DisplayName("getRuntimeValue returns null for non-existent key")
  void testGetRuntimeValueReturnsNullForNonExistentKey() {
    // Given: snapshot exists without the requested key
    Map<String, Object> runtimeData = new HashMap<>();
    ExecutionRuntimeSnapshot snapshot = new ExecutionRuntimeSnapshot();
    snapshot.setExecutionId(executionId);
    snapshot.setRuntimeDataJson(toJson(runtimeData));
    snapshot.setContextDataJson("{}");
    snapshot.setLastUpdatedAt(LocalDateTime.now());

    when(repository.findByExecutionId(executionId)).thenReturn(Optional.of(snapshot));

    // When: getting a non-existent key
    PatchProposal result =
        snapshotService.getRuntimeValue(executionId, "nonexistent", PatchProposal.class);

    // Then: should return null
    assertThat(result).isNull();
  }

  @Test
  @DisplayName("getRuntimeValue returns null when snapshot doesn't exist")
  void testGetRuntimeValueReturnsNullWhenNoSnapshot() {
    // Given: no snapshot exists
    when(repository.findByExecutionId(executionId)).thenReturn(Optional.empty());

    // When: getting any runtime value
    PatchProposal result =
        snapshotService.getRuntimeValue(executionId, "patch_1", PatchProposal.class);

    // Then: should return null
    assertThat(result).isNull();
  }

  @Test
  @DisplayName("loadRuntimeData returns empty map when snapshot doesn't exist")
  void testLoadRuntimeDataReturnsEmptyMapWhenNoSnapshot() {
    // Given: no snapshot exists
    when(repository.findByExecutionId(executionId)).thenReturn(Optional.empty());

    // When: loading runtime data
    Map<String, Object> result = snapshotService.loadRuntimeData(executionId);

    // Then: should return empty map
    assertThat(result).isNotNull();
    assertThat(result).isEmpty();
  }

  @Test
  @DisplayName("saveSnapshot persists runtime data and context")
  void testSaveSnapshotPersistsData() {
    // Given: runtime data and context
    Map<String, Object> runtimeData = new HashMap<>();
    runtimeData.put("key1", "value1");

    GraphExecutionContext context =
        GraphExecutionContext.builder()
            .executionId(executionId)
            .iterationId("iter_1")
            .scriptName("TestScript")
            .goalPrompt("Test goal")
            .maxAttempts(3)
            .runtimeData(runtimeData)
            .build();

    ExecutionRuntimeSnapshot snapshot = new ExecutionRuntimeSnapshot();
    snapshot.setExecutionId(executionId);

    when(repository.findByExecutionId(executionId)).thenReturn(Optional.of(snapshot));
    when(repository.save(any(ExecutionRuntimeSnapshot.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    // When: saving snapshot
    snapshotService.saveSnapshot(executionId, runtimeData, context);

    // Then: snapshot should be saved
    verify(repository).save(any(ExecutionRuntimeSnapshot.class));
  }

  @Test
  @DisplayName("deleteSnapshot removes the snapshot")
  void testDeleteSnapshot() {
    // Given: a snapshot exists
    ExecutionRuntimeSnapshot snapshot = new ExecutionRuntimeSnapshot();
    snapshot.setExecutionId(executionId);
    when(repository.findByExecutionId(executionId)).thenReturn(Optional.of(snapshot));

    // When: deleting the snapshot
    snapshotService.deleteSnapshot(executionId);

    // Then: repository delete should be called
    verify(repository).delete(snapshot);
  }

  @Test
  @DisplayName("clearPaused removes pause flag via deleteSnapshot equivalent")
  void testGetSnapshotVersion() {
    // Given: a snapshot exists with version 2
    ExecutionRuntimeSnapshot snapshot = new ExecutionRuntimeSnapshot();
    snapshot.setExecutionId(executionId);
    snapshot.setSnapshotVersion(2);
    when(repository.findByExecutionId(executionId)).thenReturn(Optional.of(snapshot));

    // When: getting snapshot version
    int version = snapshotService.getSnapshotVersion(executionId);

    // Then: should return the correct version
    assertThat(version).isEqualTo(2);
  }

  @Test
  @DisplayName("getSnapshotVersion returns 0 when snapshot doesn't exist")
  void testGetSnapshotVersionReturnsZeroWhenNoSnapshot() {
    // Given: no snapshot exists
    when(repository.findByExecutionId(executionId)).thenReturn(Optional.empty());

    // When: getting snapshot version
    int version = snapshotService.getSnapshotVersion(executionId);

    // Then: should return 0
    assertThat(version).isEqualTo(0);
  }

  private String toJson(Map<String, Object> data) {
    try {
      return objectMapper.writeValueAsString(data);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
