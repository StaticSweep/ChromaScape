package com.chromascape.web.aiplayer.routine.service;

import com.chromascape.web.aiplayer.contract.CanonicalJsonHasher;
import com.chromascape.web.aiplayer.contract.CurrentSliceIdentity;
import com.chromascape.web.aiplayer.contract.CurrentSliceIdentityValidator;
import com.chromascape.web.aiplayer.routine.config.AiRoutineOperatorProperties;
import com.chromascape.web.aiplayer.routine.entity.PatchCandidateRun;
import com.chromascape.web.aiplayer.routine.repository.PatchCandidateRunRepository;
import com.chromascape.web.aiplayer.routine.source.SourceManifestContract;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Orchestrates the certified-candidate lifecycle.
 *
 * <pre>
 * PATCH_PROPOSED -authenticated approval-&gt; APPROVED -enqueue-&gt; BUILD_QUEUED -claim-&gt; BUILDING
 *   -evidence complete-&gt; BUILT | BUILD_FAILED
 * BUILT -prepare registration-&gt; REGISTERING -verified registry response-&gt; REGISTERED
 *   -ambiguous transport-&gt; REGISTERING_UNCERTAIN -verified lookup-&gt; REGISTERED -confirmed 404-&gt; re-post
 * terminal: REGISTERED | BUILD_FAILED | REJECTED | FAILED
 * </pre>
 *
 * <p>External work (build, registry POST) never begins inside the transaction that changes state.
 * Builds are claimed by a bounded worker with an expiring lease; a periodic scanner requeues
 * expired leases and abandoned queued records after restart. Registration is staged durably under a
 * configured persistent root before the registry POST and recovered only from the exact persisted
 * bundle.
 */
@Service
public class PatchCandidateWorkflowService {
  /**
   * Canonical hash of {@code src/test/resources/sc967/sc572-seeded-failure-v1.json}. The seeded
   * failure manifest carries the failure code and the resolved source symbol, so renaming either
   * requires regenerating this value with the fixture.
   */
  private static final String SEEDED_FAIL =
      "sha256:10f1e4d0f27c18472ff4193317eeaf8a1aa42751e4cefe0060b156e92cba5a3e";

  private static final String SEEDED_FAILURE_CODE = "BLANK_FISH_ALIAS_ACCEPTED";
  private static final String SOURCE_SYMBOL =
      "FishingTaskDefinitionNormalizer#normalize(String, Map)";
  private static final String SOURCE_FILE =
      "runelite-client/src/main/java/net/runelite/client/plugins/microbot/accountbuilder/sharedcore/fishing/FishingTaskDefinitionNormalizer.java";
  private static final String TEST_FILE =
      "runelite-client/src/test/java/net/runelite/client/plugins/microbot/accountbuilder/sharedcore/fishing/FishingTaskDefinitionNormalizerTest.java";
  private static final String SCHEMA_V2 = "certified-candidate/v2";
  private static final java.time.Duration LEASE_DURATION = java.time.Duration.ofMinutes(15);
  private static final List<String> BUILD_GATES =
      List.of(
          ":client:compileJava",
          ":client:sc967CandidateCheckstyleMain",
          ":client:sc967CandidateCheckstyleTest",
          ":client:sc967CandidateTest",
          ":client:shadowJar",
          ":client:generateRuntimeSbom",
          ":client:sc2106ExecutionClosureInputs");
  private static final String PATCH =
      """
      diff --git a/runelite-client/src/main/java/net/runelite/client/plugins/microbot/accountbuilder/sharedcore/fishing/FishingTaskDefinitionNormalizer.java b/runelite-client/src/main/java/net/runelite/client/plugins/microbot/accountbuilder/sharedcore/fishing/FishingTaskDefinitionNormalizer.java
      index 4fd9a0d83c..5dc6e2af46 100644
      --- a/runelite-client/src/main/java/net/runelite/client/plugins/microbot/accountbuilder/sharedcore/fishing/FishingTaskDefinitionNormalizer.java
      +++ b/runelite-client/src/main/java/net/runelite/client/plugins/microbot/accountbuilder/sharedcore/fishing/FishingTaskDefinitionNormalizer.java
      @@ -180,6 +180,14 @@ public final class FishingTaskDefinitionNormalizer
      !
      ! 		String targetFish = text(s, "targetFish");
      ! 		String fishType = text(s, "fishType");
      +		if (s.get("targetFish") != null && targetFish == null)
      +		{
      +			return Result.error(FishingCoreCode.INVALID_SETTING, "targetFish must not be blank", "targetFish");
      +		}
      +		if (s.get("fishType") != null && fishType == null)
      +		{
      +			return Result.error(FishingCoreCode.INVALID_SETTING, "fishType must not be blank", "fishType");
      +		}
      ! 		String fish;
      ! 		if (isExplicitFish(targetFish))
      ! 		{
      diff --git a/runelite-client/src/test/java/net/runelite/client/plugins/microbot/accountbuilder/sharedcore/fishing/FishingTaskDefinitionNormalizerTest.java b/runelite-client/src/test/java/net/runelite/client/plugins/microbot/accountbuilder/sharedcore/fishing/FishingTaskDefinitionNormalizerTest.java
      index 622c04f0e3..1602e198c5 100644
      --- a/runelite-client/src/test/java/net/runelite/client/plugins/microbot/accountbuilder/sharedcore/fishing/FishingTaskDefinitionNormalizerTest.java
      +++ b/runelite-client/src/test/java/net/runelite/client/plugins/microbot/accountbuilder/sharedcore/fishing/FishingTaskDefinitionNormalizerTest.java
      @@ -137,6 +137,13 @@ class FishingTaskDefinitionNormalizerTest
      ! 			FishingTaskDefinitionNormalizer.normalize("WOODCUTTING_TRAINING", initialCaseSettings()), "taskKey");
      ! 	}
      !
      +	@Test
      +	void blankFishAliasesAreInvalidRatherThanMissing()
      +	{
      +		assertRejected(FishingCoreCode.INVALID_SETTING, normalize(with("targetFish", "  ")), "targetFish");
      +		assertRejected(FishingCoreCode.INVALID_SETTING, normalize(with("fishType", "")), "fishType");
      +	}
      +
      ! 	@Test
      ! 	void malformedValuesAreInvalid()
      ! 	{
      """
          .replace("\n!", "\n");

  private final ObjectMapper mapper;
  private final IsolatedPatchWorker worker;
  private final PatchCandidateRunRepository repository;
  private final PatchCandidateBuildDispatcher dispatcher;
  private final CurrentSliceRunService runService;
  private final Micro6SourceManifestClient source;
  private final CurrentSliceIdentityValidator identities;
  private final CandidateRegistryClient registry;
  private final CertifiedCandidateV2Validator candidateValidator;
  private final AiRoutineOperatorProperties operator;
  private final com.chromascape.web.aiplayer.routine.config.Sc967CandidateProperties
      candidateProperties;
  private final PatchCandidateRegistrationDispatcher registrationDispatcher;

  @Value("${builder-goal.recovery.micro6-project-dir:../micro6}")
  private String micro6Root;

  @Value("${leeuwbot.signing.private-key-path:}")
  private String signingKeyPath;

  @Value("${leeuwbot.signing.key-id:}")
  private String signingKeyId;

  @Value("${micro6.expected-artifact-name:}")
  private String expectedArtifactName;

  private final ExecutorService buildExecutor =
      new ThreadPoolExecutor(
          0,
          1,
          30,
          TimeUnit.SECONDS,
          new ArrayBlockingQueue<>(4),
          new ThreadPoolExecutor.AbortPolicy());

  @org.springframework.beans.factory.annotation.Autowired
  public PatchCandidateWorkflowService(
      ObjectMapper mapper,
      IsolatedPatchWorker worker,
      PatchCandidateRunRepository repository,
      PatchCandidateBuildDispatcher dispatcher,
      CurrentSliceRunService runService,
      Micro6SourceManifestClient source,
      CurrentSliceIdentityValidator identities,
      CandidateRegistryClient registry,
      AiRoutineOperatorProperties operator,
      com.chromascape.web.aiplayer.routine.config.Sc967CandidateProperties candidateProperties,
      PatchCandidateRegistrationDispatcher registrationDispatcher,
      CertifiedCandidateV2Validator candidateValidator) {
    this.mapper = mapper;
    this.worker = worker;
    this.repository = repository;
    this.dispatcher = dispatcher;
    this.runService = runService;
    this.source = source;
    this.identities = identities;
    this.registry = registry;
    this.operator = operator;
    this.candidateProperties = candidateProperties;
    this.registrationDispatcher = registrationDispatcher;
    this.candidateValidator = candidateValidator;
  }

  /** Compatibility constructor retained for focused unit tests that do not bootstrap Spring. */
  public PatchCandidateWorkflowService(
      ObjectMapper mapper,
      IsolatedPatchWorker worker,
      PatchCandidateRunRepository repository,
      PatchCandidateBuildDispatcher dispatcher,
      CurrentSliceRunService runService,
      Micro6SourceManifestClient source,
      CurrentSliceIdentityValidator identities,
      CandidateRegistryClient registry,
      AiRoutineOperatorProperties operator,
      com.chromascape.web.aiplayer.routine.config.Sc967CandidateProperties candidateProperties) {
    this(
        mapper,
        worker,
        repository,
        dispatcher,
        runService,
        source,
        identities,
        registry,
        operator,
        candidateProperties,
        null,
        new CertifiedCandidateV2Validator());
  }

