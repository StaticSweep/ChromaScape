package com.chromascape.web.replay.worker;

import com.chromascape.web.replay.api.dto.ReplayMode;
import com.chromascape.web.replay.api.dto.ReplayOutcome;
import com.chromascape.web.replay.api.dto.ReplayRunPhase;
import com.chromascape.web.replay.api.dto.ReplayRunState;
import com.chromascape.web.replay.artifact.ReplayArtifactService;
import com.chromascape.web.replay.artifact.entity.ArtifactRef;
import com.chromascape.web.replay.config.ReplayProperties;
import com.chromascape.web.replay.error.ReplayErrorCode;
import com.chromascape.web.replay.error.ReplayException;
import com.chromascape.web.replay.kernel.KernelProtocolCodec.KernelMessage;
import com.chromascape.web.replay.kernel.XrspsKernelProcess;
import com.chromascape.web.replay.manifest.entity.ScenarioManifest;
import com.chromascape.web.replay.manifest.repository.ScenarioManifestRepository;
import com.chromascape.web.replay.mismatch.MismatchEngine;
import com.chromascape.web.replay.projection.CanonicalProjectionService;
import com.chromascape.web.replay.report.ReplayReportService;
import com.chromascape.web.replay.run.ReplayRunService;
import com.chromascape.web.replay.run.ReplayRunService.ClaimResult;
import com.chromascape.web.replay.run.entity.ReplayCheckpoint;
import com.chromascape.web.replay.run.entity.ReplayEvent;
import com.chromascape.web.replay.run.entity.ReplayRun;
import com.chromascape.web.replay.run.entity.StateHashSample;
import com.chromascape.web.replay.run.repository.ReplayCheckpointRepository;
import com.chromascape.web.replay.run.repository.ReplayEventRepository;
import com.chromascape.web.replay.run.repository.ReplayRunRepository;
import com.chromascape.web.replay.run.repository.StateHashSampleRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Polls for queued causal-simulation runs and executes them inside isolated
 * xRSPS kernel processes.
 *
 * Execution contract: a run only reaches SUCCEEDED after every manifest
 * action has executed, observations/hashes/events/checkpoints are persisted,
 * mismatches evaluated, all artifacts finalized, and the immutable report
 * written. Any failure path produces a report with the corresponding outcome.
 */
@Component
@ConditionalOnProperty(prefix = "replay.worker", name = "enabled", havingValue = "true")
public class ReplayWorker {

    private static final Logger log = LoggerFactory.getLogger(ReplayWorker.class);
    private static final String WORKER_ID = "worker-" + UUID.randomUUID();
    private static final int STEP_MAX_TICKS = 10_000;
    private static final int CHECKPOINT_INTERVAL_ACTIONS = 25;

    private final ReplayRunRepository runRepository;
    private final ReplayRunService runService;
    private final ScenarioManifestRepository manifestRepository;
    private final ReplayEventRepository eventRepository;
    private final ReplayCheckpointRepository checkpointRepository;
    private final StateHashSampleRepository hashSampleRepository;
    private final ReplayArtifactService artifactService;
    private final MismatchEngine mismatchEngine;
    private final ReplayReportService reportService;
    private final CanonicalProjectionService projectionService;
    private final ReplayProperties properties;
    private final ObjectMapper mapper;

    private final ExecutorService executor;
    private final Semaphore concurrencySemaphore;
    /** Genesis chain anchor: SHA-256 of the domain-separated literal. */
    private final String genesisChainHash;

