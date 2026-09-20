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
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.CapabilitySnapshot;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.Mode;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.OrchestrationRequest;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.OrchestrationView;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.ProviderOrchestrationCommand;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.SnapshotBindings;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.State;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationService;
import com.chromascape.web.gameplay.orchestration.accountbuilder.ProviderDecisionOrchestrationSource;
import com.chromascape.web.gameplay.provider.runtime.GameplayProviderRuntime;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
  private final CanonicalGameplayJson canonicalJson;
  private final HumanGameplayAuthorityService authorityService;
  private final BootstrapProposalAssembler assembler;
  private final BootstrapApprovalStore approvalStore;
  private final BootstrapRecordingCoordinator recordingCoordinator;
  private final BootstrapEvidenceManifestService manifestService;
  private final ProviderDecisionOrchestrationSource decisionSource;
  private final AccountBuilderCapabilityPreflight capabilityPreflight;
  private final AccountBuilderOrchestrationService orchestrationService;
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
      ProviderDecisionOrchestrationSource decisionSource,
      AccountBuilderCapabilityPreflight capabilityPreflight,
      AccountBuilderOrchestrationService orchestrationService,
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
        decisionSource,
        capabilityPreflight,
        orchestrationService,
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
      ProviderDecisionOrchestrationSource decisionSource,
      AccountBuilderCapabilityPreflight capabilityPreflight,
      AccountBuilderOrchestrationService orchestrationService,
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
    this.decisionSource = decisionSource;
    this.capabilityPreflight = capabilityPreflight;
    this.orchestrationService = orchestrationService;
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
        0,
        blockingReasons(providerReady, orchestrationReadiness));
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
    ApprovalView bound =
        approvalStore.bind(approval.approvalId(), authority.authorityId(), signatureDigest);
    // SC-1498 ordering: the durable recording projection STARTS before SC-1431 delivery. If the
    // process dies after this point, recovery finds the STARTED projection and resumes
    // reconciliation or finalization only — gameplay is never redispatched.
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
    // The hardened SC-1431 provider route is mirrored here: the provider decision, its
    // provenance and the source type are loaded from the durable persisted provider turn by
    // stage one, an exact replay is resolved with the stored capability digest, and only a
    // genuinely new orchestration performs the Micro6 capability preflight. Invalid decisions
    // and exact replays therefore never reach delivery. The generic orchestrate() entry point
    // rejects provider sources, so the provider-only entry point is used.
    ProviderOrchestrationCommand command =
        new ProviderOrchestrationCommand(
            bound.providerSessionId(),
            bound.providerTurnId(),
            bound.orchestrationId(),
            bound.requestId(),
            boundEnvelope.idempotencyKey(),
            Mode.BOOTSTRAP_RECORDING,
            boundEnvelope,
            authority,
            request.observation(),
            boundEnvelope.deadline());
    ProviderDecisionOrchestrationSource.PreparedProviderRequest prepared;
    try {
      prepared = decisionSource.prepareProduction(command);
    } catch (RuntimeException exception) {
      throw error(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "STALE_BINDING",
          "Persisted provider decision cannot back this dispatch");
    }
    AccountBuilderOrchestrationService.Submission submission;
    try {
      Optional<OrchestrationView> existing =
          orchestrationService.findOrchestration(bound.orchestrationId());
      if (existing.isPresent()) {
        String storedCapabilityDigest =
            orchestrationService.storedCapabilityDigest(bound.orchestrationId());
        OrchestrationRequest replay =
            decisionSource.completeProductionReplay(prepared, storedCapabilityDigest);
        if (!replay.requestFingerprint().equals(existing.get().requestFingerprint())) {
          throw error(
              HttpStatus.CONFLICT,
              "STALE_BINDING",
              "A different request is already persisted under this orchestration id");
        }
        return finalizeIfTerminalForExisting(bound.orchestrationId(), existing.get());
      }
      CapabilitySnapshot preflight = capabilityPreflight.probe(boundEnvelope);
      OrchestrationRequest orchestrationRequest =
          decisionSource.completeProduction(prepared, preflight);
      submission = orchestrationService.orchestrateProvider(orchestrationRequest);
    } catch (OperatorException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw error(
          HttpStatus.CONFLICT,
          "DELIVERY_UNCERTAIN",
          "SC-1431 orchestration could not be reconciled; use reconcile only");
    }
    return finalizeIfTerminal(
        bound, recording, assembled, boundEnvelope, authority, submission.view());
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
      var artifact =
          recordingCoordinator
              .stopForFinalization(recording.recordingId())
              .or(() -> recordingCoordinator.rebuildArtifactForRecovery(recording.recordingId()))
              .orElse(null);
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
    } catch (RuntimeException ignored) {
      // Finalization failure never repeats gameplay; recovery resumes from durable evidence.
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
      var artifact =
          recordingCoordinator
              .stopForFinalization(recording.recordingId())
              .or(() -> recordingCoordinator.rebuildArtifactForRecovery(recording.recordingId()))
              .orElse(null);
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
    } catch (RuntimeException ignored) {
      // Recovery resumes finalization from durable evidence without redispatch.
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