  private void requireCurrentCandidate(PatchCandidateRun entity) {
    identities.requireOperation(
        mapper.convertValue(read(entity.getIdentityJson()), CurrentSliceIdentity.class),
        "candidate");
  }

  private String stagingRoot() {
    return candidateProperties == null ? "" : candidateProperties.getStagingRoot();
  }

  private boolean buildsEnabled() {
    return candidateProperties != null && candidateProperties.isBuildsEnabled();
  }

  /**
   * Fails startup when candidate builds are enabled but any required signing/registry/staging
   * operator configuration is missing, unreadable, or unusable. This is a real runtime gate, not a
   * startup hint: build enqueue, worker execution and recovery dispatch are all rejected while the
   * flag is disabled.
   */
  @PostConstruct
  void validateConfiguration() {
    if (!buildsEnabled()) {
      return;
    }
    if (operator == null || operator.getToken() == null || operator.getToken().isBlank()) {
      throw new IllegalStateException(
          "ai-routine.operator.token (AI_ROUTINE_OPERATOR_TOKEN) is required when candidate builds"
              + " are enabled");
    }
    if (operator.getId() == null || operator.getId().isBlank()) {
      throw new IllegalStateException(
          "ai-routine.operator.id (AI_ROUTINE_OPERATOR_ID) is required when candidate builds are"
              + " enabled");
    }
    if (signingKeyPath == null || signingKeyPath.isBlank()) {
      throw new IllegalStateException(
          "leeuwbot.signing.private-key-path (CANDIDATE_SIGNING_PRIVATE_KEY_PATH) is required when"
              + " candidate builds are enabled");
    }
    if (signingKeyId == null || signingKeyId.isBlank()) {
      throw new IllegalStateException(
          "leeuwbot.signing.key-id (CANDIDATE_SIGNING_KEY_ID) is required when candidate builds are"
              + " enabled");
    }
    try {
      new CandidateSigner(signingKeyId, Path.of(signingKeyPath));
    } catch (Exception e) {
      throw new IllegalStateException(
          "signing private key is not readable or not a valid Ed25519 key: " + signingKeyPath, e);
    }
    if (registry == null || registry.baseUrl() == null || registry.baseUrl().isBlank()) {
      throw new IllegalStateException(
          "leeuwbot.candidate-registry.base-url (LEEUWBOT_CANDIDATE_REGISTRY_URL) is required when"
              + " candidate builds are enabled");
    }
    if (registry.token() == null || registry.token().isBlank()) {
      throw new IllegalStateException(
          "leeuwbot.candidate-registry.token (STATIC_API_TOKEN) is required when candidate builds"
              + " are enabled");
    }
    if (stagingRoot().isBlank()) {
      throw new IllegalStateException(
          "sc967.candidate.staging-root (SC967_CANDIDATE_STAGING_ROOT) is required when candidate"
              + " builds are enabled");
    }
    try {
      Files.createDirectories(Path.of(stagingRoot()).toAbsolutePath().normalize());
    } catch (Exception e) {
      throw new IllegalStateException(
          "sc967.candidate.staging-root is not usable: " + stagingRoot(), e);
    }
    if (expectedArtifactName == null || expectedArtifactName.isBlank()) {
      throw new IllegalStateException(
          "micro6.expected-artifact-name (MICRO6_EXPECTED_ARTIFACT_NAME) is required when candidate"
              + " builds are enabled");
    }
  }

  private void requireBuildsEnabled() {
    if (!buildsEnabled()) {
      throw error(
          HttpStatus.FORBIDDEN,
          "CANDIDATE_BUILDS_DISABLED",
          "candidate builds are disabled (sc967.candidate.builds-enabled=false); nothing can be"
              + " queued or executed");
    }
  }

  @Transactional
  public Map<String, Object> fromFailure(Map<String, Object> request) {
    if (request == null
        || !SEEDED_FAIL.equals(String.valueOf(request.get("failure_manifest_hash")))) {
      throw error(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "FAILURE_FIXTURE_MISMATCH",
          "only the canonical seeded SC-572 FAIL manifest is eligible");
    }
    if (!SEEDED_FAILURE_CODE.equals(String.valueOf(request.get("failure_code")))) {
      throw error(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "FAILURE_CODE_MISMATCH",
          "seeded failure code is unsupported");
    }
    Object identityValue = request.get("identity");
    if (identityValue == null)
      throw error(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "CANDIDATE_IDENTITY_REQUIRED",
          "candidate identity through model is required");
    CurrentSliceIdentity candidateIdentity;
    try {
      candidateIdentity =
          identities.validate(mapper.convertValue(identityValue, CurrentSliceIdentity.class));
    } catch (com.chromascape.web.aiplayer.contract.CurrentSliceContractException e) {
      throw e;
    } catch (RuntimeException e) {
      throw error(HttpStatus.UNPROCESSABLE_ENTITY, "CANDIDATE_IDENTITY_INVALID", e.getMessage());
    }
    if (candidateIdentity.candidate() != null)
      throw error(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "CANDIDATE_IDENTITY_INVALID",
          "proposal identity must be frozen through model");
    if (candidateIdentity.source() == null
        || candidateIdentity.graph() == null
        || candidateIdentity.model() == null) {
      throw error(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "CANDIDATE_IDENTITY_INCOMPLETE",
          "identity through graph and model is required before proposal creation");
    }
    identities.requireOperation(candidateIdentity, "candidate");
    Micro6SourceManifestClient.SourceBinding resolved =
        source.resolve(
            new Micro6SourceManifestClient.SourceRequest(
                "FISHING_TRAINING",
                "net.runelite.client.plugins.microbot.accountbuilder.sharedcore.fishing.FishingTaskDefinitionNormalizer",
                SOURCE_SYMBOL,
                Micro6SourceManifestClient.expectedIdentity(candidateIdentity)));
    if (!resolved.signatureVerified()
        || !SourceManifestContract.isGitObjectId(resolved.commitId())
        || !SourceManifestContract.isGitObjectId(resolved.treeId())
        || !SourceManifestContract.isHash(resolved.artifactDigest())) {
      throw error(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "SOURCE_PROVENANCE_UNVERIFIED",
          "only a signed source manifest with exact commit, tree, and artifact identity may enter"
              + " the patch loop");
    }
    if (!resolved.clientBuildId().equals(candidateIdentity.client().clientBuildId())) {
      throw error(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "CANDIDATE_IDENTITY_BUILD_MISMATCH",
          "candidate identity client build differs from the authoritative source manifest");
    }
    candidateIdentity = withCandidateSource(candidateIdentity, resolved);
    identities.requireOperation(candidateIdentity, "candidate");
    String requestedBase = text(request, "base_commit");
    if (requestedBase != null && !requestedBase.equals(resolved.commitId())) {
      throw error(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "BASE_COMMIT_MISMATCH",
          "requested base commit differs from the verified source commit");
    }
    String baseCommit = requestedBase == null ? resolved.commitId() : requestedBase;
    if (baseCommit == null || baseCommit.isBlank() || "nogit".equals(baseCommit)) {
      throw error(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "SOURCE_BINDING_INCOMPLETE",
          "verified Micro6 source commit is required");
    }

    Map<String, Object> binding = seededFailureBinding(resolved, parse(resolved.manifestJson()));

    ObjectNode content = mapper.createObjectNode();
    content.put("schema", "patch-proposal/v1");
    content.set("failure_binding", mapper.valueToTree(binding));
    content.put("base_commit", baseCommit);
    content.put("patch", PATCH);
    String proposalHash = CanonicalJsonHasher.hash(content, null);
    String proposalId = "proposal_" + proposalHash.substring(7, 31);
    PatchCandidateRun existing = repository.findByProposalId(proposalId).orElse(null);
    if (existing != null) return response(existing);
    PatchCandidateRun entity = new PatchCandidateRun();
    entity.setProposalId(proposalId);
    entity.setState("SOURCE_RESOLVED");
    entity.setFailureManifestHash(SEEDED_FAIL);
    entity.setSourceSnapshotId(resolved.sourceSnapshotId());
    entity.setSourceManifestHash(resolved.sourceManifestHash());
    entity.setSourceManifestJson(resolved.manifestJson());
    entity.setSourceFileId(resolved.fileId());
    entity.setSourceRelativePath(resolved.cloneRelativePath());
    entity.setStartLine(resolved.startLine());
    entity.setEndLine(resolved.endLine());
    entity.setPreimageHash(resolved.preimageHash());
    entity.setBaseCommit(baseCommit);
    entity.setPatchText(PATCH);
    entity.setBindingJson(write(binding));
    entity.setIdentityJson(write(candidateIdentity));
    entity.setPatchHash(CanonicalJsonHasher.hash(mapper.valueToTree(PATCH), null));
    entity.setProposalHash(proposalHash);
    entity.setState("PATCH_PROPOSED");
    repository.save(entity);
    return response(entity);
  }

