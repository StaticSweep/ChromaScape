package com.chromascape.web.aiplayer.routine.service;

import com.chromascape.web.aiplayer.contract.CanonicalJsonHasher;
import com.chromascape.web.aiplayer.routine.entity.PatchCandidateRun;
import com.chromascape.web.aiplayer.routine.repository.PatchCandidateRunRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
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
      -        // Convert fish name to Fish enum - simple mapping
      -        switch (fishName.toLowerCase()) {
      +        if (fishName == null || fishName.isBlank()) {
      +            throw new UnsupportedFishTypeException(fishName);
      +        }
      +        // Convert fish name to Fish enum - simple mapping
      +        switch (fishName.trim().toLowerCase()) {
      @@ -121,3 +124,2 @@
      -                System.out.println("[FishingExecutionTask] Warning: Unknown fish name '" + fishName + "', defaulting to SHRIMP");
      -                return Fish.SHRIMP;
      +                throw new UnsupportedFishTypeException(fishName);
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
      +import static org.junit.Assert.fail;
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
  private final Micro6SourceManifestClient source;
  private final CurrentSliceIdentityValidator identities;
  private final CandidateRegistryClient registry;
  @Value("${builder-goal.recovery.micro6-project-dir:../micro6}") private String micro6Root;
  @Value("${leeuwbot.signing.private-key-path:}") private String signingKeyPath;
  @Value("${leeuwbot.signing.key-id:}") private String signingKeyId;

  public PatchCandidateWorkflowService(ObjectMapper mapper, IsolatedPatchWorker worker,      PatchCandidateRunRepository repository, Micro6SourceManifestClient source, CurrentSliceIdentityValidator identities, CandidateRegistryClient registry) {
    this.mapper = mapper; this.worker = worker; this.repository = repository; this.source = source; this.identities = identities; this.registry = registry;
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
    PatchCandidateRun entity = repository.findByProposalId("proposal_" + proposalHash.substring(7, 31)).orElseGet(PatchCandidateRun::new);
    entity.setProposalId("proposal_" + proposalHash.substring(7, 31));
    entity.setState("SOURCE_RESOLVED");
    entity.setFailureManifestHash(SEEDED_FAIL);
    entity.setSourceSnapshotId(resolved.sourceSnapshotId());
    entity.setSourceManifestHash(resolved.sourceManifestHash());
    entity.setSourceFileId(resolved.fileId());
    entity.setSourceRelativePath(resolved.relativePath());
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
    if (!"PATCH_PROPOSED".equals(entity.getState())) throw error(HttpStatus.CONFLICT, "PATCH_APPROVAL_STALE", "proposal is no longer approvable");
    Instant approvedAt = Instant.now();
    ObjectNode approval = mapper.createObjectNode();
    approval.put("proposal_id", entity.getProposalId());
    approval.put("proposal_hash", entity.getProposalHash());
    approval.put("patch_hash", entity.getPatchHash());
    approval.put("base_commit", entity.getBaseCommit());
    approval.put("source_snapshot_id", entity.getSourceSnapshotId());
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
  public Map<String, Object> build(String id) {
    PatchCandidateRun entity = proposal(id);
    if (!"APPROVED".equals(entity.getState())) throw error(HttpStatus.CONFLICT, "PATCH_NOT_APPROVED", "explicit human approval is required");
    entity.setState("BUILDING");
    repository.save(entity);
    IsolatedPatchWorker.BuildResult result = worker.run(new IsolatedPatchWorker.BuildRequest(
        Path.of(micro6Root), entity.getBaseCommit(), entity.getPatchText(),
        Set.of(SOURCE_FILE, EXCEPTION_FILE, TEST_FILE), List.of(), entity.getSourceSnapshotId(),
        entity.getSourceFileId(), entity.getStartLine(), entity.getEndLine(), entity.getPreimageHash(), entity.getFailureManifestHash(),
        entity.getProposalHash(), entity.getApprovalHash(), entity.getPatchHash(), entity.getSourceManifestHash(), modelHash(entity.getIdentityJson())));
    entity.setState(result.status());
    Map<String, Object> buildResult = new LinkedHashMap<>();
    buildResult.put("status", result.status());
    buildResult.put("changed_files", result.changedFiles());
    buildResult.put("failure_code", result.failureCode());
    entity.setResultJson(write(buildResult));
    if ("BUILT".equals(result.status())) {
      registerCandidate(entity, result);
    }
    repository.save(entity);
    return response(entity);
  }

  @Transactional(readOnly = true)
  public Map<String, Object> get(String id) { return response(proposal(id)); }

  private void registerCandidate(PatchCandidateRun entity, IsolatedPatchWorker.BuildResult result) {
    if (signingKeyPath == null || signingKeyPath.isBlank() || signingKeyId == null || signingKeyId.isBlank()) {
      entity.setState("FAILED"); entity.setResultJson(write(Map.of("failure_code", "SIGNING_KEY_UNAVAILABLE"))); return;
    }
    if (result.jarBytes() == null || result.sbomBytes() == null || result.certificationBytes() == null) {
      entity.setState("FAILED"); entity.setResultJson(write(Map.of("failure_code", "CERTIFICATION_ARTIFACT_MISSING"))); return;
    }
    try {
      Path bundle = Files.createTempDirectory("sc967-candidate-");
      Path jar = bundle.resolve(result.jarName()); Path sbom = bundle.resolve(result.sbomName()); Path cert = bundle.resolve("certification.json"); Path manifestPath = bundle.resolve("manifest.json");
      Files.write(jar, result.jarBytes()); Files.write(sbom, result.sbomBytes()); Files.write(cert, result.certificationBytes());
      Map<String, Object> manifest = new LinkedHashMap<>(); manifest.put("schema", "certified-candidate/v1"); manifest.put("identity", read(entity.getIdentityJson())); manifest.put("control_allowed", false);
      Map<String, Object> candidate = new LinkedHashMap<>(); candidate.put("key_id", signingKeyId); candidate.put("base_commit", entity.getBaseCommit()); candidate.put("baseline_failure_manifest_hash", entity.getFailureManifestHash()); candidate.put("source_snapshot_id", entity.getSourceSnapshotId()); candidate.put("source_manifest_hash", entity.getSourceManifestHash()); candidate.put("patch_hash", entity.getPatchHash()); candidate.put("proposal_hash", entity.getProposalHash()); candidate.put("approval_hash", entity.getApprovalHash()); candidate.put("post_patch_test_result_hash", hash(result.testReportBytes())); candidate.put("build_command_ids", List.of("compileJava", "checkstyleMain", "checkstyleTest", "test", "shadowJar", "generateRuntimeSbom")); candidate.put("jar_name", result.jarName()); candidate.put("jar_size", (long) result.jarBytes().length); candidate.put("jar_sha256", hash(result.jarBytes())); candidate.put("sbom_name", result.sbomName()); candidate.put("sbom_size", (long) result.sbomBytes().length); candidate.put("sbom_sha256", hash(result.sbomBytes())); candidate.put("certification_document_hash", hash(result.certificationBytes())); candidate.put("control_allowed", false); manifest.put("candidate", candidate);
      CandidateSigner signer = new CandidateSigner(signingKeyId, Path.of(signingKeyPath));
      String candidateHash = signer.unsignedHash(mapper.valueToTree(manifest)); candidate.put("candidate_hash", candidateHash); candidate.put("candidate_id", "cand_" + candidateHash.substring(7, 31)); candidate.put("signature", signer.signCandidateHash(candidateHash));
      Files.writeString(manifestPath, write(manifest));
      String registration = registry.register(manifestPath, jar, sbom, cert);
      byte[] downloaded = registry.download(candidate.get("candidate_id").toString());
      if (!hash(downloaded).equals(candidate.get("jar_sha256"))) throw new IllegalStateException("REGISTRY_ARTIFACT_HASH_MISMATCH");
      entity.setState("REGISTERED"); entity.setResultJson(write(Map.of("candidate_id", candidate.get("candidate_id"), "candidate_hash", candidateHash, "registration", registration, "download_sha256", hash(downloaded))));
      if (!deleteBundle(bundle)) { entity.setState("FAILED"); entity.setResultJson(write(Map.of("failure_code", "CANDIDATE_BUNDLE_CLEANUP_FAILED"))); }
    } catch (CandidateRegistryClient.RegistryException e) {
      entity.setState(e.isUncertain() ? "REGISTERING_UNCERTAIN" : "REJECTED"); entity.setResultJson(write(Map.of("failure_code", e.getMessage())));
    } catch (Exception e) {
      entity.setState("FAILED"); entity.setResultJson(write(Map.of("failure_code", e.getMessage() == null ? "CANDIDATE_REGISTRATION_FAILED" : e.getMessage())));
    }
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
  private String write(Object value) { try { return mapper.writeValueAsString(value); } catch (Exception e) { throw error(HttpStatus.UNPROCESSABLE_ENTITY, "PATCH_INVALID", "patch evidence is not serializable"); } }
  private static String text(Map<String, Object> request, String key) { Object value = request.get(key); return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value); }
  @SuppressWarnings("unchecked") private Map<String, Object> read(String json) { try { return mapper.readValue(json, Map.class); } catch (Exception e) { return Map.of(); } }
  private Map<String, Object> response(PatchCandidateRun entity) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("proposal_id", entity.getProposalId()); result.put("status", entity.getState()); result.put("failure_binding", read(entity.getBindingJson()));
    result.put("identity", read(entity.getIdentityJson())); result.put("base_commit", entity.getBaseCommit()); result.put("patch", entity.getPatchText()); result.put("patch_hash", entity.getPatchHash()); result.put("proposal_hash", entity.getProposalHash());
    result.put("approver_id", entity.getApproverId()); result.put("approved_at", entity.getApprovedAt()); result.put("approval_hash", entity.getApprovalHash()); result.put("result", read(entity.getResultJson())); result.put("control_allowed", false);
    return result;
  }
  private static PatchWorkflowException error(HttpStatus status, String code, String message) { return new PatchWorkflowException(status, code, message); }
  public static final class PatchWorkflowException extends RuntimeException { final HttpStatus status; final String code; PatchWorkflowException(HttpStatus status, String code, String message) { super(message); this.status = status; this.code = code; } public HttpStatus status() { return status; } public String code() { return code; } }
}
