package com.chromascape.web.gameplay.orchestration;

import com.chromascape.web.gameplay.authority.HumanGameplayAuthorityService;
import com.chromascape.web.gameplay.authority.HumanGameplayAuthorityService.PendingApproval;
import com.chromascape.web.gameplay.bootstrap.BootstrapApprovalStore;
import com.chromascape.web.gameplay.bootstrap.BootstrapApprovalStore.ApprovalView;
import com.chromascape.web.gameplay.bootstrap.BootstrapEvidenceManifestService;
import com.chromascape.web.gameplay.bootstrap.BootstrapProposalAssembler;
import com.chromascape.web.gameplay.bootstrap.BootstrapProposalAssembler.AssembledPreview;
import com.chromascape.web.gameplay.bootstrap.BootstrapRecordingCoordinator;
import com.chromascape.web.gameplay.bootstrap.BootstrapRecordingCoordinator.RecordingView;
import com.chromascape.web.gameplay.contract.CanonicalGameplayJson;
import com.chromascape.web.gameplay.contract.GameplayContracts.ExecutionEnvelope;
import com.chromascape.web.gameplay.contract.GameplayContracts.HumanAuthority;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderCapabilityPreflight;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationException;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.CapabilitySnapshot;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.FailureCode;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.TaskCapability;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.Mode;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.OrchestrationRequest;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.OrchestrationView;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.ProviderOrchestrationCommand;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.SnapshotBindings;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.State;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationService;
import com.chromascape.web.gameplay.provider.runtime.GameplayProviderRuntime;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Durable SC-1498 operator boundary.
 *
 * <p>Proposals are assembled exclusively from persisted SC-1430 {@code DECIDED} turns; raw provider
 * output is rejected. Human approvals are durably stored before they can be bound or consumed.
 * Delivery is delegated exclusively to SC-1431 {@code AccountBuilderOrchestrationService} in {@code
 * BOOTSTRAP_RECORDING} mode via the Micro6 v2 gateway. This service owns no delivery state machine,
 * executor or recording authority beyond proposal assembly, approval durability and evidence
 * projection.
 */
@Service
public final class BootstrapGameplayOperatorService {
  private static final Logger LOG = LoggerFactory.getLogger(BootstrapGameplayOperatorService.class);

  private final CanonicalGameplayJson canonicalJson;
  private final HumanGameplayAuthorityService authorityService;
  private final BootstrapProposalAssembler assembler;
  private final BootstrapApprovalStore approvalStore;
  private final BootstrapRecordingCoordinator recordingCoordinator;
  private final BootstrapEvidenceManifestService manifestService;
  private final AccountBuilderOrchestrationService orchestrationService;
  private final AccountBuilderCapabilityPreflight capabilityPreflight;
  private final GameplayProviderRuntime providerRuntime;
  private final ObjectMapper objectMapper;
  private final Clock clock;
  private final boolean enabled;
  private final byte[] operatorKey;
  private final boolean recordingEnabled;
  private final boolean micro6V2Enabled;

  @Autowired
  public BootstrapGameplayOperatorService(
      CanonicalGameplayJson canonicalJson,
      HumanGameplayAuthorityService authorityService,
      BootstrapProposalAssembler assembler,
      BootstrapApprovalStore approvalStore,
      BootstrapRecordingCoordinator recordingCoordinator,
      BootstrapEvidenceManifestService manifestService,
      AccountBuilderOrchestrationService orchestrationService,
      AccountBuilderCapabilityPreflight capabilityPreflight,
      GameplayProviderRuntime providerRuntime,
      ObjectMapper objectMapper,
      @Value("${gameplay.bootstrap.operator-enabled:false}") boolean enabled,
      @Value("${gameplay.bootstrap.operator-key:}") String operatorKey,
      @Value("${gameplay.bootstrap.recording-enabled:false}") boolean recordingEnabled,
      @Value("${gameplay.bootstrap.micro6-v2-enabled:false}") boolean micro6V2Enabled) {
    this(
        canonicalJson,
        authorityService,
        assembler,
        approvalStore,
        recordingCoordinator,
        manifestService,
        orchestrationService,
        capabilityPreflight,
        providerRuntime,
        objectMapper,
        Clock.systemUTC(),
        enabled,
        operatorKey,
        recordingEnabled,
        micro6V2Enabled);
  }

