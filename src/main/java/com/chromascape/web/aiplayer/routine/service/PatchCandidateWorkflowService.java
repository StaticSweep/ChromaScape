package com.chromascape.web.aiplayer.routine.service;

import com.chromascape.web.aiplayer.contract.CanonicalJsonHasher;
import com.chromascape.web.aiplayer.routine.entity.PatchCandidateRun;
import com.chromascape.web.aiplayer.routine.repository.PatchCandidateRunRepository;
import com.chromascape.web.aiplayer.routine.repository.CurrentSliceRunRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.chromascape.web.aiplayer.contract.CurrentSliceIdentity;
import com.chromascape.web.aiplayer.contract.CurrentSliceIdentityValidator;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
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

@Service
public class PatchCandidateWorkflowService {
  private static final String SEEDED_FAIL = "sha256:b201629e712be24815e43551a5185a2b45fe3a0b77b2aca8f2a51dd250917956";
  private static final String SOURCE_FILE = "runelite-client/src/main/java/net/runelite/client/plugins/microbot/accountbuilder/builderPlanStep/fishing/FishingExecutionTask.java";
  private static final String TEST_FILE = "runelite-client/src/test/java/net/runelite/client/plugins/microbot/accountbuilder/builderPlanStep/fishing/FishingExecutionTaskTest.java";
  private static final String EXCEPTION_FILE = "runelite-client/src/main/java/net/runelite/client/plugins/microbot/accountbuilder/builderPlanStep/fishing/UnsupportedFishTypeException.java";
  private static final String PATCH = """
      diff --git a/runelite-client/src/main/java/net/runelite/client/plugins/microbot/accountbuilder/builderPlanStep/fishing/FishingExecutionTask.java b/runelite-client/src/main/java/net/runelite/client/plugins/microbot/accountbuilder/builderPlanStep/fishing/FishingExecutionTask.java
      --- a/runelite-client/src/main/java/net/runelite/client/plugins/microbot/accountbuilder/builderPlanStep/fishing/FishingExecutionTask.java
      +++ b/runelite-client/src/main/java/net/runelite/client/plugins/microbot/accountbuilder/builderPlanStep/fishing/FishingExecutionTask.java
      @@ -95,3 +95,6 @@
           private Fish getFishType(String fishName) {
      +        if (fishName == null || fishName.isBlank()) {
      +            throw new UnsupportedFishTypeException(fishName);
      +        }
               // Convert fish name to Fish enum - simple mapping
      -        switch (fishName.toLowerCase()) {
      +        switch (fishName.trim().toLowerCase()) {
      @@ -122,4 +125,3 @@
                   default:
      -        System.out.println("[FishingExecutionTask] Warning: Unknown fish name '" + fishName + "', defaulting to SHRIMP");
      -        return Fish.SHRIMP;
      +        throw new UnsupportedFishTypeException(fishName);
           }
       }
      diff --git a/runelite-client/src/main/java/net/runelite/client/plugins/microbot/accountbuilder/builderPlanStep/fishing/UnsupportedFishTypeException.java b/runelite-client/src/main/java/net/runelite/client/plugins/microbot/accountbuilder/builderPlanStep/fishing/UnsupportedFishTypeException.java
      new file mode 100644
      --- /dev/null
      +++ b/runelite-client/src/main/java/net/runelite/client/plugins/microbot/accountbuilder/builderPlanStep/fishing/UnsupportedFishTypeException.java
      @@ -0,0 +1,8 @@
      +package net.runelite.client.plugins.microbot.accountbuilder.builderPlanStep.fishing;
      +
      +/** Raised when a Fishing plan names a null, blank, or unsupported fish. */
      +public final class UnsupportedFishTypeException extends IllegalArgumentException {
      +  public UnsupportedFishTypeException(String fishName) {
      +    super("Unsupported fish type: " + fishName);
      +  }
      +}
      diff --git a/runelite-client/src/test/java/net/runelite/client/plugins/microbot/accountbuilder/builderPlanStep/fishing/FishingExecutionTaskTest.java b/runelite-client/src/test/java/net/runelite/client/plugins/microbot/accountbuilder/builderPlanStep/fishing/FishingExecutionTaskTest.java
      new file mode 100644
      --- /dev/null
      +++ b/runelite-client/src/test/java/net/runelite/client/plugins/microbot/accountbuilder/builderPlanStep/fishing/FishingExecutionTaskTest.java
      @@ -0,0 +1,25 @@
      +package net.runelite.client.plugins.microbot.accountbuilder.builderPlanStep.fishing;
      +
      +import net.runelite.api.coords.WorldPoint;
      +import org.junit.Test;
      +
      +public class FishingExecutionTaskTest {
      +  @Test(expected = UnsupportedFishTypeException.class)
      +  public void nullFishIsRejected() { new FishingExecutionTask(step(null)); }
      +
      +  @Test(expected = UnsupportedFishTypeException.class)
      +  public void blankFishIsRejected() { new FishingExecutionTask(step("  ")); }
      +
      +  @Test(expected = UnsupportedFishTypeException.class)
      +  public void unknownFishIsRejected() { new FishingExecutionTask(step("not-a-fish")); }
      +
      +  @Test
      +  public void canonicalFishRemainsSupported() {
      +    new FishingExecutionTask(step("Shrimp"));
      +    new FishingExecutionTask(step("raw shrimp"));
      +  }
      +
      +  private static FishingPlanStep step(String fish) {
      +    return new FishingPlanStep(1, 2, fish, 1, 10.0, 10, 1,
      +        new WorldPoint(3087, 3228, 0));
      +  }
      +}
      """;

