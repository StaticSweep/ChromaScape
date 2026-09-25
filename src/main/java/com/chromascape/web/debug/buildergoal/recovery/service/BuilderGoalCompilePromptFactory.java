package com.chromascape.web.debug.buildergoal.recovery.service;

import com.chromascape.web.debug.buildergoal.recovery.config.BuilderGoalRecoveryProperties;
import com.chromascape.web.debug.buildergoal.recovery.entity.BuilderGoalRecoveryIncident;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Builds a deterministic, checksummed repair prompt from the incident, sanitized diagnostics, run
 * identity, and server-owned constraints and validation plan. The prompt is bounded and identical
 * inputs always produce identical bytes.
 */
@Component
public class BuilderGoalCompilePromptFactory {

  private final BuilderGoalRecoveryProperties properties;

  public BuilderGoalCompilePromptFactory(BuilderGoalRecoveryProperties properties) {
    this.properties = properties;
  }

  public String build(
      BuilderGoalRecoveryIncident incident,
      Map<String, Object> runIdentity,
      List<BuilderGoalCompileDiagnosticParser.Diagnostic> diagnostics,
      String sourceIdentitySummary) {
    StringBuilder prompt = new StringBuilder();
    prompt.append("You are preparing a Micro6 production source patch proposal.\n\n");
    prompt.append("Contract:\n");
    prompt.append("- Output exactly one canonical unified diff (git format, a/ b/ prefixes).\n");
    prompt.append(
        "- Only repository-relative forward-slash paths; no absolute paths, drive letters, or"
            + " traversal.\n");
    prompt.append(
        "- Modify only files under the allowed AccountBuilder production and test prefixes.\n");
    prompt.append("- Every changed file must cite the failing diagnostics that justify it.\n");
    prompt.append(
        "- Do not change build scripts, dependencies, generated code, or credentials material.\n\n");
    prompt.append("Incident:\n");
    prompt.append("- incident_id: ").append(incident.getIncidentId()).append('\n');
    prompt.append("- incident_type: ").append(incident.getIncidentType()).append('\n');
    prompt.append("- failure_code: ").append(incident.getFailureCode()).append('\n');
    prompt.append("- iteration_id: ").append(incident.getIterationId()).append('\n');
    prompt.append("- correlation_id: ").append(incident.getCorrelationId()).append('\n');
    prompt.append("\nRun identity:\n");
    appendBoundedMap(prompt, runIdentity);
    prompt.append("\nSource identity:\n").append(sourceIdentitySummary).append('\n');
    prompt.append("\nSanitized compiler diagnostics (").append(diagnostics.size()).append("):\n");
    for (BuilderGoalCompileDiagnosticParser.Diagnostic diagnostic : diagnostics) {
      prompt
          .append("- [")
          .append(diagnostic.tool())
          .append('/')
          .append(diagnostic.severity())
          .append("] ")
          .append(diagnostic.relativeFile() == null ? "(task)" : diagnostic.relativeFile())
          .append(':')
          .append(diagnostic.line())
          .append(" ")
          .append(diagnostic.message())
          .append('\n');
    }
    prompt.append("\nServer-owned validation plan (not negotiable):\n");
    prompt.append("1. strict git apply --check and apply against the pinned commit\n");
    prompt.append("2. canonical compile: ").append(properties.getGradleCommandKey()).append('\n');
    prompt.append("3. checkstyle and affected unit tests\n");
    prompt.append("4. candidate packaging with SBOM\n");
    String text = prompt.toString();
    int limit = properties.getPromptLimitBytes();
    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    if (bytes.length <= limit) {
      return text;
    }
    return new String(bytes, 0, limit, StandardCharsets.UTF_8);
  }

  private void appendBoundedMap(StringBuilder prompt, Map<String, Object> values) {
    if (values == null) {
      return;
    }
    int identityLimitBytes = 20_000;
    values.forEach(
        (key, value) -> {
          if (prompt.length() < identityLimitBytes) {
            prompt.append("- ").append(key).append(": ");
            if (value instanceof JsonNode jsonNode) {
              prompt.append(jsonNode.asText(""));
            } else {
              prompt.append(String.valueOf(value));
            }
            prompt.append('\n');
          }
        });
  }
}