    public ReplayWorker(ReplayRunRepository runRepository,
                        ReplayRunService runService,
                        ScenarioManifestRepository manifestRepository,
                        ReplayEventRepository eventRepository,
                        ReplayCheckpointRepository checkpointRepository,
                        StateHashSampleRepository hashSampleRepository,
                        ReplayArtifactService artifactService,
                        MismatchEngine mismatchEngine,
                        ReplayReportService reportService,
                        CanonicalProjectionService projectionService,
                        ReplayProperties properties,
                        ObjectMapper mapper) {
        this.runRepository = runRepository;
        this.runService = runService;
        this.manifestRepository = manifestRepository;
        this.eventRepository = eventRepository;
        this.checkpointRepository = checkpointRepository;
        this.hashSampleRepository = hashSampleRepository;
        this.artifactService = artifactService;
        this.mismatchEngine = mismatchEngine;
        this.reportService = reportService;
        this.projectionService = projectionService;
        this.properties = properties;
        this.mapper = mapper;
        this.executor = Executors.newFixedThreadPool(properties.getWorker().getConcurrency());
        this.concurrencySemaphore = new Semaphore(properties.getWorker().getConcurrency());
        this.genesisChainHash = projectionService.sha256Hex(
                "chromascape-replay-chain-v1".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Scheduled(fixedDelayString = "${replay.worker.poll-interval-ms:1000}")
    public void poll() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            List<UUID> candidates = runRepository.findClaimableRunIds(
                    ReplayMode.CAUSAL_SIMULATION.name(), properties.getWorker().getConcurrency() * 2);
            for (UUID runId : candidates) {
                if (!concurrencySemaphore.tryAcquire()) {
                    return;
                }
                executor.submit(() -> {
                    try {
                        executeOne(runId);
                    } catch (Exception e) {
                        log.error("Worker {} run {} crashed: {}", WORKER_ID, runId, e.getMessage(), e);
                    } finally {
                        concurrencySemaphore.release();
                    }
                });
            }
        } catch (Exception e) {
            log.warn("Worker poll error: {}", e.getMessage());
        }
    }