  BootstrapGameplayOperatorService(
      CanonicalGameplayJson canonicalJson,
      HumanGameplayAuthorityService authorityService,
      BootstrapProposalAssembler assembler,
      BootstrapApprovalStore approvalStore,
      BootstrapRecordingCoordinator recordingCoordinator,
      BootstrapEvidenceManifestService manifestService,
      AccountBuilderOrchestrationService orchestrationService,
      AccountBuilderCapabilityPreflight capabilityPreflight,
      GameplayProviderRuntime providerRuntime,
      ObjectMapper objectMapper,
      Clock clock,
      boolean enabled,
      String operatorKey,
      boolean recordingEnabled,
      boolean micro6V2Enabled) {
    this.canonicalJson = canonicalJson;
    this.authorityService = authorityService;
    this.assembler = assembler;
    this.approvalStore = approvalStore;
    this.recordingCoordinator = recordingCoordinator;
    this.manifestService = manifestService;
    this.orchestrationService = orchestrationService;
    this.capabilityPreflight = capabilityPreflight;
    this.providerRuntime = providerRuntime;
    this.objectMapper = objectMapper;
    this.clock = clock;
    this.enabled = enabled;
    this.operatorKey =
        operatorKey == null ? new byte[0] : operatorKey.getBytes(StandardCharsets.UTF_8);
    this.recordingEnabled = recordingEnabled;
    this.micro6V2Enabled = micro6V2Enabled;
  }

  /**
   * Public availability. Discloses only whether the bootstrap operator surface is enabled; no
   * provider, account, runtime or key-configuration detail is ever returned to an unauthenticated
   * caller.
   */
  public Map<String, Object> publicAvailability() {
    return Map.of("enabled", enabled);
  }

  /**
   * Detailed availability. The presented key is authenticated with the same constant-time
   * comparison used by every mutation, so a wrong nonblank key is rejected with 401 before any
   * readiness detail is produced.
   */
  public Availability availability(String presentedKey) {
    requireOperator(presentedKey);
    List<String> models;
    boolean providerReady;
    try {
      var readiness = providerRuntime.readiness();
      providerReady = readiness.enabled();
      models =
          readiness.providers().entrySet().stream()
              .filter(entry -> entry.getValue().available())
              .map(entry -> entry.getKey().name() + ":" + entry.getValue().version())
              .sorted()
              .toList();
    } catch (RuntimeException exception) {
      providerReady = false;
      models = List.of();
    }
    var orchestrationReadiness = safeOrchestrationReadiness();
    return new Availability(
        enabled,
        operatorKey.length >= 32,
        providerReady,
        models,
        pendingApprovals(),
        blockingReasons(providerReady, orchestrationReadiness));
  }