  /**
   * Builds the seeded failure binding from the resolved source. The symbol and failure code are the
   * resolved values, never a stale copy of the pre-normalizer code path.
   */
  static Map<String, Object> seededFailureBinding(
      Micro6SourceManifestClient.SourceBinding resolved, JsonNode manifestJson) {
    Map<String, Object> binding = new LinkedHashMap<>();
    binding.put("schema", "seeded-failure-source-binding/v1");
    binding.put("derivation", "SEEDED_FIXTURE");
    binding.put("failure_manifest_hash", SEEDED_FAIL);
    binding.put("failure_code", SEEDED_FAILURE_CODE);
    binding.put("task_key", "FISHING_TRAINING");
    binding.put("source_snapshot_id", resolved.sourceSnapshotId());
    binding.put("source_manifest_hash", resolved.sourceManifestHash());
    binding.put("file_id", resolved.fileId());
    binding.put("relative_path", resolved.relativePath());
    binding.put("clone_relative_path", resolved.cloneRelativePath());
    binding.put("content_hash", resolved.contentHash());
    binding.put("manifest_json", manifestJson);
    binding.put("pinned_files", resolved.pinnedFiles());
    binding.put("symbol", SOURCE_SYMBOL);
    binding.put("start_line", resolved.startLine());
    binding.put("end_line", resolved.endLine());
    binding.put("preimage_hash", resolved.preimageHash());
    binding.put("repository_id", resolved.repositoryId());
    binding.put("commit_id", resolved.commitId());
    binding.put("tree_id", resolved.treeId());
    binding.put("build_id", resolved.clientBuildId());
    binding.put("artifact_digest", resolved.artifactDigest());
    binding.put("signature_verified", resolved.signatureVerified());
    return binding;
  }

  /** Binds candidate provenance to the authoritative patch source without changing prior groups. */
  static CurrentSliceIdentity withCandidateSource(
      CurrentSliceIdentity identity, Micro6SourceManifestClient.SourceBinding resolved) {
    if (identity == null
        || resolved == null
        || !resolved.signatureVerified()
        || resolved.repositoryId() == null
        || resolved.commitId() == null
        || resolved.treeId() == null
        || resolved.clientBuildId() == null
        || resolved.artifactDigest() == null) {
      throw new IllegalArgumentException("complete verified source identity is required");
    }
    return new CurrentSliceIdentity(
        identity.schema(),
        identity.task(),
        identity.run(),
        identity.client(),
        identity.capture(),
        new CurrentSliceIdentity.Source(
            resolved.sourceSnapshotId(),
            resolved.sourceManifestHash(),
            resolved.repositoryId(),
            resolved.commitId(),
            resolved.treeId(),
            resolved.clientBuildId(),
            resolved.artifactDigest(),
            Boolean.TRUE),
        identity.graph(),
        identity.model(),
        null,
        false);
  }

  @Transactional
  public Map<String, Object> approve(String id, String operatorId) {
    PatchCandidateRun entity = lockedProposal(id);
    requireCurrentCandidate(entity);
    if (operatorId == null || operatorId.isBlank())
      throw error(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "IDENTITY_FIELD_MISSING",
          "operator identity is required");
    if ("APPROVED".equals(entity.getState())) {
      if (operatorId.equals(entity.getApproverId())) return response(entity);
      throw error(
          HttpStatus.CONFLICT,
          "PATCH_APPROVAL_STALE",
          "proposal is already approved by another operator");
    }
    if (!"PATCH_PROPOSED".equals(entity.getState()))
      throw error(HttpStatus.CONFLICT, "PATCH_APPROVAL_STALE", "proposal is no longer approvable");
    Instant approvedAt = Instant.now();
    ObjectNode approval = mapper.createObjectNode();
    approval.put("proposal_id", entity.getProposalId());
    approval.put("proposal_hash", entity.getProposalHash());
    approval.put("patch_hash", entity.getPatchHash());
    approval.put("base_commit", entity.getBaseCommit());
    approval.put("source_snapshot_id", entity.getSourceSnapshotId());
    approval.put("source_manifest_hash", entity.getSourceManifestHash());
    approval.put("source_file_id", entity.getSourceFileId());
    approval.put("source_relative_path", entity.getSourceRelativePath());
    approval.put("start_line", entity.getStartLine());
    approval.put("end_line", entity.getEndLine());
    approval.put("preimage_hash", entity.getPreimageHash());
    approval.put("operator_id", operatorId);
    approval.put("approver_id", operatorId);
    approval.put("approved_at", approvedAt.toString());
    entity.setApproverId(operatorId);
    entity.setApprovedAt(approvedAt);
    entity.setApprovalHash(CanonicalJsonHasher.hash(approval, null));
    entity.setState("APPROVED");
    repository.save(entity);
    return response(entity);
  }

