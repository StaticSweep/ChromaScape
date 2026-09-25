package com.chromascape.web.debug.buildergoal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chromascape.web.debug.buildergoal.service.Micro6OnlineClientRegistryService.TargetClient;
import com.chromascape.web.debug.buildergoal.service.Micro6TargetRelaunchService.RelaunchProgress;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class BuilderGoalLaunchOperationServiceTest {

  private final BuilderGoalLaunchOperationService service = new BuilderGoalLaunchOperationService();

  private static TargetClient targetClient() {
    return new TargetClient(
        "client-1",
        "bigGreenMeat",
        "hash-1",
        "http://127.0.0.1:8098",
        "0.0.0.0:8098",
        "micro6",
        "accountbuilder_builderpro",
        "IDLE",
        null,
        Instant.now(),
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  @Test
  void createOrReuse_deduplicatesSimultaneousSameAccountRequests() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(8);
    try {
      CountDownLatch start = new CountDownLatch(1);
      java.util.Set<String> operationIds = java.util.concurrent.ConcurrentHashMap.newKeySet();
      java.util.List<Future<BuilderGoalLaunchOperationService.CreateResult>> futures =
          new java.util.ArrayList<>();
      for (int i = 0; i < 16; i++) {
        futures.add(
            pool.submit(
                () -> {
                  start.await();
                  return service.createOrReuse(
                      "hash-1", "hash-1", "bigGreenMeat", "machine-1", 360);
                }));
      }
      start.countDown();
      boolean deduplicated = false;
      for (Future<BuilderGoalLaunchOperationService.CreateResult> future : futures) {
        BuilderGoalLaunchOperationService.CreateResult result = future.get(10, TimeUnit.SECONDS);
        operationIds.add(result.operation().getOperationId());
        deduplicated |= result.deduplicated();
      }
      assertThat(operationIds).hasSize(1);
      assertThat(deduplicated).isTrue();
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void createOrReuse_differentAccountsCreateDistinctOperations() {
    BuilderGoalLaunchOperationService.CreateResult first =
        service.createOrReuse("hash-1", "hash-1", "account-one", "machine-1", 360);
    BuilderGoalLaunchOperationService.CreateResult second =
        service.createOrReuse("hash-2", "hash-2", "account-two", "machine-1", 360);

    assertThat(first.operation().getOperationId())
        .isNotEqualTo(second.operation().getOperationId());
    assertThat(first.deduplicated()).isFalse();
    assertThat(second.deduplicated()).isFalse();
  }

  @Test
  void progressSnapshotRemainsConsistentUnderConcurrentProgressAndReads() throws Exception {
    BuilderGoalLaunchOperationService.CreateResult created =
        service.createOrReuse("hash-1", "hash-1", "bigGreenMeat", "machine-1", 360);
    String operationId = created.operation().getOperationId();

    ExecutorService pool = Executors.newFixedThreadPool(8);
    try {
      CountDownLatch start = new CountDownLatch(1);
      java.util.List<Future<?>> futures = new java.util.ArrayList<>();
      for (int i = 0; i < 8; i++) {
        final int phaseIndex = i;
        futures.add(
            pool.submit(
                () -> {
                  try {
                    start.await();
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                  }
                  for (int n = 0; n < 50; n++) {
                    service.updateProgress(
                        operationId,
                        new RelaunchProgress(
                            phaseIndex % 2 == 0
                                ? Micro6TargetRelaunchService.PHASE_WAITING_FOR_PORT
                                : Micro6TargetRelaunchService.PHASE_WAITING_FOR_HEALTH,
                            "phase-" + phaseIndex,
                            "http://127.0.0.1:8098",
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            ":runelite-client:run",
                            null,
                            false,
                            null,
                            null,
                            null,
                            null));
                    Map<String, Object> view = service.toResponse(operationId, false);
                    String status = (String) view.get("status");
                    // The snapshot must never mix terminal and non-terminal state.
                    assertThat(view.get("terminal")).isEqualTo(false);
                    assertThat(status)
                        .isIn(
                            BuilderGoalLaunchOperationService.STATUS_QUEUED,
                            BuilderGoalLaunchOperationService.STATUS_WAITING_FOR_PORT,
                            BuilderGoalLaunchOperationService.STATUS_WAITING_FOR_HEALTH);
                  }
                }));
      }
      start.countDown();
      for (Future<?> future : futures) {
        future.get(10, TimeUnit.SECONDS);
      }
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void readyWinsOverWatchdogRace() throws Exception {
    BuilderGoalLaunchOperationService.CreateResult created =
        service.createOrReuse("hash-1", "hash-1", "bigGreenMeat", "machine-1", 360);
    String operationId = created.operation().getOperationId();

    AtomicReference<Boolean> watchdogWon = new AtomicReference<>(false);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      CountDownLatch go = new CountDownLatch(1);
      Future<?> watchdog =
          pool.submit(
              () -> {
                try {
                  go.await();
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  throw new RuntimeException(e);
                }
                watchdogWon.set(service.markWatchdogFailed(operationId));
              });
      Future<?> ready =
          pool.submit(
              () -> {
                try {
                  go.await();
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  throw new RuntimeException(e);
                }
                service.markReady(operationId, targetClient(), Map.of(), false);
              });
      go.countDown();
      watchdog.get(10, TimeUnit.SECONDS);
      ready.get(10, TimeUnit.SECONDS);
    } finally {
      pool.shutdownNow();
    }

    Map<String, Object> view = service.toResponse(operationId, false);
    assertThat(view.get("terminal")).isEqualTo(true);
    boolean watchdogWonTheRace = watchdogWon.get();
    if (watchdogWonTheRace) {
      assertThat(view.get("status")).isEqualTo(BuilderGoalLaunchOperationService.STATUS_FAILED);
      assertThat(view.get("success")).isEqualTo(false);
    } else {
      assertThat(view.get("status")).isEqualTo(BuilderGoalLaunchOperationService.STATUS_READY);
      assertThat(view.get("success")).isEqualTo(true);
    }
  }

  @Test
  void failedWinsOverLateReady() {
    BuilderGoalLaunchOperationService.CreateResult created =
        service.createOrReuse("hash-1", "hash-1", "bigGreenMeat", "machine-1", 360);
    String operationId = created.operation().getOperationId();

    service.markFailed(
        operationId, "process exited", Micro6TargetRelaunchService.PHASE_PROCESS_EXITED, null);
    boolean lateReady = service.markReady(operationId, targetClient(), Map.of(), false);

    assertThat(lateReady).isFalse();
    Map<String, Object> view = service.toResponse(operationId, false);
    assertThat(view.get("status")).isEqualTo(BuilderGoalLaunchOperationService.STATUS_FAILED);
    assertThat(view.get("success")).isEqualTo(false);
    assertThat(view.get("terminal")).isEqualTo(true);
  }

  @Test
  void retainedTerminalOperationRemainsQueryableByCompletedAt() {
    BuilderGoalLaunchOperationService.CreateResult created =
        service.createOrReuse("hash-1", "hash-1", "bigGreenMeat", "machine-1", 360);
    String operationId = created.operation().getOperationId();
    service.markReady(operationId, targetClient(), Map.of(), false);

    // A new request for the same account must not delete the retained terminal operation.
    BuilderGoalLaunchOperationService.CreateResult second =
        service.createOrReuse("hash-1", "hash-1", "bigGreenMeat", "machine-1", 360);
    assertThat(second.deduplicated()).isFalse();
    assertThat(second.operation().getOperationId()).isNotEqualTo(operationId);

    Optional<BuilderGoalLaunchOperationService.LaunchOperation> retained = service.get(operationId);
    assertThat(retained).isPresent();
    assertThat(retained.get().isTerminal()).isTrue();
    assertThat(retained.get().getCompletedAt()).isNotNull();
  }

  @Test
  void terminalOperationExpiresFromCompletedAt() throws Exception {
    BuilderGoalLaunchOperationService expired = new BuilderGoalLaunchOperationService(10, 60_000);
    BuilderGoalLaunchOperationService.CreateResult created =
        expired.createOrReuse("hash-1", "hash-1", "bigGreenMeat", "machine-1", 360);
    String operationId = created.operation().getOperationId();
    expired.markReady(operationId, targetClient(), Map.of(), false);

    // Retention is based on completedAt; a short retention expires the retained terminal op.
    Thread.sleep(60);
    assertThat(expired.get(operationId)).isEmpty();
  }

  @Test
  void rejectionCleanupTransitionsToFailedAndClearsActiveMapping() {
    BuilderGoalLaunchOperationService.CreateResult created =
        service.createOrReuse("hash-1", "hash-1", "bigGreenMeat", "machine-1", 360);
    String operationId = created.operation().getOperationId();

    service.clearActiveAndTransition(operationId, "Launch executor is at capacity");

    Map<String, Object> view = service.toResponse(operationId, false);
    assertThat(view.get("status")).isEqualTo(BuilderGoalLaunchOperationService.STATUS_FAILED);
    assertThat(view.get("terminal")).isEqualTo(true);

    // The account slot is free: a new request creates a fresh operation.
    BuilderGoalLaunchOperationService.CreateResult second =
        service.createOrReuse("hash-1", "hash-1", "bigGreenMeat", "machine-1", 360);
    assertThat(second.deduplicated()).isFalse();
    assertThat(second.operation().getOperationId()).isNotEqualTo(operationId);
  }

  @Test
  void preserveFailureDetailsEnrichesFailedSnapshotWithoutChangingTerminalState() {
    BuilderGoalLaunchOperationService.CreateResult created =
        service.createOrReuse("hash-1", "hash-1", "bigGreenMeat", "machine-1", 360);
    String operationId = created.operation().getOperationId();
    service.markFailed(
        operationId, "process exited", Micro6TargetRelaunchService.PHASE_PROCESS_EXITED, null);

    service.preserveFailureDetails(operationId, "PROCESS_EXITED", "process exited detail");

    Map<String, Object> view = service.toResponse(operationId, false);
    assertThat(view.get("failureKind")).isEqualTo("PROCESS_EXITED");
    assertThat(view.get("failureDetail")).isEqualTo("process exited detail");
    assertThat(view.get("status")).isEqualTo(BuilderGoalLaunchOperationService.STATUS_FAILED);
    assertThat(view.get("terminal")).isEqualTo(true);
    assertThat(view.get("success")).isEqualTo(false);
  }

  @Test
  void toResponseAndProgressSanitizeCredentialBearingText() {
    BuilderGoalLaunchOperationService.CreateResult created =
        service.createOrReuse("hash-1", "hash-1", "bigGreenMeat", "machine-1", 360);
    String operationId = created.operation().getOperationId();

    service.updateProgress(
        operationId,
        new RelaunchProgress(
            Micro6TargetRelaunchService.PHASE_WAITING_FOR_DISPATCH,
            "failed with --profile-email=user@example.com:supersecret",
            "http://127.0.0.1:8098",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            ":runelite-client:run",
            "--profile-email=user@example.com:supersecret",
            false,
            "C:\\micro6\\micro6-recovery-launch.log",
            null,
            null,
            null));

    Map<String, Object> view = service.toResponse(operationId, false);
    assertThat(String.valueOf(view.get("message"))).doesNotContain("supersecret");
    assertThat(String.valueOf(view.get("readinessReason"))).doesNotContain("supersecret");
    assertThat(String.valueOf(view.get("launchArgsPreview"))).doesNotContain("supersecret");
    assertThat(String.valueOf(view.get("launchArgsPreview"))).contains("********");
  }

  @Test
  void modifyingOriginalMetadataAfterMarkReadyDoesNotChangeStoredSnapshot() {
    BuilderGoalLaunchOperationService.CreateResult created =
        service.createOrReuse("hash-1", "hash-1", "bigGreenMeat", "machine-1", 360);
    String operationId = created.operation().getOperationId();

    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("readiness_reason", "dispatch-ready");
    Map<String, Object> nested = new LinkedHashMap<>();
    nested.put("secret", "password=original-secret");
    metadata.put("nested", nested);

    service.markReady(operationId, targetClient(), metadata, false);

    // Mutating the caller's original map must not change the frozen snapshot.
    metadata.put("readiness_reason", "MUTATED");
    nested.put("secret", "password=mutated-secret");
    metadata.put("nested", new LinkedHashMap<>(Map.of("new", "value")));

    Map<String, Object> view = service.toResponse(operationId, false);
    assertThat(String.valueOf(view.get("readinessReason"))).isEqualTo("dispatch-ready");
    assertThat(String.valueOf(view.get("target"))).doesNotContain("MUTATED");
    assertThat(String.valueOf(view.get("target"))).doesNotContain("mutated-secret");
  }

  @Test
  void modifyingMapReturnedFromToResponseCannotAffectStoredSnapshotOrLaterResponses() {
    BuilderGoalLaunchOperationService.CreateResult created =
        service.createOrReuse("hash-1", "hash-1", "bigGreenMeat", "machine-1", 360);
    String operationId = created.operation().getOperationId();
    service.markReady(
        operationId, targetClient(), Map.of("readiness_reason", "dispatch-ready"), false);

    Map<String, Object> first = service.toResponse(operationId, false);
    first.put("status", "TAMPERED");

    @SuppressWarnings("unchecked")
    Map<String, Object> target = (Map<String, Object>) first.get("target");
    assertThatThrownBy(() -> target.put("tampered", true))
        .isInstanceOf(UnsupportedOperationException.class);

    Map<String, Object> second = service.toResponse(operationId, false);
    assertThat(second.get("status")).isEqualTo(BuilderGoalLaunchOperationService.STATUS_READY);
    @SuppressWarnings("unchecked")
    Map<String, Object> secondTarget = (Map<String, Object>) second.get("target");
    assertThat(secondTarget.containsKey("tampered")).isFalse();
  }

  @Test
  void snapshotTargetMetadataIsRecursivelyImmutable() {
    BuilderGoalLaunchOperationService.CreateResult created =
        service.createOrReuse("hash-1", "hash-1", "bigGreenMeat", "machine-1", 360);
    String operationId = created.operation().getOperationId();

    List<Object> listValue = new ArrayList<>();
    listValue.add("password=secret-list");
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("nested_map", new LinkedHashMap<>(Map.of("inner", "pwd: secret-map")));
    metadata.put("nested_list", listValue);

    service.markReady(operationId, targetClient(), metadata, false);

    BuilderGoalLaunchOperationService.LaunchOperation operation =
        service.get(operationId).orElseThrow();
    Map<String, Object> stored = operation.snapshot().targetMetadata();

    assertThatThrownBy(() -> stored.put("new", "value"))
        .isInstanceOf(UnsupportedOperationException.class);
    @SuppressWarnings("unchecked")
    Map<String, Object> nestedMap = (Map<String, Object>) stored.get("nested_map");
    assertThatThrownBy(() -> nestedMap.put("new", "value"))
        .isInstanceOf(UnsupportedOperationException.class);
    @SuppressWarnings("unchecked")
    List<Object> nestedList = (List<Object>) stored.get("nested_list");
    assertThatThrownBy(() -> nestedList.add("new"))
        .isInstanceOf(UnsupportedOperationException.class);

    assertThat(String.valueOf(nestedMap.get("inner"))).doesNotContain("secret-map");
    assertThat(String.valueOf(nestedList.get(0))).doesNotContain("secret-list");
  }

  @Test
  void everyTargetStringFieldPassesThroughSanitization() {
    BuilderGoalLaunchOperationService.CreateResult created =
        service.createOrReuse("hash-1", "hash-1", "bigGreenMeat", "machine-1", 360);
    String operationId = created.operation().getOperationId();

    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("startup_state", "accountbuilder_ready");
    metadata.put("login_state", "logged_in");
    metadata.put("dispatch_hold_reason", "waiting_for_login password=leaked");
    metadata.put("readiness_reason", "ready with credential: 'token leaked'");
    metadata.put("launcher_log_file", "C:\\micro6\\log password=\"spaced leaked\"");
    metadata.put("launch_args_preview", "--profile-email=user@example.com:leaked");
    metadata.put("local_executor_base_url", "http://127.0.0.1:8098");
    metadata.put("requested_account_name", "bigGreenMeat");

    service.markReady(operationId, targetClient(), metadata, false);

    Map<String, Object> view = service.toResponse(operationId, false);
    String target = String.valueOf(view.get("target"));
    assertThat(target).doesNotContain("leaked");
    assertThat(target).doesNotContain("user@example.com");
    assertThat(target).contains("********");
  }

  @Test
  void toResponseSanitizesOperationIdentityStrings() {
    BuilderGoalLaunchOperationService.CreateResult created =
        service.createOrReuse(
            "accountId--profile-email=user@example.com:identity-secret",
            "hash-1",
            "name password=identity-name-secret",
            "machineUuid pwd: identity-machine-secret",
            360);
    String operationId = created.operation().getOperationId();

    Map<String, Object> view = service.toResponse(operationId, false);
    assertThat(String.valueOf(view.get("accountId"))).doesNotContain("identity-secret");
    assertThat(String.valueOf(view.get("accountName"))).doesNotContain("identity-name-secret");
    assertThat(String.valueOf(view.get("launcherMachineUuid")))
        .doesNotContain("identity-machine-secret");
    assertThat(String.valueOf(view.get("statusUrl"))).doesNotContain("identity-secret");
  }

  @Test
  void markReadyFreezesSetsAndArraysInMetadataWithoutRetainingOriginalReferences() {
    BuilderGoalLaunchOperationService.CreateResult created =
        service.createOrReuse("hash-1", "hash-1", "bigGreenMeat", "machine-1", 360);
    String operationId = created.operation().getOperationId();

    List<Object> listValue = new ArrayList<>();
    listValue.add("password=secret-list");
    Set<String> setValue = new LinkedHashSet<>();
    setValue.add("pwd: secret-set");
    String[] arrayValue = {"pass=secret-array"};
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("nested_set", setValue);
    metadata.put("nested_array", arrayValue);
    metadata.put("nested_list", listValue);

    service.markReady(operationId, targetClient(), metadata, false);

    // Mutating every original input container cannot change the stored snapshot.
    setValue.add("pwd: mutated-set");
    arrayValue[0] = "pass=mutated-array";
    listValue.add("password=mutated-list");

    BuilderGoalLaunchOperationService.LaunchOperation operation =
        service.get(operationId).orElseThrow();
    Map<String, Object> stored = operation.snapshot().targetMetadata();
    @SuppressWarnings("unchecked")
    List<Object> storedSet = (List<Object>) stored.get("nested_set");
    assertThatThrownBy(() -> storedSet.add("new"))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThat(storedSet).hasSize(1);
    @SuppressWarnings("unchecked")
    List<Object> storedArray = (List<Object>) stored.get("nested_array");
    assertThat(storedArray).hasSize(1);
    assertThat(String.valueOf(storedArray.get(0))).doesNotContain("mutated-array");
    @SuppressWarnings("unchecked")
    List<Object> storedList = (List<Object>) stored.get("nested_list");
    assertThat(storedList).hasSize(1);
    assertThat(String.valueOf(storedList.get(0))).doesNotContain("mutated-list");
  }

  @Test
  void markReadyRejectsUnsupportedMetadataLeafTypes() {
    BuilderGoalLaunchOperationService.CreateResult created =
        service.createOrReuse("hash-1", "hash-1", "bigGreenMeat", "machine-1", 360);
    String operationId = created.operation().getOperationId();

    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put(
        "bad_leaf",
        new Object() {
          @Override
          public String toString() {
            return "SUPERSECRET_CONTENT";
          }
        });

    assertThatThrownBy(() -> service.markReady(operationId, targetClient(), metadata, false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining("SUPERSECRET_CONTENT");
  }

  @Test
  void buildFailureUsesBuildFailedReadinessPhaseWithFailedTerminalStatus() {
    BuilderGoalLaunchOperationService.CreateResult created =
        service.createOrReuse("hash-1", "hash-1", "bigGreenMeat", "machine-1", 360);
    String operationId = created.operation().getOperationId();

    // BUILD_FAILED progress is a readiness phase only; it must not expose a second terminal
    // status with conflicting semantics.
    service.updateProgress(
        operationId,
        new Micro6TargetRelaunchService.RelaunchProgress(
            Micro6TargetRelaunchService.PHASE_BUILD_FAILED,
            "Micro6 build failed before the client could launch",
            "http://127.0.0.1:8098",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            ":client:runBuilderGoal",
            "--profile=********",
            false,
            "C:\\micro6\\micro6-recovery-launch.log",
            "GRADLE_TASK_FAILED",
            "build failed",
            "attempt-1"));
    Map<String, Object> active = service.toResponse(operationId, false);
    assertThat(active.get("status")).isNotEqualTo(BuilderGoalLaunchOperationService.STATUS_FAILED);
    assertThat(active.get("readinessPhase"))
        .isEqualTo(Micro6TargetRelaunchService.PHASE_BUILD_FAILED);
    assertThat(active.get("terminal")).isEqualTo(false);

    // The terminal transition is FAILED, preserving BUILD_FAILED as the terminal readiness phase.
    service.markFailed(
        operationId,
        "Micro6 build failed before the client could launch",
        Micro6TargetRelaunchService.PHASE_BUILD_FAILED,
        "C:\\micro6\\micro6-recovery-launch.log");
    service.preserveFailureDetails(operationId, "GRADLE_TIMEOUT", "build timed out", "attempt-1");
    Map<String, Object> terminal = service.toResponse(operationId, false);
    assertThat(terminal.get("status")).isEqualTo(BuilderGoalLaunchOperationService.STATUS_FAILED);
    assertThat(terminal.get("readinessPhase"))
        .isEqualTo(Micro6TargetRelaunchService.PHASE_BUILD_FAILED);
    assertThat(terminal.get("failureKind")).isEqualTo("GRADLE_TIMEOUT");
    assertThat(terminal.get("failureDetail")).isEqualTo("build timed out");
    assertThat(terminal.get("attemptId")).isEqualTo("attempt-1");
    assertThat(terminal.get("terminal")).isEqualTo(true);
    assertThat(terminal.get("success")).isEqualTo(false);
  }
}
