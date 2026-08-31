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
            verifyCapabilityResponse(caps, run, manifestDoc);

            // reset with manifest as a JSON object (never an escaped string).
            ObjectNode resetParams = mapper.createObjectNode();
            resetParams.set("manifest", manifestDoc);
            KernelMessage resetMsg = kernel.send("reset", resetParams);
            assertOk(resetMsg, "reset");
            long expectedTick = resetMsg.result().path("initialTick").asLong(-1);
            if (expectedTick < 0) {
                throw new IllegalStateException("reset returned no initialTick");
            }
            persistEvents(run, claim.attempt(), resetMsg.result().path("events"), es);

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
                // Bind every kernel step to the source semantic action. The viewer trace uses
                // this index to distinguish recorded evidence from simulated consequences and
                // to jump back to the exact curation input that produced a frame.
                stepParams.put("sourceActionIndex", i);
                stepParams.put("maxTicks", STEP_MAX_TICKS);
                KernelMessage stepMsg = kernel.send("step", stepParams);
                assertStepOk(stepMsg, i);
                JsonNode result = stepMsg.result();

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
            JsonNode actualObservation = finalObserve.result().path("observation");
            var comparison = mismatchEngine.compareAtCheckpoint(
                    run.getId(), claim.attempt().getId(),
                    expectations, actualObservation, expectedTick, 0, run.getStopPolicy());

            // Trace + manifest artifacts.
            KernelMessage traceMsg = kernel.send("trace", mapper.createObjectNode().put("afterOrdinal", -1));
            assertOk(traceMsg, "trace");
            ObjectNode traceDocument = normalizeCanonicalTraceDocument(
                    traceMsg.result(), manifestDoc.path("manifestVersion").asInt(1), run.isViewerRequested());
            byte[] traceBytes = canonicalBytes(traceDocument);
            ArtifactRef traceRef = artifactService.stageArtifact(run.getId(),
                    com.chromascape.web.replay.api.dto.ArtifactKind.TRACE, traceBytes,
                    "application/json");
            es.artifactChecksums.add(traceRef.getChecksum());
            run.setViewerTraceChecksum(traceRef.getChecksum());
            run.setViewerTraceFrameCount((long) traceDocument.path("frames").size());
            runRepository.save(run);
            byte[] manifestBytes = mapper.writeValueAsBytes(manifestDoc);
            ArtifactRef manifestRef = artifactService.stageArtifact(run.getId(),
                    com.chromascape.web.replay.api.dto.ArtifactKind.MANIFEST, manifestBytes,
                    "application/json");
            es.artifactChecksums.add(manifestRef.getChecksum());

            // A hard mismatch makes the durable run FAILED even when RUN_TO_END was selected.
            // The stop policy controls execution short-circuiting; it must not turn a failed
            // comparison into a successful terminal state.
            boolean hardMismatch = comparison.hardCount() > 0;
            boolean hardStop = comparison.shouldStop();
            if (hardMismatch) {
                for (var m : comparison.recorded()) {
                    if (!es.recordedMismatchIds.contains(m.getId())) {
                        es.recordedMismatchIds.add(m.getId());
                    }
                }
            }

            runService.transition(run.getId(), ReplayRunState.RUNNING, ReplayRunPhase.FINALIZE_ARTIFACTS);

            // Report BEFORE artifact finalization: its checksum lists the staged
            // artifact content addresses. The REPORT artifact is added after.
            ReplayOutcome outcome = hardMismatch ? ReplayOutcome.FAILED : ReplayOutcome.PASSED;
            var report = reportService.finalizeReport(
                    refreshed(run), claim.attempt(), outcome,
                    hardMismatch ? (hardStop ? "MISMATCH_HARD_STOP" : "MISMATCH_HARD") : null,
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
                runService.applyTransition(
                        current,
                        hardMismatch ? ReplayRunState.FAILED : ReplayRunState.SUCCEEDED,
                        ReplayRunPhase.FINALIZE_REPORT);
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
        JsonNode snapshot = snapMsg.result().path("snapshot");
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

    /**
     * Validates the complete capability handshake before changing any run state or metadata.
     * The manifest is the source of truth for all immutable pins; the run carries the same pins
     * as a durable copy and is checked as well so a partially-created/legacy row cannot weaken
     * the boundary.
     */
    private void verifyCapabilityResponse(KernelMessage caps, ReplayRun run, JsonNode manifest) {
        assertOk(caps, "capabilities");
        JsonNode result = caps.result();
        JsonNode capability = result != null ? result.path("capabilities") : null;
        if (capability == null || !capability.isObject()) {
            throw capabilityError(
                    ReplayErrorCode.XRSPS_CAPABILITY_UNAVAILABLE,
                    "The xRSPS capability response has no capability object",
                    "Upgrade the worker to the ChromaScape replay capability contract");
        }

        requireBoolean(capability, "controlAllowed", false, ReplayErrorCode.XRSPS_CAPABILITY_UNAVAILABLE,
                "The xRSPS worker must be read-only");
        JsonNode modes = capability.path("modes");
        if (!modes.isObject()) {
            throw capabilityError(
                    ReplayErrorCode.XRSPS_CAPABILITY_UNAVAILABLE,
                    "The xRSPS capability response has no modes object",
                    "Upgrade the worker to the ChromaScape replay capability contract");
        }
        requireBoolean(modes, "policyReplay", false, ReplayErrorCode.XRSPS_CAPABILITY_UNAVAILABLE,
                "Policy replay is not permitted by this worker contract");
        requireBoolean(modes, "causalSimulation", true, ReplayErrorCode.XRSPS_CAPABILITY_UNAVAILABLE,
                "The worker must explicitly advertise causal simulation");
        requireBoolean(capability, "openRuneAvailable", false, ReplayErrorCode.XRSPS_CAPABILITY_UNAVAILABLE,
                "OpenRune fallback is not permitted for xRSPS replay");

        int manifestVersion = requireManifestVersion(manifest);
        JsonNode scenarioVersions = capability.path("scenarioSchemaVersions");
        if (!scenarioVersions.isArray() || !containsInteger(scenarioVersions, manifestVersion)) {
            throw capabilityError(
                    ReplayErrorCode.SCHEMA_VERSION_UNSUPPORTED,
                    "The worker does not advertise scenario manifest version " + manifestVersion,
                    "Use a worker that supports the immutable scenario manifest version");
        }

        JsonNode manifestRuntime = manifest.path("runtime");
        if (!manifestRuntime.isObject()) {
            throw capabilityError(
                    ReplayErrorCode.XRSPS_VERSION_INCOMPATIBLE,
                    "The immutable manifest has no runtime identity",
                    "Recompile the replay with complete xRSPS runtime pins");
        }
        JsonNode xrsps = capability.path("xrsps");
        if (!xrsps.isObject()) {
            throw capabilityError(
                    ReplayErrorCode.XRSPS_VERSION_INCOMPATIBLE,
                    "The xRSPS capability response has no runtime identity",
                    "Start a worker with adapter, build, cache, and content identities");
        }
        String adapterVersion = requirePinnedIdentity(manifestRuntime, xrsps, run,
                "xrspsAdapterVersion", "adapterVersion");
        String buildCommit = requirePinnedIdentity(manifestRuntime, xrsps, run,
                "xrspsBuildCommit", "buildCommit");
        String cacheRevision = requirePinnedIdentity(manifestRuntime, xrsps, run,
                "cacheRevision", "cacheRevision");
        String contentRevision = requirePinnedIdentity(manifestRuntime, xrsps, run,
                "contentRevision", "contentRevision");

        String fidelity = requireText(capability, "fidelity", ReplayErrorCode.XRSPS_CAPABILITY_UNAVAILABLE,
                "The worker must explicitly declare replay fidelity");
        if (!"SYNTHETIC_CONTRACT".equals(fidelity) && !"XRSPS_AUTHORITATIVE".equals(fidelity)) {
            throw capabilityError(
                    ReplayErrorCode.XRSPS_CAPABILITY_UNAVAILABLE,
                    "The worker declared unsupported replay fidelity",
                    "Use fidelity=SYNTHETIC_CONTRACT or fidelity=XRSPS_AUTHORITATIVE");
        }
        // Manifest v1 predates the explicit fidelity field. Its durable run row is the
        // compatibility source of truth and is backfilled to SYNTHETIC_CONTRACT by V25.
        // v2 must carry the field in the immutable document so it cannot be weakened by a
        // partially-created or legacy run row.
        String durableFidelity = run.getFidelity();
        if (durableFidelity == null || durableFidelity.isBlank()) {
            durableFidelity = "SYNTHETIC_CONTRACT";
        }
        String manifestFidelity = manifestVersion >= 2
                ? requireText(manifest, "fidelity", ReplayErrorCode.XRSPS_CAPABILITY_UNAVAILABLE,
                "The immutable manifest must declare replay fidelity")
                : durableFidelity;
        if (!fidelity.equals(manifestFidelity) || !fidelity.equals(durableFidelity)) {
            throw capabilityError(
                    ReplayErrorCode.XRSPS_VERSION_INCOMPATIBLE,
                    "The worker fidelity does not match the immutable replay pins",
                    "Run the replay with the worker identity used to compile the manifest");
        }
        JsonNode certifiableNode = capability.get("certifiable");
        if (certifiableNode == null || !certifiableNode.isBoolean()) {
            throw capabilityError(
                    ReplayErrorCode.XRSPS_CAPABILITY_UNAVAILABLE,
                    "The worker must explicitly declare whether it is certifiable",
                    "Upgrade the worker to the replay capability contract");
        }
        boolean certifiable = certifiableNode.booleanValue();
        if ("CERTIFIED".equals(run.getConversionQuality())
                && (!certifiable || !"XRSPS_AUTHORITATIVE".equals(fidelity))) {
            throw capabilityError(
                    ReplayErrorCode.XRSPS_VERSION_INCOMPATIBLE,
                    "The worker is not authoritative for certified replay",
                    "Use a worker advertising fidelity=XRSPS_AUTHORITATIVE and certifiable=true");
        }

        boolean traceRequired = manifestVersion >= 2 || run.isViewerRequested();
        if (traceRequired) {
            String viewerTraceSchema = requireText(
                    capability, "viewerTraceSchema", ReplayErrorCode.SCHEMA_VERSION_UNSUPPORTED,
                    "The worker must declare its observer trace schema");
            if (!"xrsps-replay-trace/v1".equals(viewerTraceSchema)) {
                throw capabilityError(
                        ReplayErrorCode.SCHEMA_VERSION_UNSUPPORTED,
                        "The worker does not support xRSPS replay trace schema v1",
                        "Upgrade the worker to xrsps-replay-trace/v1");
            }
        }

        JsonNode supported = capability.get("supportedActions");
        if (supported == null || !supported.isArray()
                || !allTextual(supported)) {
            throw capabilityError(
                    ReplayErrorCode.ACTION_UNSUPPORTED,
                    "The worker capability has no valid supportedActions declaration",
                    "Upgrade the worker to declare every supported semantic action");
        }
        JsonNode actions = manifest.path("actions");
        if (!actions.isArray()) {
            throw ReplayException.invalid(
                    ReplayErrorCode.ACTION_SEQUENCE_INVALID,
                    "The immutable manifest has no action array");
        }
        for (JsonNode action : actions) {
            String type = action.isObject() && action.path("type").isTextual()
                    ? action.path("type").textValue() : null;
            if (type == null || !containsText(supported, type)) {
                throw ReplayException.invalid(
                        ReplayErrorCode.ACTION_UNSUPPORTED,
                        type == null
                                ? "The immutable manifest contains an action without a type"
                                : "Worker capability does not declare manifest action " + type);
            }
        }

        // This is deliberately the first mutation in this method. All capability and pin
        // validation above must succeed before the durable run is touched.
        run.setXrspsAdapterVersion(adapterVersion);
        run.setXrspsBuildCommit(buildCommit);
        run.setCacheRevision(cacheRevision);
        run.setContentRevision(contentRevision);
        runRepository.save(run);
    }

    private ReplayException capabilityError(ReplayErrorCode code, String message, String remediation) {
        return new ReplayException(
                code,
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                false,
                message,
                remediation);
    }

    private static int requireManifestVersion(JsonNode manifest) {
        JsonNode version = manifest == null ? null : manifest.get("manifestVersion");
        if (version == null || !version.isIntegralNumber() || (version.intValue() != 1 && version.intValue() != 2)) {
            throw ReplayException.invalid(
                    ReplayErrorCode.SCHEMA_VERSION_UNSUPPORTED,
                    "The immutable manifest has an unsupported manifestVersion");
        }
        return version.intValue();
    }

    private static boolean containsInteger(JsonNode values, int expected) {
        for (JsonNode value : values) {
            if (value.isIntegralNumber() && value.intValue() == expected) return true;
        }
        return false;
    }

    private static boolean containsText(JsonNode values, String expected) {
        for (JsonNode value : values) {
            if (value.isTextual() && expected.equals(value.textValue())) return true;
        }
        return false;
    }

    private static boolean allTextual(JsonNode values) {
        for (JsonNode value : values) {
            if (!value.isTextual()) return false;
        }
        return true;
    }

    private static void requireBoolean(JsonNode object, String field, boolean expected,
                                       ReplayErrorCode code, String message) {
        JsonNode value = object.get(field);
        if (value == null || !value.isBoolean() || value.booleanValue() != expected) {
            throw ReplayException.invalid(code, message);
        }
    }

    private static String requireText(JsonNode object, String field, ReplayErrorCode code, String message) {
        JsonNode value = object == null ? null : object.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw ReplayException.invalid(code, message);
        }
        return value.textValue();
    }

    private static String requirePinnedIdentity(JsonNode manifestRuntime, JsonNode xrsps, ReplayRun run,
                                                String manifestField, String capabilityField) {
        String manifestValue = requireText(manifestRuntime, manifestField,
                ReplayErrorCode.XRSPS_VERSION_INCOMPATIBLE,
                "The immutable manifest has an incomplete runtime identity");
        String actualValue = requireText(xrsps, capabilityField,
                ReplayErrorCode.XRSPS_VERSION_INCOMPATIBLE,
                "The worker has an incomplete runtime identity");
        String runValue = switch (manifestField) {
            case "xrspsAdapterVersion" -> run.getXrspsAdapterVersion();
            case "xrspsBuildCommit" -> run.getXrspsBuildCommit();
            case "cacheRevision" -> run.getCacheRevision();
            case "contentRevision" -> run.getContentRevision();
            default -> null;
        };
        if (!manifestValue.equals(actualValue) || !manifestValue.equals(runValue)) {
            throw new ReplayException(
                    ReplayErrorCode.XRSPS_VERSION_INCOMPATIBLE,
                    org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                    false,
                    "The worker runtime identity does not match the immutable manifest",
                    "Run the replay with the pinned adapter, build, cache, and content revisions");
        }
        return actualValue;
    }

    /** Normalize the adapter response to the canonical viewer contract before hashing it. */
    private ObjectNode normalizeCanonicalTraceDocument(JsonNode raw, int manifestVersion,
                                                        boolean viewerRequested) {
        boolean strict = manifestVersion >= 2 || viewerRequested;
        if (raw == null || !raw.isObject()) {
            throw traceCorrupt("xRSPS kernel returned an invalid trace document");
        }

        // The current TypeScript worker returns the visual document at the response root and
        // keeps the diagnostic event page beside it. Older adapters may wrap the document in a
        // `trace` object or expose a legacy `trace` array. Read both forms, but always emit one
        // canonical ChromaScape document so hashes and viewer pagination are stable.
        JsonNode source = raw.path("trace").isObject() ? raw.path("trace") : raw;
        String schema = source.path("schema").asText(null);
        if (schema == null && !strict) {
            schema = "xrsps-replay-trace/v1";
        }
        if (!"xrsps-replay-trace/v1".equals(schema)) {
            throw traceCorrupt("xRSPS kernel returned an unsupported trace schema");
        }

        JsonNode rawFrames = source.path("frames");
        if (!rawFrames.isArray() && raw.path("trace").isArray()) {
            rawFrames = raw.path("trace");
        }
        if (!rawFrames.isArray()) {
            throw traceCorrupt("xRSPS kernel returned no trace frames");
        }

        ObjectNode trace = mapper.createObjectNode();
        trace.put("schema", "xrsps-replay-trace/v1");
        trace.put("viewerTraceSchema", "xrsps-replay-trace/v1");
        ArrayNode frames = trace.putArray("frames");
        ArrayNode ordinals = trace.putArray("ordinals");
        for (int index = 0; index < rawFrames.size(); index++) {
            ObjectNode frame = normalizeTraceFrame(rawFrames.get(index), source, index, strict);
            frames.add(frame);
            ordinals.add(frame.path("ordinal").asLong(index));
        }

        JsonNode rawEvents = raw.path("events").isArray() ? raw.path("events") : source.path("events");
        trace.set("events", normalizeTraceEvents(rawEvents, strict));
        copyTraceMetadata(source, trace, frames);
        return trace;
    }

    private ObjectNode normalizeTraceFrame(JsonNode rawFrame, JsonNode document, int index,
                                           boolean strict) {
        if (rawFrame == null || !rawFrame.isObject()) {
            throw traceCorrupt("xRSPS kernel returned a non-object replay frame");
        }
        JsonNode frameObservation = rawFrame.path("observation");
        long tick = integralOr(rawFrame.get("tick"), -1L);
        if (tick < 0) {
            throw traceCorrupt("xRSPS kernel returned a replay frame without a valid tick");
        }
        ObjectNode observation = normalizeTraceObservation(rawFrame, frameObservation, document, tick);
        long ordinal = integralOr(rawFrame.get("ordinal"),
                integralOr(rawFrame.get("frameOrdinal"), index));
        if (ordinal < 0 || (strict && (!rawFrame.has("ordinal") && !rawFrame.has("frameOrdinal")))) {
            throw traceCorrupt("xRSPS kernel returned a replay frame without a valid ordinal");
        }

        ObjectNode frame = mapper.createObjectNode();
        frame.put("frameVersion", rawFrame.path("frameVersion").asInt(1));
        frame.put("ordinal", ordinal);
        frame.put("tick", tick);
        frame.set("observation", observation);
        frame.set("player", observation.path("player").deepCopy());
        frame.set("npcs", observation.path("npcs").deepCopy());
        frame.set("objects", observation.path("objects").deepCopy());
        frame.set("taskState", observation.path("taskState").deepCopy());
        JsonNode events = rawFrame.path("events").isArray()
                ? rawFrame.path("events") : rawFrame.path("semanticEffects");
        frame.set("events", normalizeTraceEvents(events, strict));

        JsonNode stateHash = rawFrame.get("stateHash");
        if (stateHash == null || !stateHash.isTextual() || stateHash.asText().isBlank()) {
            if (strict) {
                throw traceCorrupt("xRSPS kernel returned a replay frame without a state hash");
            }
            frame.put("stateHash", projectionService.stateHash(observation));
        } else {
            frame.set("stateHash", stateHash.deepCopy());
        }
        JsonNode actionDigest = rawFrame.has("actionDigest")
                ? rawFrame.get("actionDigest") : rawFrame.get("actionHash");
        if (actionDigest == null) {
            frame.putNull("actionDigest");
        } else if (actionDigest.isNull() || actionDigest.isTextual()) {
            frame.set("actionDigest", actionDigest.deepCopy());
        } else {
            throw traceCorrupt("xRSPS kernel returned an invalid action digest");
        }
        JsonNode chainHash = rawFrame.get("chainHash");
        if (chainHash == null || !chainHash.isTextual() || chainHash.asText().isBlank()) {
            throw traceCorrupt("xRSPS kernel returned a replay frame without a chain hash");
        }
        frame.set("chainHash", chainHash.deepCopy());
        JsonNode sourceActionIndex = rawFrame.get("sourceActionIndex");
        if (sourceActionIndex == null || sourceActionIndex.isNull()) {
            frame.putNull("sourceActionIndex");
        } else if (sourceActionIndex.isIntegralNumber() && sourceActionIndex.asLong() >= 0) {
            frame.set("sourceActionIndex", sourceActionIndex.deepCopy());
        } else {
            throw traceCorrupt("xRSPS kernel returned an invalid source action index");
        }

        // Keep the render-only part of the xRSPS frame. These fields are optional in the canonical
        // contract, but retaining them lets the observer render movement/effects without exposing
        // the kernel's mutable world graph.
        copyRenderField(rawFrame, frame, "playerRenderState", observation.path("player"));
        copyRenderField(rawFrame, frame, "movementPath", defaultPositionPath(observation));
        copyRenderField(rawFrame, frame, "animations", defaultAnimations(observation));
        copyRenderField(rawFrame, frame, "graphics", mapper.createArrayNode());
        copyRenderField(rawFrame, frame, "projectiles", mapper.createArrayNode());
        copyRenderField(rawFrame, frame, "interfaces", mapper.createArrayNode());
        copyRenderField(rawFrame, frame, "dialogue", mapper.createArrayNode());
        copyRenderField(rawFrame, frame, "inventory", observation.path("player").path("inventory"));
        copyRenderField(rawFrame, frame, "equipment", observation.path("player").path("equipment"));
        copyRenderField(rawFrame, frame, "skills", observation.path("player").path("skills"));
        copyRenderField(rawFrame, frame, "vars", observation.path("taskState"));
        copyRenderField(rawFrame, frame, "semanticEffects", frame.path("events"));
        if (rawFrame.has("previousChainHash")) {
            frame.set("previousChainHash", rawFrame.get("previousChainHash").deepCopy());
        }
        return frame;
    }

    private ObjectNode normalizeTraceObservation(JsonNode rawFrame, JsonNode rawObservation,
                                                  JsonNode document, long tick) {
        ObjectNode observation = rawObservation != null && rawObservation.isObject()
                ? (ObjectNode) rawObservation.deepCopy() : mapper.createObjectNode();
        observation.put("projectionVersion", observation.path("projectionVersion")
                .asInt(document.path("projectionVersion").asInt(1)));
        observation.put("tick", tick);
        JsonNode player = observation.path("player").isObject()
                ? observation.path("player")
                : rawFrame.path("player").isObject() ? rawFrame.path("player")
                : rawFrame.path("playerRenderState");
        if (!player.isObject() || !player.path("position").isObject()) {
            throw traceCorrupt("xRSPS kernel returned a replay frame without player position");
        }
        observation.set("player", player.deepCopy());

        JsonNode npcs = observation.path("npcs").isArray()
                ? observation.path("npcs")
                : rawFrame.path("npcs").isArray() ? rawFrame.path("npcs")
                : rawFrame.path("entityDeltas").path("npcs");
        JsonNode objects = observation.path("objects").isArray()
                ? observation.path("objects")
                : rawFrame.path("objects").isArray() ? rawFrame.path("objects")
                : rawFrame.path("entityDeltas").path("objects");
        JsonNode taskState = observation.path("taskState").isObject()
                ? observation.path("taskState")
                : rawFrame.path("taskState").isObject() ? rawFrame.path("taskState")
                : rawFrame.path("vars");
        observation.set("npcs", npcs.isArray() ? npcs.deepCopy() : mapper.createArrayNode());
        observation.set("objects", objects.isArray() ? objects.deepCopy() : mapper.createArrayNode());
        observation.set("taskState", taskState.isObject() ? taskState.deepCopy() : mapper.createObjectNode());
        return observation;
    }

    private ArrayNode normalizeTraceEvents(JsonNode rawEvents, boolean strict) {
        ArrayNode events = mapper.createArrayNode();
        if (rawEvents == null || !rawEvents.isArray()) return events;
        for (JsonNode rawEvent : rawEvents) {
            if (rawEvent == null || !rawEvent.isObject()) {
                if (strict) throw traceCorrupt("xRSPS kernel returned a non-object trace event");
                continue;
            }
            ObjectNode event = (ObjectNode) rawEvent.deepCopy();
            if (!event.has("eventVersion")) event.put("eventVersion", 1);
            if (strict && (!event.path("ordinal").isIntegralNumber()
                    || !event.path("tick").isIntegralNumber()
                    || !event.path("sequence").isIntegralNumber()
                    || !event.path("eventType").isTextual()
                    || !event.path("category").isTextual())) {
                throw traceCorrupt("xRSPS kernel returned an invalid trace event");
            }
            events.add(event);
        }
        return events;
    }

    private void copyTraceMetadata(JsonNode source, ObjectNode trace, ArrayNode frames) {
        copyIfPresent(source, trace, "traceVersion");
        copyIfPresent(source, trace, "projectionVersion");
        copyIfPresent(source, trace, "hashVersion");
        copyIfPresent(source, trace, "genesisChainHash");
        copyIfPresent(source, trace, "finalChainHash");
        copyIfPresent(source, trace, "finalStateHash");
        trace.put("frameCount", frames.size());
    }

    private void copyIfPresent(JsonNode source, ObjectNode target, String field) {
        if (source.has(field)) target.set(field, source.get(field).deepCopy());
    }

    private void copyRenderField(JsonNode source, ObjectNode target, String field, JsonNode fallback) {
        JsonNode value = source.get(field);
        target.set(field, value == null || value.isNull() ? fallback.deepCopy() : value.deepCopy());
    }

    private ArrayNode defaultPositionPath(JsonNode observation) {
        ArrayNode path = mapper.createArrayNode();
        JsonNode position = observation.path("player").path("position");
        if (position.isObject()) path.add(position.deepCopy());
        return path;
    }

    private ObjectNode defaultAnimations(JsonNode observation) {
        ObjectNode animations = mapper.createObjectNode();
        JsonNode animation = observation.path("player").get("animation");
        if (animation == null || animation.isNull()) animations.putNull("player");
        else animations.set("player", animation.deepCopy());
        return animations;
    }

    private static long integralOr(JsonNode node, long fallback) {
        return node != null && node.isIntegralNumber() ? node.asLong() : fallback;
    }

    private ReplayException traceCorrupt(String message) {
        return new ReplayException(
                ReplayErrorCode.TRACE_CORRUPT,
                org.springframework.http.HttpStatus.BAD_GATEWAY,
                false,
                message,
                "Upgrade the worker to emit xRSPS replay trace schema v1");
    }

    private void assertOk(KernelMessage msg, String op) {
        if (msg == null || msg.isError() || msg.result() == null) {
            JsonNode error = msg == null ? null : msg.error();
            String code = error != null ? error.path("code").asText("INTERNAL_ERROR") : "INTERNAL_ERROR";
            String message = error != null ? error.path("message").asText("") : "";
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