  private final ObjectMapper mapper;
  private final IsolatedPatchWorker worker;
  private final PatchCandidateRunRepository repository;
  private final CurrentSliceRunRepository runs;
  private final Micro6SourceManifestClient source;
  private final CurrentSliceIdentityValidator identities;
  private final CandidateRegistryClient registry;
  @Value("${builder-goal.recovery.micro6-project-dir:../micro6}") private String micro6Root;
  @Value("${leeuwbot.signing.private-key-path:}") private String signingKeyPath;
  @Value("${leeuwbot.signing.key-id:}") private String signingKeyId;
  @Value("${micro6.expected-artifact-name:}") private String expectedArtifactName;
  private final ExecutorService buildExecutor = new ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(4), new ThreadPoolExecutor.AbortPolicy());

  public PatchCandidateWorkflowService(ObjectMapper mapper, IsolatedPatchWorker worker, PatchCandidateRunRepository repository, CurrentSliceRunRepository runs, Micro6SourceManifestClient source, CurrentSliceIdentityValidator identities, CandidateRegistryClient registry) {
    this.mapper = mapper; this.worker = worker; this.repository = repository; this.runs = runs; this.source = source; this.identities = identities; this.registry = registry;
  }

  @Transactional
  public Map<String, Object> fromFailure(Map<String, Object> request) {
    if (request == null || !SEEDED_FAIL.equals(String.valueOf(request.get("failure_manifest_hash")))) {
      throw error(HttpStatus.UNPROCESSABLE_ENTITY, "FAILURE_FIXTURE_MISMATCH", "only the canonical seeded SC-572 FAIL manifest is eligible");
    }
    if (!"UNSUPPORTED_FISH_DEFAULTED".equals(String.valueOf(request.get("failure_code")))) {
      throw error(HttpStatus.UNPROCESSABLE_ENTITY, "FAILURE_CODE_MISMATCH", "seeded failure code is unsupported");
    }
    Object identityValue = request.get("identity");
    if (identityValue == null) throw error(HttpStatus.UNPROCESSABLE_ENTITY, "CANDIDATE_IDENTITY_REQUIRED", "candidate identity through model is required");
    CurrentSliceIdentity candidateIdentity;
    try { candidateIdentity = identities.validate(mapper.convertValue(identityValue, CurrentSliceIdentity.class)); }
    catch (RuntimeException e) { throw error(HttpStatus.UNPROCESSABLE_ENTITY, "CANDIDATE_IDENTITY_INVALID", e.getMessage()); }
    if (candidateIdentity.candidate() != null) throw error(HttpStatus.UNPROCESSABLE_ENTITY, "CANDIDATE_IDENTITY_INVALID", "proposal identity must be frozen through model");
    if (candidateIdentity.source() == null || candidateIdentity.graph() == null || candidateIdentity.model() == null) {
      throw error(HttpStatus.UNPROCESSABLE_ENTITY, "CANDIDATE_IDENTITY_INCOMPLETE", "identity through graph and model is required before proposal creation");
    }
    Micro6SourceManifestClient.SourceBinding resolved = source.resolveFishingSource();
    String requestedBase = text(request, "base_commit");
    if (requestedBase != null && !requestedBase.equals(resolved.clientBuildId())) {
      throw error(HttpStatus.UNPROCESSABLE_ENTITY, "BASE_COMMIT_MISMATCH", "requested base commit differs from embedded Micro6 build identity");
    }
    String baseCommit = requestedBase == null ? resolved.clientBuildId() : requestedBase;
    if (baseCommit == null || baseCommit.isBlank() || "nogit".equals(baseCommit)) {
      throw error(HttpStatus.UNPROCESSABLE_ENTITY, "SOURCE_BINDING_INCOMPLETE", "embedded Micro6 build commit is required");
    }

    Map<String, Object> binding = new LinkedHashMap<>();
    binding.put("schema", "seeded-failure-source-binding/v1");
    binding.put("derivation", "SEEDED_FIXTURE");
    binding.put("failure_manifest_hash", SEEDED_FAIL);
    binding.put("failure_code", "UNSUPPORTED_FISH_DEFAULTED");
    binding.put("task_key", "FISHING_TRAINING");
    binding.put("source_snapshot_id", resolved.sourceSnapshotId());
    binding.put("source_manifest_hash", resolved.sourceManifestHash());
    binding.put("file_id", resolved.fileId());
    binding.put("relative_path", resolved.relativePath());
    binding.put("clone_relative_path", resolved.cloneRelativePath());
    binding.put("content_hash", resolved.contentHash());
    binding.put("manifest_json", parse(resolved.manifestJson()));
    binding.put("pinned_files", resolved.pinnedFiles());
    binding.put("symbol", "FishingExecutionTask#getFishType(String)");
    binding.put("start_line", resolved.startLine());
    binding.put("end_line", resolved.endLine());
    binding.put("preimage_hash", resolved.preimageHash());

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

  @Transactional
  public Map<String, Object> approve(String id, String approver) {
    PatchCandidateRun entity = proposal(id);
    if (approver == null || approver.isBlank()) throw error(HttpStatus.UNPROCESSABLE_ENTITY, "IDENTITY_FIELD_MISSING", "approver is required");
    if ("APPROVED".equals(entity.getState())) {
      if (approver.equals(entity.getApproverId())) return response(entity);
      throw error(HttpStatus.CONFLICT, "PATCH_APPROVAL_STALE", "proposal is already approved by another principal");
    }
    if (!"PATCH_PROPOSED".equals(entity.getState())) throw error(HttpStatus.CONFLICT, "PATCH_APPROVAL_STALE", "proposal is no longer approvable");
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
    approval.put("approver_id", approver);
    approval.put("approved_at", approvedAt.toString());
    entity.setApproverId(approver);
    entity.setApprovedAt(approvedAt);
    entity.setApprovalHash(CanonicalJsonHasher.hash(approval, null));
    entity.setState("APPROVED");
    repository.save(entity);
    return response(entity);
  }

  @Transactional
  public Map<String, Object> enqueueBuild(String id) {
    PatchCandidateRun entity = proposal(id);
    if (!"APPROVED".equals(entity.getState())) throw error(HttpStatus.CONFLICT, "PATCH_NOT_APPROVED", "explicit human approval is required");
    entity.setState("BUILDING");
    repository.save(entity);
    try {
      buildExecutor.execute(() -> build(id));
    } catch (java.util.concurrent.RejectedExecutionException e) {
      entity.setState("APPROVED");
      repository.save(entity);
      throw error(HttpStatus.CONFLICT, "BUILD_QUEUE_FULL", "approved build queue is full");
    }
    return response(entity);
  }

  public Map<String, Object> build(String id) {
    PatchCandidateRun entity = proposal(id);
    if (!"BUILDING".equals(entity.getState()) && !"APPROVED".equals(entity.getState())) throw error(HttpStatus.CONFLICT, "PATCH_NOT_APPROVED", "explicit human approval is required");
    if ("APPROVED".equals(entity.getState())) { entity.setState("BUILDING"); repository.save(entity); }
    entity.setWorkerStartedAt(Instant.now()); repository.save(entity);
    IsolatedPatchWorker.BuildResult result = worker.run(new IsolatedPatchWorker.BuildRequest(
        Path.of(micro6Root), entity.getBaseCommit(), entity.getPatchText(),
        Set.of(SOURCE_FILE, EXCEPTION_FILE, TEST_FILE), List.of(), entity.getSourceSnapshotId(),
        entity.getSourceRelativePath(), entity.getStartLine(), entity.getEndLine(), entity.getPreimageHash(), entity.getFailureManifestHash(),
        entity.getProposalHash(), entity.getApprovalHash(), entity.getPatchHash(), entity.getSourceManifestHash(), entity.getSourceManifestJson(), modelHash(entity.getIdentityJson()), expectedArtifactName));
    entity.setWorkerCompletedAt(Instant.now());
    entity.setState("BUILT".equals(result.status()) ? "BUILT" : "BUILD_FAILED");
    entity.setLastFailureCode(result.failureCode());
    Map<String, Object> buildResult = new LinkedHashMap<>();
    buildResult.put("status", result.status());
    buildResult.put("changed_files", result.changedFiles());
    buildResult.put("failure_code", result.failureCode());
    entity.setResultJson(write(buildResult));
    if ("BUILT".equals(result.status())) {
      entity.setState("REGISTERING");
      repository.save(entity);
      registerCandidate(entity, result);
    }
    repository.save(entity);
    return response(entity);
  }

  @Transactional(readOnly = true)
  public Map<String, Object> get(String id) { return response(proposal(id)); }

  @Transactional
  public Map<String, Object> reconcile(String id) {
    PatchCandidateRun entity = proposal(id);
    if (!"REGISTERING_UNCERTAIN".equals(entity.getState()) && !"REGISTERING".equals(entity.getState())) throw error(HttpStatus.CONFLICT, "RECONCILIATION_NOT_REQUIRED", "proposal is not awaiting registry reconciliation");
    if ("REGISTERING".equals(entity.getState())) return repostPersistedCandidate(entity);
    if (entity.getCandidateId() == null || entity.getCandidateHash() == null) throw error(HttpStatus.INTERNAL_SERVER_ERROR, "CANDIDATE_EVIDENCE_MISSING", "candidate identity was not persisted");
    try {
      JsonNode body = mapper.readTree(registry.lookup(entity.getCandidateId()));
      JsonNode manifest = body.path("manifest");
      JsonNode declaration = manifest.path("candidate");
      String returnedId = declaration.path("candidate_id").asText(manifest.path("candidate_id").asText());
      String returnedHash = declaration.path("candidate_hash").asText(manifest.path("candidate_hash").asText());
      if (!entity.getCandidateId().equals(returnedId) || !entity.getCandidateHash().equals(returnedHash)) {
        entity.setState("REJECTED"); entity.setResultJson(write(Map.of("failure_code", "REGISTRY_IDENTITY_MISMATCH")));
      } else if (!"CERTIFIED_CANDIDATE".equals(body.path("state").asText())) {
        entity.setState("REJECTED"); entity.setResultJson(write(Map.of("failure_code", "REGISTRY_STATE_INVALID")));
      } else {
        entity.setState("REGISTERED"); entity.setResultJson(write(Map.of("candidate_id", entity.getCandidateId(), "candidate_hash", entity.getCandidateHash(), "reconciled", true)));
      }
    } catch (CandidateRegistryClient.RegistryException e) {
      if (e.isUncertain()) entity.setState("REGISTERING_UNCERTAIN");
      else if (e.getMessage() != null && e.getMessage().contains("404")) entity.setState("REGISTERING");
      else entity.setState("REJECTED");
      entity.setResultJson(write(Map.of("failure_code", e.getMessage() == null ? "REGISTRY_RECONCILIATION_FAILED" : e.getMessage())));
    } catch (Exception e) {
      entity.setState("REJECTED"); entity.setResultJson(write(Map.of("failure_code", "REGISTRY_READBACK_INVALID")));
    }
    repository.save(entity);
    return response(entity);
  }

  private Map<String, Object> repostPersistedCandidate(PatchCandidateRun entity) {
    try {
      if (entity.getCandidateStagingDirectory() == null) throw new IllegalStateException("CANDIDATE_STAGING_MISSING");
      Path bundle = Path.of(entity.getCandidateStagingDirectory()).toAbsolutePath().normalize();
      Map<String, Object> manifest = read(entity.getCandidateManifestJson());
      Path manifestPath = bundle.resolve("manifest.json"); Path jar = bundle.resolve(String.valueOf(manifest.get("jar_name"))); Path sbom = bundle.resolve(String.valueOf(manifest.get("sbom_name"))); Path cert = bundle.resolve("certification.json");
      String registration = registry.register(manifestPath, jar, sbom, cert); entity.setRegistryResponseJson(registration); verifyRegistryReadback(manifest, registration, entity.getCandidateId(), entity.getCandidateHash());
      byte[] downloaded = registry.download(entity.getCandidateId());
      if (!hash(downloaded).equals(String.valueOf(manifest.get("jar_sha256")))) throw new IllegalStateException("REGISTRY_ARTIFACT_HASH_MISMATCH");
      entity.setState("REGISTERED"); persistCandidateRunIdentity(entity, "CANDIDATE_REGISTERED"); entity.setResultJson(write(Map.of("candidate_id", entity.getCandidateId(), "candidate_hash", entity.getCandidateHash(), "reconciled", true, "download_sha256", hash(downloaded))));
      if (!deleteBundle(bundle)) { entity.setState("FAILED"); entity.setLastFailureCode("CANDIDATE_BUNDLE_CLEANUP_FAILED"); }
    } catch (CandidateRegistryClient.RegistryException e) { entity.setState(e.isUncertain() ? "REGISTERING_UNCERTAIN" : (e.getMessage() != null && e.getMessage().contains("404") ? "REGISTERING" : "REJECTED")); entity.setLastFailureCode(e.getMessage()); entity.setResultJson(write(Map.of("failure_code", e.getMessage()))); }
    catch (Exception e) { entity.setState("FAILED"); entity.setLastFailureCode(e.getMessage()); entity.setResultJson(write(Map.of("failure_code", e.getMessage() == null ? "CANDIDATE_RECONCILIATION_FAILED" : e.getMessage()))); }
    repository.save(entity); return response(entity);
  }

  private void registerCandidate(PatchCandidateRun entity, IsolatedPatchWorker.BuildResult result) {
    if (signingKeyPath == null || signingKeyPath.isBlank() || signingKeyId == null || signingKeyId.isBlank()) {
      entity.setState("FAILED"); entity.setResultJson(write(Map.of("failure_code", "SIGNING_KEY_UNAVAILABLE"))); return;
    }
    if (result.jarBytes() == null || result.sbomBytes() == null || result.certificationBytes() == null) {
      entity.setState("FAILED"); entity.setResultJson(write(Map.of("failure_code", "CERTIFICATION_ARTIFACT_MISSING"))); return;
    }
    Path bundle = null;
    boolean retainBundle = false;
    try {
      bundle = Files.createTempDirectory("sc967-candidate-");
      Path jar = bundle.resolve(result.jarName()); Path sbom = bundle.resolve(result.sbomName()); Path cert = bundle.resolve("certification.json"); Path manifestPath = bundle.resolve("manifest.json");
      Files.write(jar, result.jarBytes()); Files.write(sbom, result.sbomBytes()); Files.write(cert, result.certificationBytes());
      Map<String, Object> manifest = new LinkedHashMap<>(); manifest.put("schema", "certified-candidate/v1"); manifest.put("identity", read(entity.getIdentityJson())); manifest.put("key_id", signingKeyId); manifest.put("base_commit", entity.getBaseCommit()); manifest.put("baseline_fail_manifest_hash", entity.getFailureManifestHash()); manifest.put("source_snapshot_id", entity.getSourceSnapshotId()); manifest.put("source_manifest_hash", entity.getSourceManifestHash()); manifest.put("patch_hash", entity.getPatchHash()); manifest.put("proposal_hash", entity.getProposalHash()); manifest.put("approval_hash", entity.getApprovalHash()); manifest.put("post_patch_test_result_hash", hash(result.testReportBytes())); manifest.put("build_commands", List.of(":runelite-client:compileJava", ":runelite-client:checkstyleMain", ":runelite-client:checkstyleTest", ":runelite-client:test", ":runelite-client:shadowJar", ":runelite-client:generateRuntimeSbom")); manifest.put("jar_name", result.jarName()); manifest.put("jar_size", (long) result.jarBytes().length); manifest.put("jar_sha256", hash(result.jarBytes())); manifest.put("sbom_name", result.sbomName()); manifest.put("sbom_size", (long) result.sbomBytes().length); manifest.put("sbom_sha256", hash(result.sbomBytes())); manifest.put("certification_document_hash", hash(result.certificationBytes())); manifest.put("control_allowed", false);
      Map<String, Object> candidate = new LinkedHashMap<>(); candidate.put("certification_hash", hash(result.certificationBytes())); manifest.put("candidate", candidate);
      CandidateSigner signer = new CandidateSigner(signingKeyId, Path.of(signingKeyPath));
      String candidateHash = signer.unsignedHash(mapper.valueToTree(manifest)); candidate.put("candidate_hash", candidateHash); candidate.put("candidate_id", "cand_" + candidateHash.substring(7, 31)); candidate.put("signature", signer.signCandidateHash(candidateHash));
      entity.setCandidateId(String.valueOf(candidate.get("candidate_id"))); entity.setCandidateHash(candidateHash); entity.setCandidateManifestJson(write(manifest));
      CurrentSliceIdentity frozen = mapper.convertValue(read(entity.getIdentityJson()), CurrentSliceIdentity.class);
      CurrentSliceIdentity.Candidate candidateIdentity = new CurrentSliceIdentity.Candidate(entity.getCandidateId(), candidateHash, String.valueOf(candidate.get("certification_hash")), String.valueOf(candidate.get("signature")));
      CurrentSliceIdentity finalIdentity = new CurrentSliceIdentity(frozen.schema(), frozen.task(), frozen.run(), frozen.client(), frozen.capture(), frozen.source(), frozen.graph(), frozen.model(), candidateIdentity, false);
      entity.setFinalIdentityJson(write(finalIdentity));
      if (finalIdentity.run() != null && finalIdentity.run().runId() != null) runs.findByRunId(finalIdentity.run().runId()).ifPresent(run -> { run.setIdentityJson(write(finalIdentity)); run.setStage("CANDIDATE_REGISTERING"); runs.save(run); });
      entity.setState("REGISTERING"); repository.save(entity);
      entity.setCandidateStagingDirectory(bundle.toAbsolutePath().normalize().toString());
      Files.writeString(manifestPath, write(manifest));
      entity.setRegistrationRequestHash(hash(Files.readAllBytes(manifestPath)));
      String registration = registry.register(manifestPath, jar, sbom, cert);
      entity.setRegistryResponseJson(registration);
      verifyRegistryReadback(manifest, registration, entity.getCandidateId(), entity.getCandidateHash());
      byte[] downloaded = registry.download(candidate.get("candidate_id").toString());
      if (!hash(downloaded).equals(candidate.get("jar_sha256"))) throw new IllegalStateException("REGISTRY_ARTIFACT_HASH_MISMATCH");
      entity.setState("REGISTERED"); persistCandidateRunIdentity(entity, "CANDIDATE_REGISTERED"); entity.setResultJson(write(Map.of("candidate_id", candidate.get("candidate_id"), "candidate_hash", candidateHash, "registration", registration, "download_sha256", hash(downloaded))));
    } catch (CandidateRegistryClient.RegistryException e) {
      retainBundle = e.isUncertain() || (e.getMessage() != null && e.getMessage().contains("404"));
      boolean notFound = e.getMessage() != null && e.getMessage().contains("404");
      entity.setState(e.isUncertain() ? "REGISTERING_UNCERTAIN" : (notFound ? "REGISTERING" : "REJECTED")); entity.setLastFailureCode(e.getMessage()); entity.setResultJson(write(Map.of("failure_code", e.getMessage())));
    } catch (Exception e) {
      entity.setState("FAILED"); entity.setLastFailureCode(e.getMessage()); entity.setResultJson(write(Map.of("failure_code", e.getMessage() == null ? "CANDIDATE_REGISTRATION_FAILED" : e.getMessage())));
    } finally {
      if (!retainBundle && bundle != null && !deleteBundle(bundle)) {
        entity.setState("FAILED"); entity.setLastFailureCode("CANDIDATE_BUNDLE_CLEANUP_FAILED"); entity.setResultJson(write(Map.of("failure_code", "CANDIDATE_BUNDLE_CLEANUP_FAILED")));
      }
    }
  }

  private void persistCandidateRunIdentity(PatchCandidateRun entity, String stage) {
    if (entity.getFinalIdentityJson() == null) return;
    try { CurrentSliceIdentity identity = mapper.readValue(entity.getFinalIdentityJson(), CurrentSliceIdentity.class); if (identity.run() != null) runs.findByRunId(identity.run().runId()).ifPresent(run -> { run.setIdentityJson(entity.getFinalIdentityJson()); run.setStage(stage); runs.save(run); }); } catch (Exception e) { throw error(HttpStatus.INTERNAL_SERVER_ERROR, "PERSISTED_JSON_CORRUPT", "final candidate identity is corrupt"); }
  }

  private void verifyRegistryReadback(Map<String, Object> localManifest, String registration, String candidateId, String candidateHash) {
    try {
      JsonNode response = mapper.readTree(registration);
      if (!"CERTIFIED_CANDIDATE".equals(response.path("state").asText())) throw new IllegalStateException("REGISTRY_STATE_INVALID");
      JsonNode returned = response.path("manifest");
      if (!candidateId.equals(returned.path("candidate").path("candidate_id").asText()) || !candidateHash.equals(returned.path("candidate").path("candidate_hash").asText())) throw new IllegalStateException("REGISTRY_IDENTITY_MISMATCH");
      JsonNode comparable = returned.deepCopy(); if (comparable.path("identity").isObject()) ((ObjectNode) comparable.path("identity")).remove("candidate");
      if (!CanonicalJsonHasher.canonical(mapper.valueToTree(localManifest)).equals(CanonicalJsonHasher.canonical(comparable))) throw new IllegalStateException("REGISTRY_MANIFEST_MISMATCH");
    } catch (CandidateRegistryClient.RegistryException e) { throw e; }
    catch (Exception e) { throw new IllegalStateException(e.getMessage() == null ? "REGISTRY_READBACK_INVALID" : e.getMessage(), e); }
  }

  private boolean deleteBundle(Path bundle) {
    try (var stream = Files.walk(bundle)) {
      final boolean[] clean = {true};
      stream.sorted(java.util.Comparator.reverseOrder()).forEach(path -> { try { Files.deleteIfExists(path); } catch (Exception e) { clean[0] = false; } });
      return clean[0] && !Files.exists(bundle);
    } catch (Exception e) { return false; }
  }
  private static String hash(byte[] bytes) { try { byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes); StringBuilder value = new StringBuilder("sha256:"); for (byte b : digest) value.append(String.format("%02x", b)); return value.toString(); } catch (Exception e) { throw new IllegalStateException(e); } }

  private PatchCandidateRun proposal(String id) { return repository.findByProposalId(id).orElseThrow(() -> error(HttpStatus.NOT_FOUND, "NOT_FOUND", "patch proposal not found")); }
  @SuppressWarnings("unchecked") private String modelHash(String identityJson) {
    Map<String, Object> identity = read(identityJson);
    Object model = identity.get("model");
    if (!(model instanceof Map)) throw error(HttpStatus.UNPROCESSABLE_ENTITY, "CANDIDATE_IDENTITY_INVALID", "model identity is required");
    Object value = ((Map<String, Object>) model).get("model_hash");
    if (value == null || String.valueOf(value).isBlank()) throw error(HttpStatus.UNPROCESSABLE_ENTITY, "CANDIDATE_IDENTITY_INVALID", "model_hash is required");
    return String.valueOf(value);
  }
  private JsonNode parse(String json) { try { return mapper.readTree(json); } catch (Exception e) { throw error(HttpStatus.UNPROCESSABLE_ENTITY, "SOURCE_MANIFEST_INVALID", "source manifest JSON is invalid"); } }
  private String write(Object value) { try { return mapper.writeValueAsString(value); } catch (Exception e) { throw error(HttpStatus.UNPROCESSABLE_ENTITY, "PATCH_INVALID", "patch evidence is not serializable"); } }
  private static String text(Map<String, Object> request, String key) { Object value = request.get(key); return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value); }
  @SuppressWarnings("unchecked") private Map<String, Object> read(String json) { try { return mapper.readValue(json, Map.class); } catch (Exception e) { throw error(HttpStatus.INTERNAL_SERVER_ERROR, "PERSISTED_JSON_CORRUPT", "persisted patch evidence is corrupt"); } }
  private Map<String, Object> readOptional(String json) { return json == null ? Map.of() : read(json); }
  private Map<String, Object> response(PatchCandidateRun entity) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("proposal_id", entity.getProposalId()); result.put("status", entity.getState()); result.put("failure_binding", read(entity.getBindingJson()));
    result.put("identity", read(entity.getFinalIdentityJson() == null ? entity.getIdentityJson() : entity.getFinalIdentityJson())); result.put("identity_through_model", read(entity.getIdentityJson())); result.put("base_commit", entity.getBaseCommit()); result.put("patch", entity.getPatchText()); result.put("patch_hash", entity.getPatchHash()); result.put("proposal_hash", entity.getProposalHash());
    result.put("approver_id", entity.getApproverId()); result.put("approved_at", entity.getApprovedAt()); result.put("approval_hash", entity.getApprovalHash()); result.put("candidate_id", entity.getCandidateId()); result.put("candidate_hash", entity.getCandidateHash()); result.put("candidate_manifest", entity.getCandidateManifestJson() == null ? null : read(entity.getCandidateManifestJson())); result.put("result", readOptional(entity.getResultJson())); result.put("control_allowed", false);
    return result;
  }
  private static PatchWorkflowException error(HttpStatus status, String code, String message) { return new PatchWorkflowException(status, code, message); }
  public static final class PatchWorkflowException extends RuntimeException { final HttpStatus status; final String code; PatchWorkflowException(HttpStatus status, String code, String message) { super(message); this.status = status; this.code = code; } public HttpStatus status() { return status; } public String code() { return code; } }
}
