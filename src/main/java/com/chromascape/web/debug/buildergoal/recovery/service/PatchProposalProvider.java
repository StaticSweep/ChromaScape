package com.chromascape.web.debug.buildergoal.recovery.service;

import com.chromascape.web.debug.buildergoal.recovery.RecoveryException;
import com.chromascape.web.debug.buildergoal.recovery.RecoveryFailureCodes;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Request boundary between compile recovery and the run-bound Codex provider. The provider must
 * return a canonical unified diff; loose oldText/newText patch drafts are rejected for this path.
 */
public interface PatchProposalProvider {

  record ProviderOutput(String canonicalDiff, Map<String, Object> generatorMetadata) {}

  ProviderOutput requestProposal(String prompt, Map<String, Object> context) throws Exception;

  /** Adapts the run-bound Codex session's provider to the canonical-diff contract. */
  @Component
  class RunBoundCodexAdapter implements PatchProposalProvider {

    private final com.chromascape.web.debug.buildergoal.service.BuilderGoalCodexOrchestrationService
        orchestrationService;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    public RunBoundCodexAdapter(
        com.chromascape.web.debug.buildergoal.service.BuilderGoalCodexOrchestrationService
            orchestrationService,
        com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
      this.orchestrationService = orchestrationService;
      this.objectMapper = objectMapper;
    }

    @Override
    public ProviderOutput requestProposal(String prompt, Map<String, Object> context)
        throws Exception {
      String sessionId = String.valueOf(context.get("codexSessionId"));
      Map<String, Object> payload =
          Map.of(
              "prompt",
              prompt == null ? "" : prompt,
              "mode",
              "CANONICAL_DIFF",
              "requireUnifiedDiff",
              true);
      Map<String, Object> response =
          orchestrationService.generatePatchPlan(sessionId, objectMapper.valueToTree(payload));
      String diff = extractCanonicalDiff(response);
      if (diff == null) {
        throw RecoveryException.unprocessable(
            RecoveryFailureCodes.AI_PROVIDER_OUTPUT_INVALID,
            "The provider did not return a canonical unified diff; loose oldText/newText patches"
                + " are rejected for compile recovery");
      }
      return new ProviderOutput(diff, response);
    }

    static String extractCanonicalDiff(Map<String, Object> response) {
      String diff = findDiff(response, 0);
      if (diff == null || !diff.contains("diff --git ")) {
        return null;
      }
      return diff;
    }

    private static String findDiff(Map<?, ?> map, int depth) {
      if (map == null || depth > 4) {
        return null;
      }
      for (String key :
          List.of("diff", "canonical_diff", "canonicalDiff", "unifiedDiff", "patch")) {
        Object value = map.get(key);
        if (value instanceof String text && text.contains("diff --git ")) {
          return text;
        }
      }
      for (Object value : map.values()) {
        if (value instanceof Map<?, ?> nested) {
          String found = findDiff(nested, depth + 1);
          if (found != null) {
            return found;
          }
        }
      }
      return null;
    }
  }
}