  /** Durable pending-approval count for operator readiness; a store failure reports zero. */
  private int pendingApprovals() {
    try {
      long count = approvalStore.countPending();
      return count > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) count;
    } catch (RuntimeException exception) {
      LOG.warn("SC-1498 pending-approval count could not be read", exception);
      return 0;
    }
  }

  public ApprovalPreview preview(String presentedKey, PreviewRequest request) {
    requireOperator(presentedKey);
    requireEnabled();
    if (request == null) {
      throw error(HttpStatus.BAD_REQUEST, "PROPOSAL_INCOMPLETE", "A complete proposal is required");
    }
    AssembledPreview assembled =
        assembler.assemble(
            request.providerSessionId(),
            request.providerTurnId(),
            request.observation(),
            request.executionEnvelope(),
            request.snapshotBindings());
    return new ApprovalPreview(
        assembled.exactScope(),
        assembled.scopeDigest(),
        assembled.previewFingerprint(),
        assembled.orchestrationId(),
        assembled.requestId(),
        assembled.recordingId());
  }

  public PendingApprovalView approve(String presentedKey, ApprovalRequest request) {
    requireOperator(presentedKey);
    requireEnabled();
    if (request == null
        || request.providerSessionId() == null
        || request.snapshotBindings() == null
        || request.executionEnvelope() == null
        || request.observation() == null) {
      throw error(HttpStatus.BAD_REQUEST, "APPROVAL_INCOMPLETE", "A complete approval is required");
    }
    if (!request.humanConfirmed() || !"CONFIRM".equals(request.confirmation())) {
      throw error(
          HttpStatus.PRECONDITION_FAILED,
          "HUMAN_CONFIRMATION_REQUIRED",
          "The exact displayed scope requires explicit human confirmation");
    }
    if (request.approvedBy() == null
        || request.expiresAt() == null
        || request.scopeDigest() == null) {
      throw error(HttpStatus.BAD_REQUEST, "APPROVAL_INCOMPLETE", "A complete approval is required");
    }
    AssembledPreview assembled =
        assembler.assemble(
            request.providerSessionId(),
            request.providerTurnId(),
            request.observation(),
            request.executionEnvelope(),
            request.snapshotBindings());
    if (!constantTimeEquals(assembled.scopeDigest(), request.scopeDigest())) {
      throw error(
          HttpStatus.CONFLICT,
          "APPROVED_SCOPE_MISMATCH",
          "Approved digest does not match the current exact scope");
    }
    PendingApproval pending =
        authorityService.newDurablePendingApproval(
            request.approvedBy(), assembled.exactScope(), request.expiresAt());
    if (!constantTimeEquals(pending.scopeDigest(), assembled.scopeDigest())) {
      throw error(
          HttpStatus.CONFLICT,
          "APPROVED_SCOPE_MISMATCH",
          "Approved digest does not match the current exact scope");
    }
    BootstrapApprovalStore.Claim claim =
        approvalStore.create(
            request.providerSessionId(),
            request.providerTurnId(),
            assembled.orchestrationId(),
            assembled.requestId(),
            assembled.recordingId(),
            assembled.previewFingerprint(),
            assembled.scopeDigest(),
            request.approvedBy(),
            request.expiresAt());
    return new PendingApprovalView(
        claim.view().approvalId(),
        claim.view().approvedBy(),
        claim.view().expiresAt(),
        claim.view().scopeDigest(),
        claim.view().orchestrationId(),
        claim.created());
  }

  public OrchestrationView dispatch(String presentedKey, DispatchRequest request) {
    requireOperator(presentedKey);
    requireEnabled();
    if (request == null || request.approvalId() == null || request.approvalId().isBlank()) {
      throw error(HttpStatus.BAD_REQUEST, "DISPATCH_INCOMPLETE", "A complete dispatch is required");
    }
    ApprovalView approval = approvalStore.find(request.approvalId());
    if (!"APPROVED".equals(approval.state()) && !"BOUND".equals(approval.state())) {
      throw error(
          HttpStatus.CONFLICT,
          "APPROVAL_MISSING_OR_USED",
          "Pending approval is absent, expired, restarted, or already used");
    }
    if (request.executionEnvelope() == null
        || request.observation() == null
        || request.snapshotBindings() == null) {
      throw error(HttpStatus.BAD_REQUEST, "DISPATCH_INCOMPLETE", "A complete dispatch is required");
    }
    AssembledPreview assembled =
        assembler.assemble(
            approval.providerSessionId(),
            approval.providerTurnId(),
            request.observation(),
            request.executionEnvelope(),
            request.snapshotBindings());
    if (!constantTimeEquals(assembled.scopeDigest(), approval.scopeDigest())
        || !constantTimeEquals(assembled.previewFingerprint(), approval.requestFingerprint())
        || !assembled.orchestrationId().equals(approval.orchestrationId())
        || !assembled.requestId().equals(approval.requestId())) {
      throw error(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "STALE_BINDING",
          "Dispatch preview changed after human approval");
    }
    // The final envelope carries the durably bound authority id. Scope ignores authority id,
    // so the stored scope digest remains valid.
    ExecutionEnvelope boundEnvelope =
        withAuthority(assembled.envelope(), durableAuthorityId(approval));
    PendingApproval pending =
        new PendingApproval(
            durableAuthorityId(approval),
            approval.approvedBy(),
            approval.approvedAt(),
            approval.expiresAt(),
            approval.scopeDigest());
    HumanAuthority authority = authorityService.signDurableApproval(pending, boundEnvelope);
    String signatureDigest = authorityService.signatureDigest(authority);
    // Delivery is delegated exclusively to the hardened SC-1431 provider route. The service loads
    // the persisted provider decision and its provenance from the durable SC-1430 turn, resolves an
    // exact replay against the stored capability digest, and performs the Micro6 capability
    // preflight only for a genuinely new orchestration. Invalid decisions and exact replays
    // therefore never reach delivery; the operator never assembles a provider-shaped request and
    // the generic orchestrate() entry point rejects provider sources.
    ProviderOrchestrationCommand command =
        new ProviderOrchestrationCommand(
            approval.providerSessionId(),
            approval.providerTurnId(),
            approval.orchestrationId(),
            approval.requestId(),
            boundEnvelope.idempotencyKey(),
            Mode.BOOTSTRAP_RECORDING,
            boundEnvelope,
            authority,
            request.observation(),
            boundEnvelope.deadline());
    // Validate the persisted provider turn before consuming the one-use bind or starting the
    // durable recording projection, so a stale or malformed command leaves neither a bound
    // approval nor an orphan STARTED recording. This never probes Micro6.
    try {
      orchestrationService.validateProviderCommand(command);
    } catch (RuntimeException exception) {
      throw error(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "STALE_BINDING",
          "Persisted provider decision cannot back this dispatch");
    }
    // Read-only capability attestation before the one-use bind and before any recording row: an
    // absent, ineligible or mismatched task refuses with a stable code and leaves no durable trace.
    requireEligibleTask(boundEnvelope, capabilityPreflight.probe(boundEnvelope));
    ApprovalView bound =
        approvalStore.bind(approval.approvalId(), authority.authorityId(), signatureDigest);
    // SC-1498 ordering: the durable recording projection STARTS before SC-1431 delivery, once the
    // command is proven valid. If the process dies after this point, recovery finds the STARTED
    // projection and resumes reconciliation or finalization only — gameplay is never redispatched.
    RecordingView recording =
        recordingCoordinator.start(
            bound.orchestrationId(),
            bound.recordingId(),
            bound.providerSessionId(),
            bound.providerTurnId(),
            assembled.persisted().decisionDigest(),
            canonicalJson.digest(boundEnvelope),
            assembled.decision().provider().name(),
            assembled.decision().modelVersion(),
            null,
            null);
    AccountBuilderOrchestrationService.Submission submission;
    try {
      submission = orchestrationService.orchestrateProvider(command);
    } catch (OperatorException exception) {
      throw exception;
    } catch (AccountBuilderOrchestrationException exception) {
      throw error(
          HttpStatus.CONFLICT,
          exception.failureCode().name(),
          "SC-1431 orchestration could not be reconciled; use reconcile only");
    } catch (RuntimeException exception) {
      throw error(
          HttpStatus.CONFLICT,
          "DELIVERY_UNCERTAIN",
          "SC-1431 orchestration could not be reconciled; use reconcile only");
    }
    OrchestrationView view = submission.view();
    if (!view.state().provenTerminal()) {
      // SC-1498 one-run bound: wait read-only for a proven terminal receipt before projecting the
      // evidence. The wait never redispatches and stays reconciliation-only if the bound elapses.
      view =
          orchestrationService.reconcileUntilTerminal(
              command.orchestrationId(), boundEnvelope.deadline());
    }
    return finalizeIfTerminal(bound, recording, assembled, boundEnvelope, authority, view);
  }

  public OrchestrationView run(String presentedKey, String orchestrationId) {
    requireOperator(presentedKey);
    try {
      OrchestrationView view = orchestrationService.find(orchestrationId);
      return finalizeIfTerminalForExisting(orchestrationId, view);
    } catch (RuntimeException exception) {
      throw error(HttpStatus.NOT_FOUND, "RUN_NOT_FOUND", "Bootstrap run was not found");
    }
  }

  public OrchestrationView stop(String presentedKey, StopRequest request) {
    requireOperator(presentedKey);
    if (request == null || request.orchestrationId() == null || request.reason() == null) {
      throw error(HttpStatus.BAD_REQUEST, "STOP_INCOMPLETE", "A stop request is required");
    }
    try {
      OrchestrationView view =
          orchestrationService.stop(
              request.orchestrationId(), "safe-stop:" + request.reason(), false);
      return finalizeIfTerminalForExisting(request.orchestrationId(), view);
    } catch (RuntimeException exception) {
      throw error(HttpStatus.CONFLICT, "STOP_REJECTED", "A bounded stop requires active state");
    }
  }

  public OrchestrationView kill(String presentedKey, KillRequest request) {
    requireOperator(presentedKey);
    if (request == null
        || request.orchestrationId() == null
        || request.reason() == null
        || !request.confirmed()) {
      throw error(
          HttpStatus.PRECONDITION_FAILED,
          "KILL_CONFIRMATION_REQUIRED",
          "Kill-switch requires explicit confirmation");
    }
    try {
      OrchestrationView view =
          orchestrationService.stop(
              request.orchestrationId(), "kill-switch:" + request.reason(), true);
      return finalizeIfTerminalForExisting(request.orchestrationId(), view);
    } catch (RuntimeException exception) {
      throw error(HttpStatus.CONFLICT, "STOP_REJECTED", "Kill-switch requires active state");
    }
  }

  public OrchestrationView reconcile(String presentedKey, String orchestrationId) {
    requireOperator(presentedKey);
    try {
      OrchestrationView view = orchestrationService.reconcile(orchestrationId);
      return finalizeIfTerminalForExisting(orchestrationId, view);
    } catch (RuntimeException exception) {
      throw error(HttpStatus.NOT_FOUND, "RUN_NOT_FOUND", "Bootstrap run was not found");
    }
  }

  /**
   * Crash recovery: expires stale approvals and marks SC-1431 recoverables for lookup-only
   * reconciliation. Never calls reserve/start; never redispatches.
   */
  public java.util.List<OrchestrationView> recoverAfterRestart(String presentedKey) {
    requireOperator(presentedKey);
    approvalStore.expireBefore(clock.instant());
    return orchestrationService.recoverAfterRestart();
  }

  private OrchestrationView finalizeIfTerminal(
      ApprovalView approval,
      RecordingView recording,
      AssembledPreview assembled,
      ExecutionEnvelope boundEnvelope,
      HumanAuthority authority,
      OrchestrationView view) {
    if (!view.state().provenTerminal() && view.state() != State.NEEDS_RECONCILIATION) {
      return view;
    }
    if (view.state() == State.NEEDS_RECONCILIATION) {
      return view;
    }
    try {
      // Stop the exact active recording once. When a different recording is active (or none),
      // reconciliation is required: never finalize a foreign recording's bytes.
      // stopForFinalization journals the intent first and either stops the live recording or
      // recovers the already-finalized file; it never returns an empty artifact.
      var artifact = recordingCoordinator.stopForFinalization(recording.recordingId()).orElse(null);
      if (artifact == null) {
        return view;
      }
      // The finalization instant is durable (persisted SC-1431 terminal evidence), never the
      // wall clock, so a crash between publication and database finalization replays the
      // identical manifest bytes and digest.
      Instant finalizedAt = durableFinalizedAt(approval.orchestrationId(), view);
      var manifest =
          manifestService.buildManifest(
              approval,
              recording,
              artifact,
              assembled.decision(),
              boundEnvelope,
              assembled.bindings(),
              assembled.observation(),
              authority,
              view,
              finalizedAt);
      manifestService.publish(
          recording.recordingId(), manifest.document(), manifest.manifestDigest());
      recordingCoordinator.finalizeRecording(
          approval.orchestrationId(),
          view.state().name(),
          view.failureCode().name(),
          manifest.manifestDigest(),
          artifact.videoPath(),
          artifact.artifactDigest(),
          finalizedAt);
    } catch (RuntimeException exception) {
      // Finalization failure never repeats gameplay; recovery resumes from durable evidence.
      LOG.warn(
          "SC-1498 finalization projection failed for orchestration {}; recovery resumes from"
              + " durable evidence",
          approval.orchestrationId(),
          exception);
    }
    return view;
  }

  private OrchestrationView finalizeIfTerminalForExisting(
      String orchestrationId, OrchestrationView view) {
    if (!view.state().provenTerminal()) {
      return view;
    }
    try {
      var recording = recordingCoordinator.find(orchestrationId).orElse(null);
      var approval = approvalStore.findByOrchestration(orchestrationId).orElse(null);
      if (recording == null || approval == null || !"STARTED".equals(recording.manifestState())) {
        return view;
      }
      // Recovery resumes finalization from durable evidence without redispatch. The full
      // manifest is rebuilt from the durable SC-1431 request snapshot plus the durable
      // approval; gameplay is never repeated to recover a manifest. When the live
      // recording already stopped before the crash, the artifact is rebuilt from the
      // durable recording session instead of repeating gameplay.
      // stopForFinalization journals the intent first and either stops the live recording or
      // recovers the already-finalized file; it never returns an empty artifact.
      var artifact = recordingCoordinator.stopForFinalization(recording.recordingId()).orElse(null);
      if (artifact == null) {
        return view;
      }
      OrchestrationRequest persisted = orchestrationService.findRequest(orchestrationId);
      Instant finalizedAt = durableFinalizedAt(orchestrationId, view);
      var manifest =
          manifestService.buildManifest(
              approval,
              recording,
              artifact,
              persisted.decision(),
              persisted.envelope(),
              persisted.bindings(),
              persisted.observation(),
              persisted.authority(),
              view,
              finalizedAt);
      manifestService.publish(
          recording.recordingId(), manifest.document(), manifest.manifestDigest());
      recordingCoordinator.finalizeRecording(
          orchestrationId,
          view.state().name(),
          view.failureCode().name(),
          manifest.manifestDigest(),
          artifact.videoPath(),
          artifact.artifactDigest(),
          finalizedAt);
    } catch (RuntimeException exception) {
      // Recovery resumes finalization from durable evidence without redispatch.
      LOG.warn(
          "SC-1498 recovery finalization failed for orchestration {}; a later reconcile resumes"
              + " from durable evidence",
          orchestrationId,
          exception);
    }
    return view;
  }

  /**
   * Durable finalization instant for the evidence manifest. Prefers the persisted SC-1431
   * terminal-result timestamp; falls back to the persisted terminal view update time. Both are
   * durable, so recovery replays the identical manifest bytes and digest. The wall clock is never
   * sampled here.
   */
  private Instant durableFinalizedAt(String orchestrationId, OrchestrationView view) {
    try {
      var terminal = orchestrationService.findTerminalResult(orchestrationId).orElse(null);
      if (terminal != null && terminal.terminalAt() != null) {
        return terminal.terminalAt();
      }
    } catch (RuntimeException ignored) {
      // Fall through to the persisted terminal view timestamp.
    }
    return view.updatedAt();
  }

  private String durableAuthorityId(ApprovalView approval) {
    if (approval.boundAuthorityId() != null) {
      return approval.boundAuthorityId();
    }
    return "auth_" + approval.approvalId().replace("approval_", "");
  }

  private ExecutionEnvelope withAuthority(ExecutionEnvelope envelope, String authorityId) {
    if (authorityId.equals(envelope.authorityId())) {
      return envelope;
    }
    com.fasterxml.jackson.databind.node.ObjectNode node =
        (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.valueToTree(envelope);
    node.put("authorityId", authorityId);
    try {
      return objectMapper.treeToValue(node, ExecutionEnvelope.class);
    } catch (Exception exception) {
      throw new IllegalStateException("Unable to bind durable authority id", exception);
    }
  }

  private Object safeOrchestrationReadiness() {
    try {
      return orchestrationService.readiness();
    } catch (RuntimeException exception) {
      return null;
    }
  }

  private List<String> blockingReasons(boolean providerReady, Object orchestrationReadiness) {
    java.util.ArrayList<String> reasons = new java.util.ArrayList<>();
    if (!enabled) {
      reasons.add("BOOTSTRAP_DISABLED");
    }
    if (operatorKey.length < 32) {
      reasons.add("OPERATOR_KEY_UNCONFIGURED");
    }
    if (!providerReady) {
      reasons.add("PROVIDER_RUNTIME_NOT_READY");
    }
    if (!recordingEnabled) {
      reasons.add("RECORDING_DISABLED");
    }
    if (!micro6V2Enabled) {
      reasons.add("MICRO6_V2_DISABLED");
    }
    if (orchestrationReadiness == null) {
      reasons.add("ORCHESTRATION_UNAVAILABLE");
    }
    return List.copyOf(reasons);
  }

  /**
   * Refuses unless the live Micro6 catalog attests this exact task as eligible with the schema the
   * approved envelope binds. Nothing is fabricated from the request when the attestation is absent.
   */
  private void requireEligibleTask(
      ExecutionEnvelope envelope, CapabilitySnapshot capabilities) {
    if (capabilities == null || !capabilities.liveDeliveryReady()) {
      throw error(
          HttpStatus.SERVICE_UNAVAILABLE,
          FailureCode.GATEWAY_UNAVAILABLE.name(),
          "AccountBuilder capability attestation is unavailable");
    }
    TaskCapability task = capabilities.task(envelope.taskKey());
    if (task == null
        || !task.eligible()
        || !java.util.Objects.equals(task.schemaDigest(), envelope.schemaDigest())) {
      throw error(
          HttpStatus.UNPROCESSABLE_ENTITY,
          FailureCode.TASK_INELIGIBLE.name(),
          "AccountBuilder task is not currently eligible");
    }
  }

  private void requireOperator(String presentedKey) {
    byte[] presented =
        presentedKey == null ? new byte[0] : presentedKey.getBytes(StandardCharsets.UTF_8);
    if (operatorKey.length < 32 || !MessageDigest.isEqual(operatorKey, presented)) {
      throw error(
          HttpStatus.UNAUTHORIZED,
          "OPERATOR_AUTHENTICATION_FAILED",
          "A valid SC-1498 operator key is required");
    }
  }

  private void requireEnabled() {
    if (!enabled) {
      throw error(
          HttpStatus.SERVICE_UNAVAILABLE,
          "BOOTSTRAP_OPERATOR_DISABLED",
          "SC-1498 operator endpoints are disabled");
    }
    if (!recordingEnabled) {
      throw error(
          HttpStatus.SERVICE_UNAVAILABLE,
          "BOOTSTRAP_RECORDING_DISABLED",
          "SC-1498 bootstrap recording is disabled");
    }
    if (!micro6V2Enabled) {
      throw error(
          HttpStatus.SERVICE_UNAVAILABLE,
          "MICRO6_V2_DISABLED",
          "Micro6 v2 AccountBuilder delivery is disabled");
    }
  }

  private boolean constantTimeEquals(String left, String right) {
    if (left == null || right == null) {
      return false;
    }
    return MessageDigest.isEqual(
        left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
  }

  private OperatorException error(HttpStatus status, String failureCode, String message) {
    return new OperatorException(status, failureCode, message);
  }

  public record PreviewRequest(
      String providerSessionId,
      String providerTurnId,
      JsonNode observation,
      ExecutionEnvelope executionEnvelope,
      SnapshotBindings snapshotBindings) {}

  public record ApprovalRequest(
      String providerSessionId,
      String providerTurnId,
      JsonNode observation,
      ExecutionEnvelope executionEnvelope,
      SnapshotBindings snapshotBindings,
      String approvedBy,
      Instant expiresAt,
      String scopeDigest,
      boolean humanConfirmed,
      String confirmation) {}

  public record DispatchRequest(
      String approvalId,
      JsonNode observation,
      ExecutionEnvelope executionEnvelope,
      SnapshotBindings snapshotBindings) {}

  public record StopRequest(String orchestrationId, String reason) {}

  public record KillRequest(String orchestrationId, String reason, boolean confirmed) {}

  public record ApprovalPreview(
      Map<String, Object> exactScope,
      String scopeDigest,
      String previewFingerprint,
      String orchestrationId,
      String requestId,
      String recordingId) {}

  public record PendingApprovalView(
      String approvalId,
      String approvedBy,
      Instant expiresAt,
      String scopeDigest,
      String orchestrationId,
      boolean created) {}

  public record Availability(
      boolean enabled,
      boolean operatorKeyConfigured,
      boolean providerReady,
      List<String> providerModels,
      int pendingApprovals,
      List<String> blockingReasons) {}

  public static final class OperatorException extends IllegalStateException {
    private final HttpStatus status;
    private final String failureCode;

    public OperatorException(HttpStatus status, String failureCode, String message) {
      super(message);
      this.status = status;
      this.failureCode = failureCode;
    }

    public HttpStatus status() {
      return status;
    }

    public String failureCode() {
      return failureCode;
    }
  }
}
