package com.chromascape.web.debug.buildergoal.recovery;

/**
 * Stable typed failure codes for Builder Goal compile recovery. Values are part of the operator
 * contract and must never be renamed; new codes are additive.
 */
public final class RecoveryFailureCodes {

  // Incident and evidence
  public static final String NOT_ELIGIBLE = "NOT_ELIGIBLE";
  public static final String COMPILE_EVIDENCE_MISSING = "COMPILE_EVIDENCE_MISSING";
  public static final String COMPILE_EVIDENCE_TOO_LARGE = "COMPILE_EVIDENCE_TOO_LARGE";
  public static final String DIAGNOSTICS_UNPARSEABLE = "DIAGNOSTICS_UNPARSEABLE";
  public static final String SANITIZATION_FAILED = "SANITIZATION_FAILED";
  public static final String CODEX_SESSION_NOT_BOUND = "CODEX_SESSION_NOT_BOUND";
  public static final String AI_PROVIDER_UNAVAILABLE = "AI_PROVIDER_UNAVAILABLE";
  public static final String AI_PROVIDER_OUTPUT_INVALID = "AI_PROVIDER_OUTPUT_INVALID";

  // Proposal and approval
  public static final String SOURCE_MANIFEST_MISSING = "SOURCE_MANIFEST_MISSING";
  public static final String SOURCE_PROVENANCE_UNVERIFIED = "SOURCE_PROVENANCE_UNVERIFIED";
  public static final String SOURCE_IDENTITY_MISMATCH = "SOURCE_IDENTITY_MISMATCH";
  public static final String PATCH_FORMAT_INVALID = "PATCH_FORMAT_INVALID";
  public static final String PATCH_PATH_FORBIDDEN = "PATCH_PATH_FORBIDDEN";
  public static final String PATCH_PREIMAGE_MISMATCH = "PATCH_PREIMAGE_MISMATCH";
  public static final String PATCH_POLICY_REJECTED = "PATCH_POLICY_REJECTED";
  public static final String PATCH_EVIDENCE_INCOMPLETE = "PATCH_EVIDENCE_INCOMPLETE";
  public static final String PROPOSAL_STALE = "PROPOSAL_STALE";
  public static final String PROPOSAL_EXPIRED = "PROPOSAL_EXPIRED";
  public static final String APPROVAL_REQUIRED = "APPROVAL_REQUIRED";
  public static final String APPROVAL_FORBIDDEN = "APPROVAL_FORBIDDEN";
  public static final String APPROVAL_INVALIDATED = "APPROVAL_INVALIDATED";

  // Validation
  public static final String WORKER_CAPACITY_EXHAUSTED = "WORKER_CAPACITY_EXHAUSTED";
  public static final String WORKER_LOST = "WORKER_LOST";
  public static final String LEASE_EXPIRED = "LEASE_EXPIRED";
  public static final String ISOLATED_CLONE_FAILED = "ISOLATED_CLONE_FAILED";
  public static final String BASE_MISMATCH = "BASE_MISMATCH";
  public static final String PATCH_CHECK_FAILED = "PATCH_CHECK_FAILED";
  public static final String PATCH_APPLY_FAILED = "PATCH_APPLY_FAILED";
  public static final String PATCH_ALLOWLIST_VIOLATION = "PATCH_ALLOWLIST_VIOLATION";
  public static final String POLICY_FAILURE = "POLICY_FAILURE";
  public static final String COMPILE_FAILURE = "COMPILE_FAILURE";
  public static final String STATIC_FAILURE = "STATIC_FAILURE";
  public static final String TEST_FAILURE = "TEST_FAILURE";
  public static final String PACKAGE_FAILURE = "PACKAGE_FAILURE";
  public static final String SBOM_FAILURE = "SBOM_FAILURE";
  public static final String NON_REPRODUCIBLE_BUILD = "NON_REPRODUCIBLE_BUILD";
  public static final String ARTIFACT_FINALIZATION_FAILURE = "ARTIFACT_FINALIZATION_FAILURE";
  public static final String TIMEOUT = "TIMEOUT";
  public static final String CANCELLED = "CANCELLED";
  public static final String CLEANUP_UNCERTAIN = "CLEANUP_UNCERTAIN";
  public static final String PASS_BUDGET_EXHAUSTED = "PASS_BUDGET_EXHAUSTED";

  // Rerun
  public static final String CANDIDATE_NOT_BUILD_VALIDATED = "CANDIDATE_NOT_BUILD_VALIDATED";
  public static final String CANDIDATE_DIGEST_MISMATCH = "CANDIDATE_DIGEST_MISMATCH";
  public static final String CANDIDATE_ACTIVATION_UNSUPPORTED = "CANDIDATE_ACTIVATION_UNSUPPORTED";
  public static final String RERUN_AUTHORITY_REQUIRED = "RERUN_AUTHORITY_REQUIRED";
  public static final String DUPLICATE_OR_CONFLICTING_DISPATCH =
      "DUPLICATE_OR_CONFLICTING_DISPATCH";
  public static final String UNCERTAIN_OUTCOME = "UNCERTAIN_OUTCOME";
  public static final String NEEDS_RECONCILIATION = "NEEDS_RECONCILIATION";

  // Capability gating
  public static final String RECOVERY_MUTATIONS_DISABLED = "RECOVERY_MUTATIONS_DISABLED";
  public static final String ISOLATED_VALIDATION_DISABLED = "ISOLATED_VALIDATION_DISABLED";
  public static final String CANDIDATE_RERUN_DISABLED = "CANDIDATE_RERUN_DISABLED";

  private RecoveryFailureCodes() {}
}
