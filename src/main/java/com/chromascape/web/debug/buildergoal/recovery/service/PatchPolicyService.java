package com.chromascape.web.debug.buildergoal.recovery.service;

import com.chromascape.web.debug.buildergoal.recovery.RecoveryException;
import com.chromascape.web.debug.buildergoal.recovery.RecoveryFailureCodes;
import com.chromascape.web.debug.buildergoal.recovery.config.BuilderGoalRecoveryProperties;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * Server-owned proposal policy: path allowlist/denylist, size limits, secret scanning, and
 * capability scanning. Operators cannot narrow these checks; violations fail closed with stable
 * codes before any proposal reaches review.
 */
@Service
public class PatchPolicyService {

  public record PolicyViolation(String code, String detail) {}

  public record PolicyReport(
      boolean accepted,
      String policyVersion,
      List<PolicyViolation> violations,
      List<String> allowedPaths,
      List<String> forbiddenPaths,
      boolean secretScanClean) {}

  private static final Set<String> DENIED_SEGMENTS =
      Set.of(".git", "build", "gradle", "vendor", "node_modules", "secrets", "credentials");
  private static final Set<String> DENIED_SUFFIXES =
      Set.of(".jks", ".keystore", ".p12", ".pem", ".key", ".env", ".token", ".password", ".log");
  private static final Pattern SECRET_PATTERN =
      Pattern.compile(
          "(?i)(password|passwd|secret|api[_-]?key|token|authorization\\s*:)\\s*[=:]?\\s*['\"]?[A-Za-z0-9+/=_-]{8,}");
  private static final Pattern CAPABILITY_PATTERN =
      Pattern.compile(
          "(?i)(Runtime\\.getRuntime\\(\\)\\.exec|ProcessBuilder|new\\s+URL\\(|HttpURLConnection|"
              + "Socket\\(|Files\\.delete|System\\.exit|Class\\.forName\\s*\\(\\s*\"sun\")");

  private final BuilderGoalRecoveryProperties properties;

  public PatchPolicyService(BuilderGoalRecoveryProperties properties) {
    this.properties = properties;
  }

  public PolicyReport evaluate(PatchParser.ParsedPatch patch, List<String> affectedPaths) {
    List<PolicyViolation> violations = new ArrayList<>();
    List<String> allowed = new ArrayList<>();
    List<String> forbidden = new ArrayList<>();
    List<String> prefixes = properties.getAllowedPathPrefixes();
    for (String path : affectedPaths) {
      if (isDeniedPath(path)) {
        forbidden.add(path);
        violations.add(new PolicyViolation(RecoveryFailureCodes.PATCH_PATH_FORBIDDEN, path));
        continue;
      }
      boolean allowedPath = false;
      for (String prefix : prefixes) {
        String normalizedPrefix = prefix.trim().replace('\\', '/');
        if (!normalizedPrefix.isEmpty() && path.startsWith(normalizedPrefix)) {
          allowedPath = true;
          break;
        }
      }
      if (allowedPath) {
        allowed.add(path);
      } else {
        forbidden.add(path);
        violations.add(new PolicyViolation(RecoveryFailureCodes.PATCH_PATH_FORBIDDEN, path));
      }
    }
    if (patch.files().size() > properties.getMaxProposalFiles()) {
      violations.add(
          new PolicyViolation(
              RecoveryFailureCodes.PATCH_POLICY_REJECTED,
              "Proposal exceeds the maximum number of files"));
    }
    if (patch.totalBytes() > properties.getMaxProposalBytes()) {
      violations.add(
          new PolicyViolation(
              RecoveryFailureCodes.PATCH_POLICY_REJECTED,
              "Proposal exceeds the maximum diff byte budget"));
    }
    if (patch.totalLines() > properties.getMaxProposalLines()) {
      violations.add(
          new PolicyViolation(
              RecoveryFailureCodes.PATCH_POLICY_REJECTED,
              "Proposal exceeds the maximum changed-line budget"));
    }
    boolean secretClean = true;
    for (PatchParser.PatchFile file : patch.files()) {
      for (String line : file.newLines()) {
        if (SECRET_PATTERN.matcher(line).find()) {
          secretClean = false;
          violations.add(
              new PolicyViolation(
                  RecoveryFailureCodes.PATCH_POLICY_REJECTED,
                  "Potential secret in new content of " + file.path()));
        }
        if (CAPABILITY_PATTERN.matcher(line).find()) {
          violations.add(
              new PolicyViolation(
                  RecoveryFailureCodes.PATCH_POLICY_REJECTED,
                  "New network/process/control capability requires elevated review: "
                      + file.path()));
        }
      }
    }
    if (!forbidden.isEmpty()) {
      violations.add(
          new PolicyViolation(
              RecoveryFailureCodes.PATCH_PATH_FORBIDDEN,
              "Changed paths outside the allowed AccountBuilder prefixes"));
    }
    return new PolicyReport(
        violations.isEmpty(),
        properties.getPolicyVersion(),
        List.copyOf(violations),
        List.copyOf(allowed),
        List.copyOf(forbidden),
        secretClean);
  }

  static boolean isDeniedPath(String path) {
    String lower = path.toLowerCase(Locale.ROOT);
    for (String segment : lower.split("/")) {
      if (DENIED_SEGMENTS.contains(segment)) {
        return true;
      }
    }
    for (String suffix : DENIED_SUFFIXES) {
      if (lower.endsWith(suffix)) {
        return true;
      }
    }
    return false;
  }

  public Map<String, Object> reportToJson(PolicyReport report) {
    Map<String, Object> json = new LinkedHashMap<>();
    json.put("schema", "patch-policy-report/v1");
    json.put("policyVersion", report.policyVersion());
    json.put("accepted", report.accepted());
    json.put("allowedPaths", report.allowedPaths());
    json.put("forbiddenPaths", report.forbiddenPaths());
    json.put("secretScanClean", report.secretScanClean());
    List<Map<String, Object>> violations = new ArrayList<>();
    for (PolicyViolation violation : report.violations()) {
      violations.add(Map.of("code", violation.code(), "detail", violation.detail()));
    }
    json.put("violations", violations);
    return json;
  }

  public void requireAccepted(PolicyReport report) {
    if (!report.accepted()) {
      String first =
          report.violations().isEmpty() ? "policy rejected" : report.violations().get(0).code();
      throw RecoveryException.unprocessable(
          RecoveryFailureCodes.PATCH_POLICY_REJECTED, "Proposal failed policy: " + first);
    }
  }
}
