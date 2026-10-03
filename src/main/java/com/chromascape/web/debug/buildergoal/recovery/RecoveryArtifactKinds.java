package com.chromascape.web.debug.buildergoal.recovery;

import java.util.Set;

/** Allowlisted artifact kinds persisted by the compile recovery pipeline. */
public final class RecoveryArtifactKinds {

  public static final String ORIGINAL_COMPILE_LOG = "ORIGINAL_COMPILE_LOG";
  public static final String COMPILER_DIAGNOSTICS = "COMPILER_DIAGNOSTICS";
  public static final String AI_PROMPT = "AI_PROMPT";
  public static final String PROPOSAL = "PROPOSAL";
  public static final String POLICY_REPORT = "POLICY_REPORT";
  public static final String PATCH_APPLY_LOG = "PATCH_APPLY_LOG";
  public static final String COMPILE_LOG = "COMPILE_LOG";
  public static final String STATIC_LOG = "STATIC_LOG";
  public static final String TEST_LOG = "TEST_LOG";
  public static final String PACKAGE_LOG = "PACKAGE_LOG";
  public static final String SBOM = "SBOM";
  public static final String CANDIDATE_MANIFEST = "CANDIDATE_MANIFEST";
  public static final String CLEANUP_REPORT = "CLEANUP_REPORT";

  public static final Set<String> ALL =
      Set.of(
          ORIGINAL_COMPILE_LOG,
          COMPILER_DIAGNOSTICS,
          AI_PROMPT,
          PROPOSAL,
          POLICY_REPORT,
          PATCH_APPLY_LOG,
          COMPILE_LOG,
          STATIC_LOG,
          TEST_LOG,
          PACKAGE_LOG,
          SBOM,
          CANDIDATE_MANIFEST,
          CLEANUP_REPORT);

  public static boolean isAllowlisted(String kind) {
    return kind != null && ALL.contains(kind);
  }

  private RecoveryArtifactKinds() {}
}