    /** Recovers runs whose worker lost its lease (crash / GC pause / network cut). */
    @Scheduled(fixedDelayString = "${replay.worker.poll-interval-ms:5000}")
    public void recoverExpiredLeases() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            LocalDateTime now = LocalDateTime.now();
            List<ReplayRun> active = runRepository.findAll().stream()
                    .filter(r -> r.getState() == ReplayRunState.CLAIMED
                            || r.getState() == ReplayRunState.STARTING
                            || r.getState() == ReplayRunState.RUNNING)
                    .toList();
            for (ReplayRun run : active) {
                runService.findExpiredActiveAttempt(run.getId(), now).ifPresent(attempt -> {
                    log.warn("Recovering run {}: attempt {} lease expired", run.getId(), attempt.getAttemptNo());
                    try {
                        ReplayRun current = runRepository.findById(run.getId()).orElse(null);
                        if (current == null || current.isTerminal() || current.getState() == ReplayRunState.CANCELLING) {
                            return;
                        }
                        runService.applyTransition(current, ReplayRunState.FAILED, null);
                        current.setOutcome(ReplayOutcome.INFRASTRUCTURE_FAILURE);
                        current.setTerminalReason("LEASE_EXPIRED");
                        runRepository.save(current);
                    } catch (Exception e) {
                        log.debug("Recovery transition failed for run {}: {}", run.getId(), e.getMessage());
                    }
                });
            }
        } catch (Exception e) {
            log.debug("Lease recovery skipped: {}", e.getMessage());
        }
    }

    private void executeOne(UUID runId) {
        ClaimResult claim = runService.claimAtomically(runId, WORKER_ID);
        if (claim == null) {
            return; // another instance won the claim race
        }
        ReplayRun run = claim.run();
        XrspsKernelProcess kernel = null;
        ExecutionState es = new ExecutionState(genesisChainHash);
        try {
            runService.transition(run.getId(), ReplayRunState.STARTING, ReplayRunPhase.START_KERNEL);

            ScenarioManifest manifestEntity = manifestRepository.findById(run.getManifestId())
                    .orElseThrow(() -> new IllegalStateException("Manifest not found"));
            JsonNode manifestDoc = requireManifestObject(manifestEntity);
            JsonNode actions = manifestDoc.path("actions");
            if (!actions.isArray()) {
                throw new IllegalStateException("Manifest actions missing");
            }

            kernel = new XrspsKernelProcess(properties.getXrsps(), mapper);
            kernel.start();

            runService.transition(run.getId(), ReplayRunState.RUNNING, ReplayRunPhase.RESET);

            // Capability handshake with version verification.
            KernelMessage caps = kernel.send("capabilities", null);
            verifyCapabilityResponse(caps, run);

            // reset with manifest as a JSON object (never an escaped string).
            ObjectNode resetParams = mapper.createObjectNode();
            resetParams.set("manifest", manifestDoc);
            KernelMessage resetMsg = kernel.send("reset", resetParams);
            assertOk(resetMsg, "reset");
            long expectedTick = resetMsg.result().path("result").path("initialTick").asLong(-1);
            if (expectedTick < 0) {
                throw new IllegalStateException("reset returned no initialTick");
            }
            persistEvents(run, claim.attempt(), resetMsg.result().path("result").path("events"), es);

            runService.transition(run.getId(), ReplayRunState.RUNNING, ReplayRunPhase.EXECUTE);

            // ── action iteration ──
            for (int i = 0; i < actions.size(); i++) {
                ensureNotCancelled(run.getId());
                ensureWithinDeadline(run);
                runService.heartbeatOrThrow(run.getId(), claim.attempt(), WORKER_ID);

                JsonNode action = actions.get(i);
                ObjectNode stepParams = mapper.createObjectNode();
                stepParams.put("expectedTick", expectedTick);
                stepParams.set("action", action);
                stepParams.put("maxTicks", STEP_MAX_TICKS);
                KernelMessage stepMsg = kernel.send("step", stepParams);
                assertStepOk(stepMsg, i);
                JsonNode result = stepMsg.result().path("result");

                persistEvents(run, claim.attempt(), result.path("events"), es);
                JsonNode observation = result.path("observation");

                String stateHash = projectionService.stateHash(observation);
                es.lastStateHash = stateHash;
                String actionDigest = projectionService.actionDigest(action);
                es.chainHash = projectionService.chainHash(es.chainHash, actionDigest, stateHash);
                long tick = result.path("terminalTick").asLong(expectedTick);
                hashSampleRepository.save(new StateHashSample(
                        run.getId(), tick, 0, 1, stateHash, es.chainHash));
                expectedTick = tick;
                es.executedActions++;

                boolean isLast = i == actions.size() - 1;
                if ((i + 1) % CHECKPOINT_INTERVAL_ACTIONS == 0 || isLast) {
                    takeCheckpoint(kernel, run, claim.attempt(), tick, i, es);
                    ensureNotCancelled(run.getId());
                    ensureWithinDeadline(run);
                }
            }

            // Final observation + hashes already tracked via steps.
            runService.transition(run.getId(), ReplayRunState.RUNNING, ReplayRunPhase.COMPARE);

            // Evaluate expectations recorded in the manifest document.
            JsonNode expectations = manifestDoc.path("expectations");
            KernelMessage finalObserve = kernel.send("observe", null);
            assertOk(finalObserve, "observe");
            JsonNode actualObservation = finalObserve.result().path("result").path("observation");
            var comparison = mismatchEngine.compareAtCheckpoint(
                    run.getId(), claim.attempt().getId(),
                    expectations, actualObservation, expectedTick, 0, run.getStopPolicy());

            // Trace + manifest artifacts.
            KernelMessage traceMsg = kernel.send("trace", mapper.createObjectNode().put("afterOrdinal", -1));
            assertOk(traceMsg, "trace");
            byte[] traceBytes = mapper.writeValueAsBytes(traceMsg.result().path("result"));
            ArtifactRef traceRef = artifactService.stageArtifact(run.getId(),
                    com.chromascape.web.replay.api.dto.ArtifactKind.TRACE, traceBytes,
                    "application/json");
            es.artifactChecksums.add(traceRef.getChecksum());
            byte[] manifestBytes = mapper.writeValueAsBytes(manifestDoc);
            ArtifactRef manifestRef = artifactService.stageArtifact(run.getId(),
                    com.chromascape.web.replay.api.dto.ArtifactKind.MANIFEST, manifestBytes,
                    "application/json");
            es.artifactChecksums.add(manifestRef.getChecksum());

            // Outcome resolution: hard mismatch stops the run as FAILED (PASSED otherwise).
            boolean hardStop = comparison.shouldStop();
            if (hardStop && comparison.hardCount() > 0) {
                for (var m : comparison.recorded()) {
                    if (!es.recordedMismatchIds.contains(m.getId())) {
                        es.recordedMismatchIds.add(m.getId());
                    }
                }
            }

            runService.transition(run.getId(), ReplayRunState.RUNNING, ReplayRunPhase.FINALIZE_ARTIFACTS);

            // Report BEFORE artifact finalization: its checksum lists the staged
            // artifact content addresses. The REPORT artifact is added after.
            ReplayOutcome outcome = hardStop ? ReplayOutcome.FAILED : ReplayOutcome.PASSED;
            var report = reportService.finalizeReport(
                    refreshed(run), claim.attempt(), outcome,
                    hardStop ? "MISMATCH_HARD_STOP" : null,
                    es.executedActions, es.checkpointCount,
                    es.lastStateHash, es.chainHash, es.artifactChecksums,
                    java.sql.Timestamp.valueOf(run.getStartedAt() != null ? run.getStartedAt()
                            : run.getCreatedAt()).toInstant());
            byte[] reportBytes = report.getReportJson().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            ArtifactRef reportRef = artifactService.stageArtifact(run.getId(),
                    com.chromascape.web.replay.api.dto.ArtifactKind.REPORT, reportBytes,
                    "application/json");

            // Only now publish everything; success is unreachable before this returns.
            artifactService.finalizeArtifacts(run.getId());

            ReplayRun current = runRepository.findById(run.getId()).orElseThrow();
            if (current.getState() == ReplayRunState.CANCELLING) {
                runService.applyTransition(current, ReplayRunState.CANCELLED, ReplayRunPhase.FINALIZE_REPORT);
            } else {
                runService.applyTransition(current, ReplayRunState.SUCCEEDED, ReplayRunPhase.FINALIZE_REPORT);
                current.setOutcome(outcome);
            }
            runRepository.save(current);
            log.info("Run {} finished: {} actions={} checkpoints={}",
                    run.getId(), current.getState(), es.executedActions, es.checkpointCount);

        } catch (CancellationRequested e) {
            handleTerminal(run, claim.attempt(), es, ReplayOutcome.CANCELLED, "CANCELLED",
                    ReplayRunState.CANCELLED, kernel);
        } catch (ReplayException e) {
            if (e.getErrorCode() == ReplayErrorCode.ATTEMPT_STALE_FENCE) {
                log.warn("Run {} lost its fence; abandoning without further writes", run.getId());
                safeClose(kernel);
                return;
            }
            handleTerminal(run, claim.attempt(), es, ReplayOutcome.FAILED,
                    "REPLAY_ERROR:" + e.getErrorCode().name(),
                    ReplayRunState.FAILED, kernel);
        } catch (Exception e) {
            log.error("Run {} failed: {}", run.getId(), e.getMessage());
            handleTerminal(run, claim.attempt(), es, ReplayOutcome.INFRASTRUCTURE_FAILURE,
                    "INFRASTRUCTURE_FAILURE", ReplayRunState.FAILED, kernel);
        } finally {
            safeClose(kernel);
        }
    }

    // ── helpers ──

    private void takeCheckpoint(XrspsKernelProcess kernel, ReplayRun run,
                                com.chromascape.web.replay.run.entity.ReplayAttempt attempt,
                                long tick, int actionIndex, ExecutionState es) {
        KernelMessage snapMsg = kernel.send("snapshot", null);
        assertOk(snapMsg, "snapshot");
        JsonNode snapshot = snapMsg.result().path("result").path("snapshot");
        byte[] snapBytes = canonicalBytes(snapshot);
        ArtifactRef ref = artifactService.stageArtifact(run.getId(),
                com.chromascape.web.replay.api.dto.ArtifactKind.SNAPSHOT, snapBytes, "application/json");
        es.artifactChecksums.add(ref.getChecksum());

        ReplayCheckpoint checkpoint = new ReplayCheckpoint(
                UUID.randomUUID(), run.getId(), attempt.getId(), tick,
                snapshot.path("checksum").asText(null),
                es.lastStateHash != null ? es.lastStateHash : "",
                es.chainHash, "default");
        checkpoint.setActionIndex(actionIndex + 1);
        checkpoint.setSnapshotArtifactId(ref.getChecksum());
        checkpointRepository.save(checkpoint);
        es.checkpointCount++;
    }

    private void persistEvents(ReplayRun run, com.chromascape.web.replay.run.entity.ReplayAttempt attempt,
                               JsonNode eventsNode, ExecutionState es) {
        if (eventsNode == null || !eventsNode.isArray()) {
            return;
        }
        List<ReplayEvent> batch = new ArrayList<>();
        int maxBatch = Math.max(1, properties.getWorker().getMaxEventBatch());
        for (JsonNode ev : eventsNode) {
            long ordinal = ev.path("ordinal").asLong(-1);
            if (ordinal <= es.lastPersistedOrdinal) {
                continue;
            }
            ReplayEvent event = new ReplayEvent();
            event.setRunId(run.getId());
            event.setOrdinal(ordinal);
            event.setAttemptId(attempt.getId());
            event.setTick(ev.path("tick").asLong(0));
            event.setSequence(ev.path("sequence").asInt(0));
            event.setEventType(ev.path("eventType").asText("UNKNOWN"));
            event.setCategory(ev.path("category").asText("DIAGNOSTIC"));
            event.setPayload(ev.has("payload") && !ev.get("payload").isNull()
                    ? ev.get("payload") : null);
            event.setCreatedAt(LocalDateTime.now());
            batch.add(event);
            if (batch.size() >= maxBatch) {
                eventRepository.saveAll(batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            eventRepository.saveAll(batch);
        }
        eventsNode.forEach(ev -> {
            long ordinal = ev.path("ordinal").asLong(-1);
            if (ordinal > es.lastPersistedOrdinal) {
                es.lastPersistedOrdinal = ordinal;
            }
        });
    }

    private void verifyCapabilityResponse(KernelMessage caps, ReplayRun run) {
        assertOk(caps, "capabilities");
        JsonNode capability = caps.result().path("result").path("capabilities");
        if (capability.isMissingNode()) {
            capability = caps.result().path("result");
        }
        if (!capability.path("modes").path("causalSimulation").asBoolean(false)) {
            throw new ReplayException(ReplayErrorCode.XRSPS_CAPABILITY_UNAVAILABLE,
                    org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, false,
                    "Kernel does not offer causal simulation", null, null, null);
        }
        JsonNode xrsps = capability.path("xrsps");
        run.setXrspsAdapterVersion(xrsps.path("adapterVersion").asText(null));
        run.setXrspsBuildCommit(xrsps.path("buildCommit").asText(null));
        run.setCacheRevision(xrsps.path("cacheRevision").asText(null));
        run.setContentRevision(xrsps.path("contentRevision").asText(null));
        runRepository.save(run);
    }

    private void assertOk(KernelMessage msg, String op) {
        if (msg.isError()) {
            String code = msg.error() != null ? msg.error().path("code").asText("INTERNAL_ERROR") : "INTERNAL_ERROR";
            String message = msg.error() != null ? msg.error().path("message").asText("") : "";
            throw new ReplayException(mapErrorCode(code),
                    org.springframework.http.HttpStatus.BAD_GATEWAY, false,
                    "Kernel " + op + " failed (" + code + ")", null, message, null);
        }
    }

    private void assertStepOk(KernelMessage msg, int actionIndex) {
        assertOk(msg, "step[" + actionIndex + "]");
    }

    private static ReplayErrorCode mapErrorCode(String code) {
        return switch (code) {
            case "TICK_MISMATCH" -> ReplayErrorCode.TICK_MISMATCH;
            case "CHECKSUM_MISMATCH" -> ReplayErrorCode.CHECKSUM_MISMATCH;
            case "SCHEMA_VERSION_UNSUPPORTED" -> ReplayErrorCode.SCHEMA_VERSION_UNSUPPORTED;
            case "ACTION_UNSUPPORTED" -> ReplayErrorCode.ACTION_UNSUPPORTED;
            case "ACTION_TARGET_NOT_FOUND", "ACTION_AMBIGUOUS" -> ReplayErrorCode.ACTION_TARGET_UNRESOLVED;
            default -> ReplayErrorCode.INTERNAL_ERROR;
        };
    }

    private JsonNode requireManifestObject(ScenarioManifest entity) {
        JsonNode json = entity.getManifestJson();
        if (json == null || !json.isObject()) {
            throw new IllegalStateException("Stored manifest JSON is not an object");
        }
        return json;
    }

    private void ensureNotCancelled(UUID runId) {
        ReplayRun current = runRepository.findById(runId).orElseThrow();
        if (current.getState() == ReplayRunState.CANCELLING || current.getState() == ReplayRunState.CANCELLED) {
            throw new CancellationRequested();
        }
    }

    private void ensureWithinDeadline(ReplayRun run) {
        LocalDateTime started = run.getStartedAt() != null ? run.getStartedAt() : run.getCreatedAt();
        if (started != null && LocalDateTime.now().isAfter(
                started.plusSeconds(properties.getWorker().getRunTimeoutSeconds()))) {
            throw new ReplayException(ReplayErrorCode.RUN_STATE_INVALID,
                    org.springframework.http.HttpStatus.REQUEST_TIMEOUT, false,
                    "Run exceeded its deadline", null, null, null);
        }
    }

    private ReplayRun refreshed(ReplayRun stale) {
        return runRepository.findById(stale.getId()).orElse(stale);
    }

    private void handleTerminal(ReplayRun run, com.chromascape.web.replay.run.entity.ReplayAttempt attempt,
                                ExecutionState es, ReplayOutcome outcome, String reason,
                                ReplayRunState targetState, XrspsKernelProcess kernel) {
        safeClose(kernel);
        try {
            ReplayRun current = runRepository.findById(run.getId()).orElse(null);
            if (current == null || current.isTerminal()) {
                return;
            }
            if (current.getState() == ReplayRunState.CANCELLING) {
                runService.applyTransition(current, ReplayRunState.CANCELLED, null);
            } else {
                switch (current.getState()) {
                    case RUNNING -> runService.applyTransition(current, targetState, ReplayRunPhase.FINALIZE_REPORT);
                    case CLAIMED, STARTING -> runService.applyTransition(current, ReplayRunState.FAILED, null);
                    default -> { /* QUEUED or other: leave untouched */ }
                }
            }
            current.setOutcome(outcome);
            if (targetState == ReplayRunState.CANCELLED) {
                current.setState(ReplayRunState.CANCELLED);
            }
            runRepository.save(current);
            // Best-effort report so every terminal run has evidence.
            try {
                reportService.finalizeReport(refreshed(current), attempt, outcome, reason,
                        es.executedActions, es.checkpointCount,
                        es.lastStateHash, es.chainHash, es.artifactChecksums,
                        java.sql.Timestamp.valueOf((current.getStartedAt() != null
                                ? current.getStartedAt() : current.getCreatedAt())).toInstant());
            } catch (Exception reportFailure) {
                log.warn("Post-failure report could not be written for run {}: {}",
                        run.getId(), reportFailure.getMessage());
            }
        } catch (Exception e) {
            log.warn("Failed to terminalize run {}: {}", run.getId(), e.getMessage());
        }
    }

    private void safeClose(XrspsKernelProcess kernel) {
        if (kernel != null) {
            kernel.close();
        }
    }

    private byte[] canonicalBytes(JsonNode node) {
        return projectionService.canonicalJson(node)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Mutable per-execution accumulator. */
    private static class ExecutionState {
        String chainHash;
        String lastStateHash;
        long lastPersistedOrdinal = -1;
        int executedActions;
        int checkpointCount;
        final List<String> artifactChecksums = new ArrayList<>();
        final List<UUID> recordedMismatchIds = new ArrayList<>();

        ExecutionState(String genesisChainHash) {
            this.chainHash = genesisChainHash;
        }
    }

    private static final class CancellationRequested extends RuntimeException {}
}