  /**
   * Commits {@code BUILD_QUEUED} before any work is offered to the executor. The afterCommit hook
   * offers the proposal to the bounded executor; a full queue is not a rollback, the periodic
   * recovery scanner picks up the queued record instead.
   */
  @Transactional
  public Map<String, Object> enqueueBuild(String id) {
    requireBuildsEnabled();
    PatchCandidateRun entity = lockedProposal(id);
    requireCurrentCandidate(entity);
    if (!"APPROVED".equals(entity.getState()))
      throw error(
          HttpStatus.CONFLICT, "PATCH_NOT_APPROVED", "explicit operator approval is required");
    entity.setState("BUILD_QUEUED");
    entity.setWorkerAttemptId(null);
    entity.setWorkerLeaseExpiresAt(null);
    repository.save(entity);
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              offerBuild(id);
            }
          });
    } else {
      offerBuild(id);
    }
    return response(entity);
  }

  private void offerBuild(String id) {
    try {
      buildExecutor.execute(() -> runBuild(id));
    } catch (java.util.concurrent.RejectedExecutionException e) {
      // The recovery scanner will claim the queued record later; never roll back the queue commit.
    }
  }

  /** Runs the isolated build for a claimed record and drives registration on success. */
  private void runBuild(String id) {
    if (!buildsEnabled()) {
      return;
    }
    PatchCandidateRun pending = repository.findByProposalId(id).orElse(null);
    if (pending == null) return;
    requireCurrentCandidate(pending);
    String attemptId = dispatcher.claim(id, Instant.now().plus(LEASE_DURATION));
    if (attemptId == null) {
      return;
    }
    Thread heartbeat = leaseHeartbeat(id, attemptId);
    heartbeat.start();
    try {
      PatchCandidateRun entity = repository.findByProposalId(id).orElse(null);
      if (entity == null) {
        dispatcher.persistFailure(id, attemptId, "PROPOSAL_NOT_FOUND");
        return;
      }
      requireCurrentCandidate(entity);
      IsolatedPatchWorker.BuildRequest request =
          new IsolatedPatchWorker.BuildRequest(
              Path.of(micro6Root),
              entity.getBaseCommit(),
              entity.getPatchText(),
              Set.of(SOURCE_FILE, TEST_FILE),
              List.of(),
              entity.getSourceSnapshotId(),
              entity.getSourceRelativePath(),
              entity.getStartLine(),
              entity.getEndLine(),
              entity.getPreimageHash(),
              entity.getFailureManifestHash(),
              entity.getProposalHash(),
              entity.getApprovalHash(),
              entity.getPatchHash(),
              entity.getSourceManifestHash(),
              entity.getSourceManifestJson(),
              modelHash(entity.getIdentityJson()),
              expectedArtifactName);
      IsolatedPatchWorker.BuildResult result = worker.run(request);
      Map<String, Object> buildResult = new LinkedHashMap<>();
      buildResult.put("status", result.status());
      buildResult.put("changed_files", result.changedFiles());
      buildResult.put("failure_code", result.failureCode());
      dispatcher.persistBuildResult(id, attemptId, result, write(buildResult));
      if ("BUILT".equals(result.status())) {
        prepareRegistration(id, result);
        postPreparedRegistration(id);
      }
    } catch (Exception e) {
      dispatcher.persistFailure(
          id, attemptId, e.getMessage() == null ? "BUILD_FAILED" : e.getMessage());
    } finally {
      heartbeat.interrupt();
    }
  }

  private Thread leaseHeartbeat(String id, String attemptId) {
    Thread thread =
        new Thread(
            () -> {
              while (!Thread.currentThread().isInterrupted()) {
                try {
                  Thread.sleep(60_000L);
                  dispatcher.heartbeat(id, attemptId, Instant.now().plus(LEASE_DURATION));
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  return;
                } catch (Exception ignored) {
                  // best-effort; claim ownership is validated on every refresh
                }
              }
            },
            "candidate-lease-" + id);
    thread.setDaemon(true);
    return thread;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> get(String id) {
    return response(proposal(id));
  }

  /**
   * Reconciles an uncertain registration. Lookup first; accept only after exact canonical manifest
   * comparison, candidate id/hash match, certified state, and download SHA-256 verification.
   * Re-post only on a confirmed registry 404 using the exact persisted bundle.
   */
  public Map<String, Object> reconcile(String id) {
    PatchCandidateRun entity =
        repository
            .findByProposalId(id)
            .orElseThrow(
                () -> error(HttpStatus.NOT_FOUND, "NOT_FOUND", "patch proposal not found"));
    if (!"REGISTERING_UNCERTAIN".equals(entity.getState())
        && !"REGISTERING".equals(entity.getState())) {
      throw error(
          HttpStatus.CONFLICT,
          "RECONCILIATION_NOT_REQUIRED",
          "proposal is not awaiting registry reconciliation");
    }
    if ("REGISTERING".equals(entity.getState())) {
      return repostPersistedCandidate(id);
    }
    return lookupUncertain(id);
  }

  private Map<String, Object> lookupUncertain(String id) {
    PatchCandidateRun entity = repository.findByProposalId(id).orElse(null);
    if (entity == null) {
      return Map.of("control_allowed", false);
    }
    requireCurrentCandidate(entity);
    PatchCandidateRegistrationDispatcher.RegistrationClaim claim = claimRegistration(id);
    if (registrationDispatcher != null && claim == null) {
      return response(repository.findByProposalId(id).orElse(entity));
    }
    entity = repository.findByProposalId(id).orElse(entity);
    if (entity.getCandidateId() == null || entity.getCandidateHash() == null) {
      failClosed(
          id,
          "CANDIDATE_EVIDENCE_MISSING",
          "candidate identity was not persisted",
          HttpStatus.INTERNAL_SERVER_ERROR);
      return response(repository.findByProposalId(id).orElse(entity));
    }
    CandidateRegistryClient.RegistryManifestResponse lookup = null;
    CandidateRegistryClient.ArtifactDownload downloaded = null;
    try {
      requireCurrentCandidate(entity);
      Path bundle = revalidatePersistedBundle(entity);
      lookup = registry.lookupEvidence(entity.getCandidateId(), entity.getManifestFileHash());
      Map<String, Object> localManifest = read(entity.getCandidateManifestJson());
      verifyRegistryReadback(
          Files.readAllBytes(bundle.resolve("manifest.json")),
          localManifest,
          lookup,
          entity.getCandidateId(),
          entity.getCandidateHash(),
          entity.getManifestFileHash());
      if (entity.getRegistryResponseBytes() != null
          && !java.util.Arrays.equals(entity.getRegistryResponseBytes(), lookup.rawManifest())) {
        throw new IllegalStateException("REGISTRY_MANIFEST_MISMATCH");
      }
      downloaded = registry.downloadEvidence(entity.getCandidateId());
      if (!downloaded.sha256().equals(String.valueOf(localManifest.get("jar_sha256")))) {
        throw new IllegalStateException("REGISTRY_ARTIFACT_HASH_MISMATCH");
      }
      return transitionClaimed(
          entity,
          claim,
          "REGISTERED",
          Map.of(
              "candidate_id",
              entity.getCandidateId(),
              "candidate_hash",
              entity.getCandidateHash(),
              "reconciled",
              true,
              "download_sha256",
              downloaded.sha256(),
              "registration",
              new String(lookup.rawManifest(), java.nio.charset.StandardCharsets.UTF_8),
              "registry_manifest_hash",
              lookup.manifestHash()),
          evidenceForLookup(entity, lookup, downloaded));
    } catch (CandidateRegistryClient.RegistryException e) {
      if (e.isUncertain())
        return transitionClaimed(
            entity,
            claim,
            "REGISTERING_UNCERTAIN",
            Map.of("failure_code", String.valueOf(e.getMessage())),
            evidenceForLookup(entity, lookup, downloaded));
      if (e.getMessage() != null && e.getMessage().contains("404")) {
        transitionClaimed(
            entity,
            claim,
            "REGISTERING",
            Map.of("failure_code", "REGISTRY_REJECTED_404"),
            evidenceForLookup(entity, lookup, downloaded));
        return repostPersistedCandidate(id);
      }
      return transitionClaimed(
          entity,
          claim,
          "REJECTED",
          Map.of("failure_code", String.valueOf(e.getMessage())),
          evidenceForLookup(entity, lookup, downloaded));
    } catch (Exception e) {
      return transitionClaimed(
          entity,
          claim,
          "REJECTED",
          Map.of(
              "failure_code",
              e.getMessage() == null ? "REGISTRY_READBACK_INVALID" : e.getMessage()),
          evidenceForLookup(entity, lookup, downloaded));
    }
  }

  private Map<String, Object> repostPersistedCandidate(String id) {
    PatchCandidateRun entity = repository.findByProposalId(id).orElse(null);
    if (entity == null) {
      return Map.of("control_allowed", false);
    }
    requireCurrentCandidate(entity);
    PatchCandidateRegistrationDispatcher.RegistrationClaim claim = claimRegistration(id);
    if (registrationDispatcher != null && claim == null) {
      return response(repository.findByProposalId(id).orElse(entity));
    }
    entity = repository.findByProposalId(id).orElse(entity);
    if (entity.getCandidateStagingDirectory() == null) {
      // Missing/corrupt staging evidence is terminal; never silently re-create a candidate.
      return transitionClaimed(
          entity, claim, "FAILED", Map.of("failure_code", "CANDIDATE_STAGING_MISSING"), null);
    }
    CandidateRegistryClient.RegistryManifestResponse registration = null;
    CandidateRegistryClient.RegistryManifestResponse lookup = null;
    CandidateRegistryClient.ArtifactDownload downloaded = null;
    try {
      requireCurrentCandidate(entity);
      Path bundle = revalidatePersistedBundle(entity);
      Map<String, Object> manifest = read(entity.getCandidateManifestJson());
      Path manifestPath = bundle.resolve("manifest.json");
      Path jar = bundle.resolve(String.valueOf(manifest.get("jar_name")));
      Path sbom = bundle.resolve(String.valueOf(manifest.get("sbom_name")));
      Path cert = bundle.resolve("certification.json");
      registration = registry.registerEvidence(manifestPath, jar, sbom, cert);
      verifyRegistryReadback(
          Files.readAllBytes(manifestPath),
          manifest,
          registration,
          entity.getCandidateId(),
          entity.getCandidateHash(),
          entity.getManifestFileHash());
      lookup = registry.lookupEvidence(entity.getCandidateId(), registration.manifestHash());
      verifyRegistryReadback(
          Files.readAllBytes(manifestPath),
          manifest,
          lookup,
          entity.getCandidateId(),
          entity.getCandidateHash(),
          entity.getManifestFileHash());
      downloaded = registry.downloadEvidence(entity.getCandidateId());
      if (!downloaded.sha256().equals(String.valueOf(manifest.get("jar_sha256")))) {
        throw new IllegalStateException("REGISTRY_ARTIFACT_HASH_MISMATCH");
      }
      return transitionClaimed(
          entity,
          claim,
          "REGISTERED",
          Map.of(
              "candidate_id",
              entity.getCandidateId(),
              "candidate_hash",
              entity.getCandidateHash(),
              "reconciled",
              true,
              "download_sha256",
              downloaded.sha256(),
              "registration",
              new String(registration.rawManifest(), java.nio.charset.StandardCharsets.UTF_8),
              "registry_manifest_hash",
              registration.manifestHash(),
              "lookup_manifest",
              new String(lookup.rawManifest(), java.nio.charset.StandardCharsets.UTF_8)),
          evidence(registration, lookup, downloaded));
    } catch (CandidateRegistryClient.RegistryException e) {
      if (e.isUncertain())
        return transitionClaimed(
            entity,
            claim,
            "REGISTERING_UNCERTAIN",
            Map.of("failure_code", String.valueOf(e.getMessage())),
            evidence(registration, lookup, downloaded));
      if (e.getMessage() != null && e.getMessage().contains("404"))
        return transitionClaimed(
            entity,
            claim,
            "REGISTERING",
            Map.of("failure_code", "REGISTRY_REJECTED_404"),
            evidence(registration, lookup, downloaded));
      return transitionClaimed(
          entity,
          claim,
          "REJECTED",
          Map.of("failure_code", String.valueOf(e.getMessage())),
          evidence(registration, lookup, downloaded));
    } catch (Exception e) {
      return transitionClaimed(
          entity,
          claim,
          "FAILED",
          Map.of(
              "failure_code",
              e.getMessage() == null
                  ? "CANDIDATE_RECONCILIATION_FAILED"
                  : String.valueOf(e.getMessage())),
          evidence(registration, lookup, downloaded));
    }
  }

  private Map<String, Object> transition(
      PatchCandidateRun entity, String state, Map<String, Object> result) {
    return transition(entity, state, result, null);
  }

  private Map<String, Object> transition(
      PatchCandidateRun entity,
      String state,
      Map<String, Object> result,
      PatchCandidateRegistrationDispatcher.RegistryEvidence evidence) {
    if (entity == null) return Map.of("control_allowed", false);
    if (registrationDispatcher != null) {
      PatchCandidateRun current =
          registrationDispatcher.transition(entity.getProposalId(), state, result, evidence);
      return current == null ? Map.of("control_allowed", false) : response(current);
    }
    // Compatibility fallback for direct unit construction outside Spring. Production always uses
    // the locked dispatcher above, including the atomic candidate/run registration path.
    PatchCandidateRun current =
        repository.findWithLockByProposalId(entity.getProposalId()).orElse(entity);
    current.setState(state);
    if ("REGISTERED".equals(state)) {
      current.setRegistryReconciledAt(Instant.now());
      if (result.get("registration") != null)
        current.setRegistryResponseJson(String.valueOf(result.get("registration")));
      persistCandidateRunIdentity(current, "CANDIDATE_REGISTERED");
    }
    current.setResultJson(write(result));
    repository.save(current);
    return response(current);
  }

  private PatchCandidateRegistrationDispatcher.RegistrationClaim claimRegistration(String id) {
    return registrationDispatcher == null
        ? null
        : registrationDispatcher.claimRegistration(id, Instant.now());
  }

  private Map<String, Object> transitionClaimed(
      PatchCandidateRun entity,
      PatchCandidateRegistrationDispatcher.RegistrationClaim claim,
      String state,
      Map<String, Object> result,
      PatchCandidateRegistrationDispatcher.RegistryEvidence evidence) {
    if (entity == null) return Map.of("control_allowed", false);
    if (registrationDispatcher != null) {
      String attemptId = claim == null ? null : claim.attemptId();
      PatchCandidateRun current =
          attemptId == null
              ? registrationDispatcher.transition(entity.getProposalId(), state, result, evidence)
              : registrationDispatcher.transitionClaimed(
                  entity.getProposalId(), attemptId, state, result, evidence);
      return current == null ? Map.of("control_allowed", false) : response(current);
    }
    return transition(entity, state, result, evidence);
  }

  private void failClosed(String id, String code, String message, HttpStatus status) {
    PatchCandidateRun entity = repository.findByProposalId(id).orElse(null);
    if (entity != null) {
      transition(entity, "FAILED", Map.of("failure_code", code));
    }
    throw error(status, code, message);
  }

  /**
   * Durably prepares the registration bundle before any registry I/O. The bundle is assembled in
   * {@code <staging-root>/.tmp/<uuid>} using exclusive {@code CREATE_NEW} writes, fsynced, re-read
   * and verified byte-for-byte, then atomically renamed to {@code <staging-root>/<candidate-id>}. A
   * pre-existing candidate directory is reused only when every persisted byte is identical;
   * otherwise the preparation fails closed. All evidence (identity, canonical manifest, manifest
   * file hash, component hashes/sizes, staging directory, registration attempt id, request
   * fingerprint, {@code REGISTERING}) is committed in one transaction via the dispatcher.
   */
  public void prepareRegistration(String id, IsolatedPatchWorker.BuildResult build) {
    PatchCandidateRun entity = repository.findByProposalId(id).orElse(null);
    if (entity == null || !"BUILT".equals(entity.getState())) {
      return;
    }
    requireCurrentCandidate(entity);
    if (signingKeyPath == null
        || signingKeyPath.isBlank()
        || signingKeyId == null
        || signingKeyId.isBlank()) {
      transition(entity, "FAILED", Map.of("failure_code", "SIGNING_KEY_UNAVAILABLE"));
      return;
    }
    if (stagingRoot().isBlank()) {
      transition(entity, "FAILED", Map.of("failure_code", "STAGING_ROOT_NOT_CONFIGURED"));
      return;
    }
    if (build == null
        || build.jarBytes() == null
        || build.sbomBytes() == null
        || build.certificationBytes() == null) {
      transition(entity, "FAILED", Map.of("failure_code", "CERTIFICATION_ARTIFACT_MISSING"));
      return;
    }
    if (build.closureInputsBytes() == null
        || build.closureInputsName() == null
        || build.closureArtifacts() == null
        || build.closureArtifacts().isEmpty()) {
      transition(entity, "FAILED", Map.of("failure_code", "EXECUTION_CLOSURE_INPUTS_MISSING"));
      return;
    }
    try {
      Path root = Path.of(stagingRoot()).toAbsolutePath().normalize();
      Files.createDirectories(root);
      String jarSha = hash(build.jarBytes());
      String sbomSha = hash(build.sbomBytes());
      String certificationSha = hash(build.certificationBytes());
      Map<String, Object> manifest = new LinkedHashMap<>();
      manifest.put("schema", SCHEMA_V2);
      manifest.put("identity", read(entity.getIdentityJson()));
      manifest.put("key_id", signingKeyId);
      manifest.put("signature_algorithm", "Ed25519");
      manifest.put("base_commit", entity.getBaseCommit());
      manifest.put("baseline_fail_manifest_hash", entity.getFailureManifestHash());
      manifest.put("source_snapshot_id", entity.getSourceSnapshotId());
      manifest.put("source_manifest_hash", entity.getSourceManifestHash());
      manifest.put("patch_hash", entity.getPatchHash());
      manifest.put("proposal_hash", entity.getProposalHash());
      manifest.put("approval_hash", entity.getApprovalHash());
      manifest.put("post_patch_test_result_hash", hash(build.testReportBytes()));
      manifest.put("build_commands", BUILD_GATES);
      manifest.put("jar_name", build.jarName());
      manifest.put("jar_size", (long) build.jarBytes().length);
      manifest.put("jar_sha256", jarSha);
      manifest.put("sbom_name", build.sbomName());
      manifest.put("sbom_size", (long) build.sbomBytes().length);
      manifest.put("sbom_sha256", sbomSha);
      manifest.put("certification_document_hash", certificationSha);
      manifest.put("control_allowed", false);
      Map<String, Object> candidate = new LinkedHashMap<>();
      candidate.put("certification_hash", certificationSha);
      manifest.put("candidate", candidate);
      // Validate all unsigned fields, nested identity groups, certification bindings, and
      // artifact evidence before creating any signature bytes.  The final signed document is
      // validated again below with the real derived candidate identity and signature.
      candidateValidator.validateBeforeSigning(
          mapper.valueToTree(manifest),
          build.certificationBytes(),
          build.jarBytes(),
          build.sbomBytes());
      CandidateSigner signer = new CandidateSigner(signingKeyId, Path.of(signingKeyPath));
      String candidateHash = signer.unsignedHash(mapper.valueToTree(manifest));
      candidate.put("candidate_hash", candidateHash);
      candidate.put("candidate_id", "cand_" + candidateHash.substring(7, 31));
      candidate.put("signature", signer.signCandidateHash(candidateHash));
      String candidateId = String.valueOf(candidate.get("candidate_id"));
      byte[] manifestBytes = write(manifest).getBytes(java.nio.charset.StandardCharsets.UTF_8);
      candidateValidator.validate(
          manifestBytes, build.certificationBytes(), build.jarBytes(), build.sbomBytes());
      JsonNode signedManifest = mapper.valueToTree(manifest);
      CandidateExecutionClosureV1.Inputs closureInputs = closureInputs(build);
      byte[] closureBytes =
          CandidateExecutionClosureV1.build(
              signedManifest, build.jarBytes(), closureInputs, signer);
      CandidateExecutionClosureV1.validate(
          closureBytes,
          signedManifest,
          build.jarBytes(),
          closureInputs.runner().bytes(),
          closureInputs.core().bytes(),
          closureInputs.dependencies().stream()
              .map(CandidateExecutionClosureV1.ArtifactBytes::bytes)
              .toList(),
          candidateValidator.configuredPublicKey());
      Path finalBundle = root.resolve(candidateId);
      if (Files.exists(finalBundle)) {
        // Reuse only an identical bundle; never overwrite a persisted candidate.
        verifyBundleContents(finalBundle, build, manifestBytes, closureBytes, candidateId);
      } else {
        finalBundle = stageBundle(root, build, manifestBytes, closureBytes, candidateId);
      }
      // Registration fingerprint binds the canonical manifest and every artifact evidence field.
      ObjectNode fingerprint = mapper.createObjectNode();
      fingerprint.set("manifest", mapper.valueToTree(manifest));
      fingerprint.put("jar_sha256", jarSha);
      fingerprint.put("jar_size", (long) build.jarBytes().length);
      fingerprint.put("sbom_sha256", sbomSha);
      fingerprint.put("sbom_size", (long) build.sbomBytes().length);
      fingerprint.put("certification_document_hash", certificationSha);
      fingerprint.put("execution_closure_sha256", hash(closureBytes));
      fingerprint.put("execution_closure_size", (long) closureBytes.length);
      String requestHash = CanonicalJsonHasher.hash(fingerprint, null);
      CurrentSliceIdentity frozen =
          mapper.convertValue(read(entity.getIdentityJson()), CurrentSliceIdentity.class);
      CurrentSliceIdentity.Candidate candidateIdentity =
          new CurrentSliceIdentity.Candidate(
              candidateId,
              candidateHash,
              certificationSha,
              String.valueOf(candidate.get("signature")));
      CurrentSliceIdentity finalIdentity =
          new CurrentSliceIdentity(
              frozen.schema(),
              frozen.task(),
              frozen.run(),
              frozen.client(),
              frozen.capture(),
              frozen.source(),
              frozen.graph(),
              frozen.model(),
              candidateIdentity,
              false);
      boolean committed =
          dispatcher.commitRegistration(
              id,
              candidateId,
              candidateHash,
              write(manifest),
              write(finalIdentity),
              finalBundle.toAbsolutePath().normalize().toString(),
              requestHash,
              UUID.randomUUID().toString(),
              hash(manifestBytes),
              jarSha,
              build.jarBytes().length,
              sbomSha,
              build.sbomBytes().length,
              certificationSha);
      if (!committed) {
        transition(entity, "FAILED", Map.of("failure_code", "REGISTRATION_STATE_STALE"));
      }
    } catch (Exception e) {
      transition(
          entity,
          "FAILED",
          Map.of(
              "failure_code",
              e.getMessage() == null
                  ? "CANDIDATE_REGISTRATION_PREPARATION_FAILED"
                  : String.valueOf(e.getMessage())));
    }
  }

  /**
   * Maps the build's closure inputs document and JAR bytes onto the producer contract. Every JAR
   * named by the document must exist exactly once in the collected build output.
   */
  static CandidateExecutionClosureV1.Inputs closureInputs(IsolatedPatchWorker.BuildResult build)
      throws IOException {
    JsonNode inputs;
    try {
      inputs = Sc967CanonicalJson.parse(build.closureInputsBytes());
    } catch (IllegalArgumentException invalid) {
      throw new IOException("EXECUTION_CLOSURE_INPUTS_INVALID", invalid);
    }
    if (!"sc2106-execution-closure-inputs/v1".equals(requiredText(inputs, "schema"))) {
      throw new IOException("EXECUTION_CLOSURE_INPUTS_INVALID");
    }
    Map<String, byte[]> artifacts = new LinkedHashMap<>();
    for (IsolatedPatchWorker.ClosureArtifact artifact : build.closureArtifacts()) {
      if (artifacts.put(artifact.name(), artifact.bytes()) != null) {
        throw new IOException("EXECUTION_CLOSURE_JAR_AMBIGUOUS:" + artifact.name());
      }
    }
    String runnerName = requiredText(inputs, "runnerJarName");
    String coreName = requiredText(inputs, "coreJarName");
    byte[] runner = artifacts.get(runnerName);
    byte[] core = artifacts.get(coreName);
    if (runner == null || core == null) {
      throw new IOException("EXECUTION_CLOSURE_JAR_MISSING");
    }
    List<CandidateExecutionClosureV1.ArtifactBytes> dependencies = new ArrayList<>();
    for (JsonNode dependency : inputs.path("dependencyJarNames")) {
      if (!dependency.isTextual()) throw new IOException("EXECUTION_CLOSURE_INPUTS_INVALID");
      String name = dependency.asText();
      byte[] bytes = artifacts.get(name);
      if (bytes == null) throw new IOException("EXECUTION_CLOSURE_JAR_MISSING:" + name);
      dependencies.add(new CandidateExecutionClosureV1.ArtifactBytes(name, bytes));
    }
    if (dependencies.isEmpty()) throw new IOException("EXECUTION_CLOSURE_DEPENDENCIES_INCOMPLETE");
    return new CandidateExecutionClosureV1.Inputs(
        requiredText(inputs, "taskCoreEntry"),
        requiredText(inputs, "runnerVersion"),
        requiredText(inputs, "adapterVersion"),
        requiredText(inputs, "protocolVersion"),
        new CandidateExecutionClosureV1.ArtifactBytes(runnerName, runner),
        requiredText(inputs, "entrypoint"),
        requiredText(inputs, "coreId"),
        requiredText(inputs, "coreVersion"),
        requiredText(inputs, "coreSourceDigest"),
        new CandidateExecutionClosureV1.ArtifactBytes(coreName, core),
        requiredText(inputs, "coreEntryClass"),
        dependencies);
  }

  /** Re-runs the consumer's exact closure rules against persisted bytes before registry I/O. */
  private void validateExecutionClosure(
      byte[] closureBytes, byte[] manifestBytes, byte[] jarBytes, Map<String, byte[]> artifacts)
      throws IOException {
    JsonNode closure = Sc967CanonicalJson.parse(closureBytes);
    String runnerName = requiredText(closure.path("package"), "name");
    String coreName = requiredText(closure.path("core"), "name");
    byte[] runner = artifacts.get(runnerName);
    byte[] core = artifacts.get(coreName);
    if (runner == null || core == null) throw new IOException("EXECUTION_CLOSURE_JAR_MISSING");
    List<byte[]> dependencies = new ArrayList<>();
    for (JsonNode dependency : closure.path("dependencies")) {
      String name = requiredText(dependency, "name");
      byte[] bytes = artifacts.get(name);
      if (bytes == null) throw new IOException("EXECUTION_CLOSURE_JAR_MISSING:" + name);
      dependencies.add(bytes);
    }
    CandidateExecutionClosureV1.validate(
        closureBytes,
        Sc967CanonicalJson.parse(manifestBytes),
        jarBytes,
        runner,
        core,
        dependencies,
        candidateValidator.configuredPublicKey());
  }

  private static byte[] readClosureArtifact(Path bundle, String name) throws IOException {
    if (!name.matches("[A-Za-z0-9._-]+\\.jar")) {
      throw new IOException("EXECUTION_CLOSURE_JAR_NAME_INVALID");
    }
    Path path = bundle.resolve(name);
    if (!Files.isRegularFile(path) || Files.isSymbolicLink(path)) {
      throw new IOException("CANDIDATE_STAGING_MISSING");
    }
    return Files.readAllBytes(path);
  }

  private static String requiredText(JsonNode node, String field) throws IOException {
    JsonNode value = node == null ? null : node.get(field);
    if (value == null || !value.isTextual() || value.textValue().isBlank()) {
      throw new IOException("EXECUTION_CLOSURE_INVALID:" + field);
    }
    return value.textValue();
  }

  /**
   * Writes the complete bundle into a private temp directory and atomically renames it into place.
   */
  private Path stageBundle(
      Path root,
      IsolatedPatchWorker.BuildResult build,
      byte[] manifestBytes,
      byte[] closureBytes,
      String candidateId)
      throws IOException {
    Path tmp = Files.createDirectories(root.resolve(".tmp").resolve(UUID.randomUUID().toString()));
    try {
      writeNew(tmp.resolve(build.jarName()), build.jarBytes());
      writeNew(tmp.resolve(build.sbomName()), build.sbomBytes());
      writeNew(tmp.resolve("certification.json"), build.certificationBytes());
      writeNew(tmp.resolve("manifest.json"), manifestBytes);
      writeNew(tmp.resolve("execution-closure.json"), closureBytes);
      for (IsolatedPatchWorker.ClosureArtifact artifact : build.closureArtifacts()) {
        writeNew(tmp.resolve(artifact.name()), artifact.bytes());
      }
      // Re-read and verify every staged byte before the rename.
      verifyBundleContents(tmp, build, manifestBytes, closureBytes, candidateId);
      Path target = root.resolve(candidateId);
      try {
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
      } catch (java.nio.file.AtomicMoveNotSupportedException e) {
        throw new IOException("STAGING_ATOMIC_MOVE_UNAVAILABLE", e);
      }
      return target;
    } finally {
      deleteBundle(tmp);
    }
  }

  /** Exclusive create: the file must not exist; existing bytes are never replaced. */
  private static void writeNew(Path target, byte[] bytes) throws IOException {
    try (var channel =
        java.nio.channels.FileChannel.open(
            target,
            java.nio.file.StandardOpenOption.CREATE_NEW,
            java.nio.file.StandardOpenOption.WRITE)) {
      java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes);
      while (buffer.hasRemaining()) channel.write(buffer);
      channel.force(true);
    }
  }

  /**
   * Verifies every persisted bundle byte against the built evidence and the exact manifest bytes.
   */
  private void verifyBundleContents(
      Path bundle,
      IsolatedPatchWorker.BuildResult build,
      byte[] manifestBytes,
      byte[] closureBytes,
      String candidateId)
      throws IOException {
    boolean finalBundle = bundle.getFileName().toString().equals(candidateId);
    boolean privateTemporaryBundle =
        bundle.getParent() != null && ".tmp".equals(bundle.getParent().getFileName().toString());
    if ((!finalBundle && !privateTemporaryBundle)
        || !bundle
            .toAbsolutePath()
            .normalize()
            .startsWith(Path.of(stagingRoot()).toAbsolutePath().normalize())) {
      throw new IOException("STAGING_PATH_INVALID");
    }
    Path jar = bundle.resolve(build.jarName());
    Path sbom = bundle.resolve(build.sbomName());
    Path cert = bundle.resolve("certification.json");
    Path manifestPath = bundle.resolve("manifest.json");
    Path closurePath = bundle.resolve("execution-closure.json");
    List<Path> required = new ArrayList<>(List.of(jar, sbom, cert, manifestPath, closurePath));
    for (IsolatedPatchWorker.ClosureArtifact artifact : build.closureArtifacts()) {
      required.add(bundle.resolve(artifact.name()));
    }
    for (Path path : required) {
      if (!Files.isRegularFile(path)) throw new IOException("CANDIDATE_STAGING_MISSING");
      if (Files.isSymbolicLink(path)) throw new IOException("STAGING_PATH_INVALID");
    }
    byte[] jarBytes = Files.readAllBytes(jar);
    byte[] sbomBytes = Files.readAllBytes(sbom);
    byte[] certificationBytes = Files.readAllBytes(cert);
    byte[] persistedManifestBytes = Files.readAllBytes(manifestPath);
    byte[] persistedClosureBytes = Files.readAllBytes(closurePath);
    if (!java.util.Arrays.equals(jarBytes, build.jarBytes())
        || !java.util.Arrays.equals(sbomBytes, build.sbomBytes())
        || !java.util.Arrays.equals(certificationBytes, build.certificationBytes())
        || !java.util.Arrays.equals(persistedManifestBytes, manifestBytes)
        || !java.util.Arrays.equals(persistedClosureBytes, closureBytes)) {
      throw new IOException("STAGING_BUNDLE_CONFLICT");
    }
    Map<String, byte[]> closureArtifacts = new LinkedHashMap<>();
    for (IsolatedPatchWorker.ClosureArtifact artifact : build.closureArtifacts()) {
      byte[] persisted = Files.readAllBytes(bundle.resolve(artifact.name()));
      if (!java.util.Arrays.equals(persisted, artifact.bytes())) {
        throw new IOException("STAGING_BUNDLE_CONFLICT");
      }
      closureArtifacts.put(artifact.name(), persisted);
    }
    try {
      candidateValidator.validate(persistedManifestBytes, certificationBytes, jarBytes, sbomBytes);
      validateExecutionClosure(
          persistedClosureBytes, persistedManifestBytes, jarBytes, closureArtifacts);
    } catch (IllegalArgumentException e) {
      throw new IOException(e.getMessage(), e);
    }
  }

  /**
   * Revalidates the persisted registration bundle before any external registry action: the stored
   * path must stay under the configured staging root, the final directory name must equal the
   * stored candidate id, and every byte must still hash to the persisted component digests and
   * manifest file hash.
   */
  private Path revalidatePersistedBundle(PatchCandidateRun entity) throws IOException {
    if (entity.getCandidateStagingDirectory() == null
        || entity.getCandidateId() == null
        || entity.getManifestFileHash() == null
        || entity.getJarSha256() == null
        || entity.getSbomSha256() == null
        || entity.getCertificationDocumentHash() == null) {
      throw new IOException("CANDIDATE_EVIDENCE_MISSING");
    }
    Path root = Path.of(stagingRoot()).toAbsolutePath().normalize();
    Path bundle = Path.of(entity.getCandidateStagingDirectory()).toAbsolutePath().normalize();
    if (!bundle.startsWith(root)
        || !bundle.getFileName().toString().equals(entity.getCandidateId())) {
      throw new IOException("STAGING_PATH_INVALID");
    }
    Map<String, Object> manifest = read(entity.getCandidateManifestJson());
    Path jar = bundle.resolve(String.valueOf(manifest.get("jar_name")));
    Path sbom = bundle.resolve(String.valueOf(manifest.get("sbom_name")));
    Path cert = bundle.resolve("certification.json");
    Path manifestPath = bundle.resolve("manifest.json");
    Path closurePath = bundle.resolve("execution-closure.json");
    for (Path path : List.of(jar, sbom, cert, manifestPath, closurePath)) {
      if (!Files.isRegularFile(path) || Files.isSymbolicLink(path))
        throw new IOException("CANDIDATE_STAGING_MISSING");
    }
    byte[] manifestBytes = Files.readAllBytes(manifestPath);
    byte[] jarBytes = Files.readAllBytes(jar);
    byte[] sbomBytes = Files.readAllBytes(sbom);
    byte[] certificationBytes = Files.readAllBytes(cert);
    byte[] closureBytes = Files.readAllBytes(closurePath);
    if (!entity.getManifestFileHash().equals(hash(manifestBytes))
        || !entity.getJarSha256().equals(hash(jarBytes))
        || !entity.getSbomSha256().equals(hash(sbomBytes))
        || !entity.getCertificationDocumentHash().equals(hash(certificationBytes))) {
      throw new IOException("STAGING_BUNDLE_TAMPERED");
    }
    try {
      candidateValidator.validate(manifestBytes, certificationBytes, jarBytes, sbomBytes);
      JsonNode closure = Sc967CanonicalJson.parse(closureBytes);
      Map<String, byte[]> artifacts = new LinkedHashMap<>();
      artifacts.put(
          requiredText(closure.path("package"), "name"),
          readClosureArtifact(bundle, requiredText(closure.path("package"), "name")));
      artifacts.put(
          requiredText(closure.path("core"), "name"),
          readClosureArtifact(bundle, requiredText(closure.path("core"), "name")));
      for (JsonNode dependency : closure.path("dependencies")) {
        String name = requiredText(dependency, "name");
        artifacts.put(name, readClosureArtifact(bundle, name));
      }
      validateExecutionClosure(closureBytes, manifestBytes, jarBytes, artifacts);
    } catch (IllegalArgumentException e) {
      throw new IOException(e.getMessage(), e);
    }
    return bundle;
  }

  /**
   * Performs the registry POST using the exact staged bundle; runs outside any database
   * transaction.
   */
  public void postPreparedRegistration(String id) {
    PatchCandidateRun entity = repository.findByProposalId(id).orElse(null);
    if (entity == null || !"REGISTERING".equals(entity.getState())) {
      return;
    }
    requireCurrentCandidate(entity);
    PatchCandidateRegistrationDispatcher.RegistrationClaim claim = claimRegistration(id);
    if (registrationDispatcher != null && claim == null) {
      return;
    }
    entity = repository.findByProposalId(id).orElse(null);
    if (entity == null || !"REGISTERING".equals(entity.getState())) {
      return;
    }
    if (entity.getCandidateStagingDirectory() == null
        || entity.getCandidateId() == null
        || entity.getCandidateHash() == null) {
      transitionClaimed(
          entity, claim, "FAILED", Map.of("failure_code", "CANDIDATE_STAGING_MISSING"), null);
      return;
    }
    CandidateRegistryClient.RegistryManifestResponse registration = null;
    CandidateRegistryClient.RegistryManifestResponse lookup = null;
    CandidateRegistryClient.ArtifactDownload downloaded = null;
    try {
      requireCurrentCandidate(entity);
      Path bundle = revalidatePersistedBundle(entity);
      Map<String, Object> manifest = read(entity.getCandidateManifestJson());
      Path manifestPath = bundle.resolve("manifest.json");
      Path jar = bundle.resolve(String.valueOf(manifest.get("jar_name")));
      Path sbom = bundle.resolve(String.valueOf(manifest.get("sbom_name")));
      Path cert = bundle.resolve("certification.json");
      registration = registry.registerEvidence(manifestPath, jar, sbom, cert);
      verifyRegistryReadback(
          Files.readAllBytes(manifestPath),
          manifest,
          registration,
          entity.getCandidateId(),
          entity.getCandidateHash(),
          entity.getManifestFileHash());
      lookup = registry.lookupEvidence(entity.getCandidateId(), registration.manifestHash());
      verifyRegistryReadback(
          Files.readAllBytes(manifestPath),
          manifest,
          lookup,
          entity.getCandidateId(),
          entity.getCandidateHash(),
          entity.getManifestFileHash());
      downloaded = registry.downloadEvidence(entity.getCandidateId());
      if (!downloaded.sha256().equals(String.valueOf(manifest.get("jar_sha256")))) {
        throw new IllegalStateException("REGISTRY_ARTIFACT_HASH_MISMATCH");
      }
      transitionClaimed(
          entity,
          claim,
          "REGISTERED",
          Map.of(
              "candidate_id",
              entity.getCandidateId(),
              "candidate_hash",
              entity.getCandidateHash(),
              "registration",
              new String(registration.rawManifest(), java.nio.charset.StandardCharsets.UTF_8),
              "download_sha256",
              downloaded.sha256(),
              "registry_manifest_hash",
              registration.manifestHash(),
              "lookup_manifest",
              new String(lookup.rawManifest(), java.nio.charset.StandardCharsets.UTF_8)),
          evidence(registration, lookup, downloaded));
    } catch (CandidateRegistryClient.RegistryException e) {
      boolean notFound = e.getMessage() != null && e.getMessage().contains("404");
      transitionClaimed(
          entity,
          claim,
          e.isUncertain() ? "REGISTERING_UNCERTAIN" : (notFound ? "REGISTERING" : "REJECTED"),
          Map.of("failure_code", String.valueOf(e.getMessage())),
          evidence(registration, lookup, downloaded));
    } catch (Exception e) {
      transitionClaimed(
          entity,
          claim,
          "FAILED",
          Map.of(
              "failure_code",
              e.getMessage() == null
                  ? "CANDIDATE_REGISTRATION_FAILED"
                  : String.valueOf(e.getMessage())),
          evidence(registration, lookup, downloaded));
    }
  }

  /**
   * Recovery scanner: requeues expired worker leases and abandoned queued records after restart.
   */
  @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 60_000L)
  @Transactional
  public void recover() {
    if (!buildsEnabled()) {
      // While disabled, no build can ever be queued or recovered; nothing is dispatched.
      return;
    }
    List<String> buildQueue = new java.util.ArrayList<>();
    List<String> registrations = new java.util.ArrayList<>();
    Instant now = Instant.now();
    for (PatchCandidateRun candidate :
        repository.findByStateIn(
            List.of("BUILD_QUEUED", "BUILDING", "REGISTERING", "REGISTERING_UNCERTAIN"))) {
      // The scanner query is only a work list. Never mutate or save that detached/stale instance;
      // reacquire the row with a database lock before changing a lease or deciding what to run.
      PatchCandidateRun current =
          repository.findWithLockByProposalId(candidate.getProposalId()).orElse(null);
      if (current == null) continue;
      String state = current.getState();
      if ("BUILD_QUEUED".equals(state)) {
        buildQueue.add(current.getProposalId());
      } else if ("BUILDING".equals(state)
          && current.getWorkerLeaseExpiresAt() != null
          && current.getWorkerLeaseExpiresAt().isBefore(now)) {
        // The locked dispatcher is the compare-and-set owner for lease recovery.
        if (registrationDispatcher == null
            ? requeueExpiredBuildFallback(current)
            : registrationDispatcher.requeueExpiredBuild(current.getProposalId(), now)) {
          buildQueue.add(current.getProposalId());
        }
      } else if ("REGISTERING".equals(state)) {
        registrations.add(current.getProposalId());
      } else if ("REGISTERING_UNCERTAIN".equals(state)) {
        registrations.add(current.getProposalId());
      }
    }
    Runnable afterCommit =
        () -> {
          buildQueue.forEach(this::offerBuild);
          registrations.forEach(
              id -> {
                PatchCandidateRun row = repository.findByProposalId(id).orElse(null);
                if (row == null) return;
                if ("REGISTERING".equals(row.getState())) postPreparedRegistration(id);
                else if ("REGISTERING_UNCERTAIN".equals(row.getState())) lookupUncertain(id);
              });
        };
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              afterCommit.run();
            }
          });
    } else {
      afterCommit.run();
    }
  }

  private boolean requeueExpiredBuildFallback(PatchCandidateRun candidate) {
    if (candidate == null || !"BUILDING".equals(candidate.getState())) return false;
    candidate.setState("BUILD_QUEUED");
    candidate.setWorkerAttemptId(null);
    candidate.setWorkerLeaseExpiresAt(null);
    repository.save(candidate);
    return true;
  }

  @SuppressWarnings("unchecked")
  private void persistCandidateRunIdentity(PatchCandidateRun entity, String stage) {
    if (entity.getFinalIdentityJson() == null) return;
    try {
      CurrentSliceIdentity identity =
          mapper.readValue(entity.getFinalIdentityJson(), CurrentSliceIdentity.class);
      if (identity.run() != null && identity.run().runId() != null) {
        runService.advanceIdentity(identity.run().runId(), identity, stage);
      }
    } catch (Exception e) {
      throw error(
          HttpStatus.INTERNAL_SERVER_ERROR,
          "PERSISTED_JSON_CORRUPT",
          "final candidate identity is corrupt");
    }
  }

  private void verifyRegistryReadback(
      byte[] stagedManifest,
      Map<String, Object> localManifest,
      CandidateRegistryClient.RegistryManifestResponse registration,
      String candidateId,
      String candidateHash,
      String expectedManifestFileHash) {
    try {
      if (stagedManifest == null || registration == null || registration.rawManifest() == null) {
        throw new IllegalStateException("REGISTRY_READBACK_INVALID");
      }
      if (!java.util.Arrays.equals(stagedManifest, registration.rawManifest())) {
        throw new IllegalStateException("REGISTRY_MANIFEST_MISMATCH");
      }
      if (expectedManifestFileHash == null
          || !expectedManifestFileHash.equals(registration.manifestHash())) {
        throw new IllegalStateException("REGISTRY_MANIFEST_HASH_HEADER_MISMATCH");
      }
      if (!registration
          .manifestHash()
          .equals(CandidateRegistryClient.hash(registration.rawManifest()))) {
        throw new IllegalStateException("REGISTRY_MANIFEST_HASH_HEADER_MISMATCH");
      }
      JsonNode returned = candidateValidator.validateRegistryManifest(registration.rawManifest());
      if (!candidateId.equals(returned.path("candidate").path("candidate_id").asText())
          || !candidateHash.equals(returned.path("candidate").path("candidate_hash").asText()))
        throw new IllegalStateException("REGISTRY_IDENTITY_MISMATCH");
      if (!Sc967CanonicalJson.canonical(mapper.valueToTree(localManifest))
          .equals(Sc967CanonicalJson.canonical(returned)))
        throw new IllegalStateException("REGISTRY_MANIFEST_MISMATCH");
    } catch (CandidateRegistryClient.RegistryException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException(
          e.getMessage() == null ? "REGISTRY_READBACK_INVALID" : e.getMessage(), e);
    }
  }

  private PatchCandidateRegistrationDispatcher.RegistryEvidence evidence(
      CandidateRegistryClient.RegistryManifestResponse response,
      CandidateRegistryClient.ArtifactDownload download) {
    if (response == null && download == null) return null;
    return new PatchCandidateRegistrationDispatcher.RegistryEvidence(
        response == null ? null : response.rawManifest(),
        response == null
            ? null
            : new String(response.rawManifest(), java.nio.charset.StandardCharsets.UTF_8),
        response == null ? null : response.manifestHash(),
        download == null ? null : download.sha256(),
        Instant.now());
  }

  private PatchCandidateRegistrationDispatcher.RegistryEvidence evidence(
      CandidateRegistryClient.RegistryManifestResponse registration,
      CandidateRegistryClient.RegistryManifestResponse lookup,
      CandidateRegistryClient.ArtifactDownload download) {
    if (registration == null && lookup == null && download == null) return null;
    CandidateRegistryClient.RegistryManifestResponse primary =
        registration == null ? lookup : registration;
    return new PatchCandidateRegistrationDispatcher.RegistryEvidence(
        primary == null ? null : primary.rawManifest(),
        primary == null
            ? null
            : new String(primary.rawManifest(), java.nio.charset.StandardCharsets.UTF_8),
        primary == null ? null : primary.manifestHash(),
        download == null ? null : download.sha256(),
        Instant.now(),
        lookup == null ? null : lookup.rawManifest(),
        lookup == null ? null : lookup.manifestHash());
  }

  private PatchCandidateRegistrationDispatcher.RegistryEvidence evidenceForLookup(
      PatchCandidateRun entity,
      CandidateRegistryClient.RegistryManifestResponse lookup,
      CandidateRegistryClient.ArtifactDownload download) {
    if (lookup == null && download == null && entity.getRegistryResponseBytes() == null)
      return null;
    byte[] registrationBytes = entity.getRegistryResponseBytes();
    String registrationHash = entity.getRegistryManifestHash();
    if (registrationBytes == null) registrationBytes = lookup == null ? null : lookup.rawManifest();
    if (registrationHash == null) registrationHash = lookup == null ? null : lookup.manifestHash();
    return new PatchCandidateRegistrationDispatcher.RegistryEvidence(
        registrationBytes,
        registrationBytes == null
            ? null
            : new String(registrationBytes, java.nio.charset.StandardCharsets.UTF_8),
        registrationHash,
        download == null ? null : download.sha256(),
        Instant.now(),
        lookup == null ? null : lookup.rawManifest(),
        lookup == null ? null : lookup.manifestHash());
  }

  private boolean deleteBundle(Path bundle) {
    try (var stream = Files.walk(bundle)) {
      final boolean[] clean = {true};
      stream
          .sorted(java.util.Comparator.reverseOrder())
          .forEach(
              path -> {
                try {
                  Files.deleteIfExists(path);
                } catch (Exception e) {
                  clean[0] = false;
                }
              });
      return clean[0] && !Files.exists(bundle);
    } catch (Exception e) {
      return false;
    }
  }

  private static String hash(byte[] bytes) {
    try {
      byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
      StringBuilder value = new StringBuilder("sha256:");
      for (byte b : digest) value.append(String.format("%02x", b));
      return value.toString();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private PatchCandidateRun proposal(String id) {
    return repository
        .findByProposalId(id)
        .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "NOT_FOUND", "patch proposal not found"));
  }

  private PatchCandidateRun lockedProposal(String id) {
    return repository
        .findWithLockByProposalId(id)
        .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "NOT_FOUND", "patch proposal not found"));
  }

  @SuppressWarnings("unchecked")
  private String modelHash(String identityJson) {
    Map<String, Object> identity = read(identityJson);
    Object model = identity.get("model");
    if (!(model instanceof Map))
      throw error(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "CANDIDATE_IDENTITY_INVALID",
          "model identity is required");
    Object value = ((Map<String, Object>) model).get("model_hash");
    if (value == null || String.valueOf(value).isBlank())
      throw error(
          HttpStatus.UNPROCESSABLE_ENTITY, "CANDIDATE_IDENTITY_INVALID", "model_hash is required");
    return String.valueOf(value);
  }

  private JsonNode parse(String json) {
    try {
      return mapper.readTree(json);
    } catch (Exception e) {
      throw error(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "SOURCE_MANIFEST_INVALID",
          "source manifest JSON is invalid");
    }
  }

  private String write(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (Exception e) {
      throw error(
          HttpStatus.UNPROCESSABLE_ENTITY, "PATCH_INVALID", "patch evidence is not serializable");
    }
  }

  private static String text(Map<String, Object> request, String key) {
    Object value = request.get(key);
    return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> read(String json) {
    try {
      return mapper.readValue(json, Map.class);
    } catch (Exception e) {
      throw error(
          HttpStatus.INTERNAL_SERVER_ERROR,
          "PERSISTED_JSON_CORRUPT",
          "persisted patch evidence is corrupt");
    }
  }

  private Map<String, Object> readOptional(String json) {
    return json == null ? Map.of() : read(json);
  }

  private Map<String, Object> response(PatchCandidateRun entity) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("proposal_id", entity.getProposalId());
    result.put("status", entity.getState());
    result.put("failure_binding", read(entity.getBindingJson()));
    result.put(
        "identity",
        read(
            entity.getFinalIdentityJson() == null
                ? entity.getIdentityJson()
                : entity.getFinalIdentityJson()));
    result.put("identity_through_model", read(entity.getIdentityJson()));
    result.put("base_commit", entity.getBaseCommit());
    result.put("patch", entity.getPatchText());
    result.put("patch_hash", entity.getPatchHash());
    result.put("proposal_hash", entity.getProposalHash());
    result.put("approver_id", entity.getApproverId());
    result.put("approved_at", entity.getApprovedAt());
    result.put("approval_hash", entity.getApprovalHash());
    result.put("candidate_id", entity.getCandidateId());
    result.put("candidate_hash", entity.getCandidateHash());
    result.put(
        "candidate_manifest",
        entity.getCandidateManifestJson() == null ? null : read(entity.getCandidateManifestJson()));
    result.put("result", readOptional(entity.getResultJson()));
    result.put("worker_attempt_id", entity.getWorkerAttemptId());
    result.put("worker_lease_expires_at", entity.getWorkerLeaseExpiresAt());
    result.put("registration_attempt_id", entity.getRegistrationAttemptId());
    result.put("manifest_file_hash", entity.getManifestFileHash());
    result.put("registry_response_hash", entity.getRegistryResponseHash());
    result.put("registry_manifest_hash", entity.getRegistryManifestHash());
    result.put("registry_download_hash", entity.getRegistryDownloadHash());
    result.put("registry_verified_at", entity.getRegistryVerifiedAt());
    result.put("control_allowed", false);
    return result;
  }

  private static PatchWorkflowException error(HttpStatus status, String code, String message) {
    return new PatchWorkflowException(status, code, message);
  }

  public static final class PatchWorkflowException extends RuntimeException {
    final HttpStatus status;
    final String code;

    PatchWorkflowException(HttpStatus status, String code, String message) {
      super(message);
      this.status = status;
      this.code = code;
    }

    public HttpStatus status() {
      return status;
    }

    public String code() {
      return code;
    }
  }
}
