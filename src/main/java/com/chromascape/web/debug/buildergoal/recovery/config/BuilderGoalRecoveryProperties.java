package com.chromascape.web.debug.buildergoal.recovery.config;

import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Server-owned configuration for Builder Goal compile recovery. Every limit, command key, and path
 * policy value lives here; no caller may narrow a required gate or supply a command.
 */
@Component
public class BuilderGoalRecoveryProperties {

  private final boolean mutationsEnabled;
  private final boolean isolatedValidationEnabled;
  private final boolean candidateRerunEnabled;
  private final boolean exactDigestActivationEnabled;
  private final boolean requireSignedManifest;
  private final String artifactRoot;
  private final String micro6ProjectDir;
  private final String gradleCommandKey;
  private final int rawLogLimitBytes;
  private final int diagnosticLimit;
  private final int promptLimitBytes;
  private final int responseLimitBytes;
  private final int maxProposalFiles;
  private final int maxProposalBytes;
  private final int maxProposalLines;
  private final int maxWorkerConcurrency;
  private final int maxAttemptsPerJob;
  private final Duration leaseDuration;
  private final Duration proposalTtl;
  private final Duration approvalTtl;
  private final String sanitizerVersion;
  private final String policyVersion;
  private final String signingKeyPath;
  private final String signingKeyId;
  private final String allowedPathPrefixes;

  public BuilderGoalRecoveryProperties(
      @Value("${builder-goal.recovery.mutations-enabled:false}") boolean mutationsEnabled,
      @Value("${builder-goal.recovery.isolated-validation-enabled:false}")
          boolean isolatedValidationEnabled,
      @Value("${builder-goal.recovery.candidate-rerun-enabled:false}")
          boolean candidateRerunEnabled,
      @Value("${micro6.exact-digest-activation-enabled:false}")
          boolean exactDigestActivationEnabled,
      @Value("${builder-goal.recovery.require-signed-manifest:true}") boolean requireSignedManifest,
      @Value("${builder-goal.recovery.artifact-root:data/compile-recovery-artifacts}")
          String artifactRoot,
      @Value("${builder-goal.recovery.micro6-project-dir:../micro6}") String micro6ProjectDir,
      @Value("${builder-goal.recovery.gradle-command-key:MICRO6_PRODUCTION_COMPILE}")
          String gradleCommandKey,
      @Value("${builder-goal.recovery.raw-log-limit-bytes:2097152}") int rawLogLimitBytes,
      @Value("${builder-goal.recovery.diagnostic-limit:500}") int diagnosticLimit,
      @Value("${builder-goal.recovery.prompt-limit-bytes:262144}") int promptLimitBytes,
      @Value("${builder-goal.recovery.response-limit-bytes:524288}") int responseLimitBytes,
      @Value("${builder-goal.recovery.max-proposal-files:12}") int maxProposalFiles,
      @Value("${builder-goal.recovery.max-proposal-bytes:262144}") int maxProposalBytes,
      @Value("${builder-goal.recovery.max-proposal-lines:1500}") int maxProposalLines,
      @Value("${builder-goal.recovery.worker.max-concurrency:1}") int maxWorkerConcurrency,
      @Value("${builder-goal.recovery.worker.max-attempts:2}") int maxAttemptsPerJob,
      @Value("${builder-goal.recovery.worker.lease-duration:PT15M}") Duration leaseDuration,
      @Value("${builder-goal.recovery.proposal-ttl:PT24H}") Duration proposalTtl,
      @Value("${builder-goal.recovery.approval-ttl:PT12H}") Duration approvalTtl,
      @Value("${builder-goal.recovery.sanitizer-version:2}") String sanitizerVersion,
      @Value("${builder-goal.recovery.policy-version:1}") String policyVersion,
      @Value("${leeuwbot.signing.private-key-path:}") String signingKeyPath,
      @Value("${leeuwbot.signing.key-id:}") String signingKeyId,
      @Value(
              "${builder-goal.recovery.allowed-path-prefixes:runelite-client/src/main/java/net/runelite/client/plugins/microbot/accountbuilder/,runelite-client/src/test/java/net/runelite/client/plugins/microbot/accountbuilder/}")
          String allowedPathPrefixes) {
    this.mutationsEnabled = mutationsEnabled;
    this.isolatedValidationEnabled = isolatedValidationEnabled;
    this.candidateRerunEnabled = candidateRerunEnabled;
    this.exactDigestActivationEnabled = exactDigestActivationEnabled;
    this.requireSignedManifest = requireSignedManifest;
    this.artifactRoot = artifactRoot;
    this.micro6ProjectDir = micro6ProjectDir;
    this.gradleCommandKey = gradleCommandKey;
    this.rawLogLimitBytes = rawLogLimitBytes;
    this.diagnosticLimit = diagnosticLimit;
    this.promptLimitBytes = promptLimitBytes;
    this.responseLimitBytes = responseLimitBytes;
    this.maxProposalFiles = maxProposalFiles;
    this.maxProposalBytes = maxProposalBytes;
    this.maxProposalLines = maxProposalLines;
    this.maxWorkerConcurrency = maxWorkerConcurrency;
    this.maxAttemptsPerJob = maxAttemptsPerJob;
    this.leaseDuration = leaseDuration;
    this.proposalTtl = proposalTtl;
    this.approvalTtl = approvalTtl;
    this.sanitizerVersion = sanitizerVersion;
    this.policyVersion = policyVersion;
    this.signingKeyPath = signingKeyPath;
    this.signingKeyId = signingKeyId;
    this.allowedPathPrefixes = allowedPathPrefixes;
  }

  public boolean isMutationsEnabled() {
    return mutationsEnabled;
  }

  public boolean isIsolatedValidationEnabled() {
    return isolatedValidationEnabled;
  }

  public boolean isCandidateRerunEnabled() {
    return candidateRerunEnabled;
  }

  public boolean isExactDigestActivationEnabled() {
    return exactDigestActivationEnabled;
  }

  public boolean isRequireSignedManifest() {
    return requireSignedManifest;
  }

  public String getArtifactRoot() {
    return artifactRoot;
  }

  public String getMicro6ProjectDir() {
    return micro6ProjectDir;
  }

  public String getGradleCommandKey() {
    return gradleCommandKey;
  }

  public int getRawLogLimitBytes() {
    return rawLogLimitBytes;
  }

  public int getDiagnosticLimit() {
    return diagnosticLimit;
  }

  public int getPromptLimitBytes() {
    return promptLimitBytes;
  }

  public int getResponseLimitBytes() {
    return responseLimitBytes;
  }

  public int getMaxProposalFiles() {
    return maxProposalFiles;
  }

  public int getMaxProposalBytes() {
    return maxProposalBytes;
  }

  public int getMaxProposalLines() {
    return maxProposalLines;
  }

  public int getMaxWorkerConcurrency() {
    return maxWorkerConcurrency;
  }

  public int getMaxAttemptsPerJob() {
    return maxAttemptsPerJob;
  }

  public Duration getLeaseDuration() {
    return leaseDuration;
  }

  public Duration getProposalTtl() {
    return proposalTtl;
  }

  public Duration getApprovalTtl() {
    return approvalTtl;
  }

  public String getSanitizerVersion() {
    return sanitizerVersion;
  }

  public String getPolicyVersion() {
    return policyVersion;
  }

  public String getSigningKeyPath() {
    return signingKeyPath;
  }

  public String getSigningKeyId() {
    return signingKeyId;
  }

  public List<String> getAllowedPathPrefixes() {
    return List.of(allowedPathPrefixes.split(","));
  }
}
